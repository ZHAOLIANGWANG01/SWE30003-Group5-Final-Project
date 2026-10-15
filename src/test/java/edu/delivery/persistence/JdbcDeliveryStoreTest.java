package edu.delivery.persistence;

import static edu.delivery.contract.DeliveryData.*;
import static edu.delivery.contract.StoreException.Code.*;
import static org.assertj.core.api.Assertions.*;

import edu.delivery.contract.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:store;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000",
      "app.demo.enabled=false"
    })
class JdbcDeliveryStoreTest {
  @Autowired DeliveryStore store;
  @Autowired JdbcTemplate jdbc;
  long customer, office, staff, secondStaff;
  final OffsetDateTime start = OffsetDateTime.parse("2026-10-20T09:00:00+11:00");

  @BeforeEach
  void setup() {
    for (String table :
        List.of(
            "delivery_events",
            "schedule_changes",
            "delivery_schedules",
            "staff_availability",
            "delivery_quotes",
            "parcels",
            "delivery_requests",
            "delivery_staff",
            "users")) {
      jdbc.update("DELETE FROM " + table);
    }
    customer = user("customer", "CUSTOMER");
    office = user("office", "OFFICE_STAFF");
    long driver = user("driver", "DELIVERY_STAFF"), driver2 = user("driver2", "DELIVERY_STAFF");
    jdbc.update("INSERT INTO delivery_staff(user_id) VALUES (?)", driver);
    jdbc.update("INSERT INTO delivery_staff(user_id) VALUES (?)", driver2);
    staff = store.findActiveStaff().get(0).id();
    secondStaff = store.findActiveStaff().get(1).id();
  }

  long user(String name, String role) {
    jdbc.update(
        "INSERT INTO users(username,password_hash,role,display_name) VALUES (?,'test-hash',?,?)",
        name,
        role,
        name);
    return store.findAccountByUsername(name).orElseThrow().id();
  }

  RequestDetails details(String parcel) {
    return new RequestDetails(
        "Alice",
        "0400000000",
        "1 Pickup Street",
        "2 Delivery Street",
        "Bob",
        "0400000001",
        parcel,
        "");
  }

  RequestData request(String key) {
    return store.createRequest(new RequestDraft(customer, key, details("Box")));
  }

  QuoteDraft quote(long requestId) {
    return new QuoteDraft(
        requestId, new BigDecimal("25.50"), start, start.plusHours(1), start.minusDays(1));
  }

  ScheduleData schedule(long requestId) {
    return store.inTransaction(
        tx -> {
          tx.lockStaff(staff);
          tx.lockRequest(requestId);
          return tx.createSchedule(new ScheduleDraft(requestId, staff, start, start.plusHours(1)));
        });
  }

  void code(Runnable action, StoreException.Code expected) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            StoreException.class, e -> assertThat(e.code()).isEqualTo(expected));
  }

  @Test
  void requestParcelAndIdempotencyAreAtomic() {
    RequestData r = request("same");
    assertThat(request("same").id()).isEqualTo(r.id());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parcels", Integer.class)).isEqualTo(1);
    code(
        () -> store.createRequest(new RequestDraft(customer, "same", details("Different box"))),
        CONFLICT);
    assertThat(store.findRequests(new RequestFilter(customer, RequestStatus.REQUESTED, 0, 20)))
        .hasSize(1);
    assertThat(store.findRequests(new RequestFilter(customer, RequestStatus.DELIVERED, 0, 20)))
        .isEmpty();
  }

  @Test
  void idempotencyRemembersOriginalAfterEditing() {
    RequestData r = request("edit");
    RequestData changed = store.updateRequestDetails(r.id(), 0, details("Revised box"));
    assertThat(changed.version()).isEqualTo(1);
    assertThat(request("edit").details().parcelDescription()).isEqualTo("Revised box");
    code(() -> store.updateRequestDetails(r.id(), 0, details("Old update")), STALE_DATA);
    assertThat(store.findRequest(r.id()).orElseThrow().details().parcelDescription())
        .isEqualTo("Revised box");
  }

  @Test
  void invalidInputsAndUnknownReferencesFailCleanly() {
    code(
        () -> store.createRequest(new RequestDraft(office, "bad-role", details("Box"))),
        INVALID_DATA);
    code(() -> store.createRequest(new RequestDraft(999999, "missing", details("Box"))), NOT_FOUND);
    code(() -> store.createRequest(new RequestDraft(customer, "blank", details(""))), INVALID_DATA);
    code(() -> store.findRequests(new RequestFilter(null, null, 0, 101)), INVALID_DATA);
    code(() -> store.updateRequestDetails(999999, 0, details("Box")), NOT_FOUND);
    assertThat(store.findRequest(999999)).isEmpty();
  }

  @Test
  void transactionRollsBackRequestAndParcel() {
    assertThatThrownBy(
            () ->
                store.inTransaction(
                    tx -> {
                      request("rollback");
                      throw new IllegalStateException("force rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_requests", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parcels", Integer.class)).isZero();
  }

  @Test
  void quoteAndRequestUpdateShareTransaction() {
    RequestData r = request("quote");
    QuoteData q =
        store.inTransaction(
            tx -> {
              tx.lockRequest(r.id());
              QuoteData created = tx.createQuote(quote(r.id()));
              tx.saveStatusAndEvent(
                  r.id(),
                  r.version(),
                  RequestStatus.QUOTED,
                  new EventDraft(EventType.QUOTED, start, office, ""));
              return created;
            });
    assertThat(q.amount()).isEqualByComparingTo("25.50");
    assertThat(store.findQuote(q.id())).contains(q);
    QuoteData accepted = store.updateQuoteStatus(q.id(), 0, QuoteStatus.ACCEPTED);
    assertThat(accepted.version()).isEqualTo(1);
    code(() -> store.updateQuoteStatus(q.id(), 0, QuoteStatus.DECLINED), STALE_DATA);
    assertThat(store.findRequest(r.id()).orElseThrow().status()).isEqualTo(RequestStatus.QUOTED);
  }

  @Test
  void quoteInsertRollsBackWhenRelatedStatusWriteFails() {
    RequestData r = request("quote-rollback");
    code(
        () ->
            store.inTransaction(
                tx -> {
                  tx.createQuote(quote(r.id()));
                  return tx.saveStatusAndEvent(
                      r.id(),
                      99,
                      RequestStatus.QUOTED,
                      new EventDraft(EventType.QUOTED, start, office, ""));
                }),
        STALE_DATA);
    assertThat(store.findQuotesForRequest(r.id())).isEmpty();
  }

  @Test
  void moneyRejectsSilentRounding() {
    RequestData r = request("money");
    code(
        () ->
            store.createQuote(
                new QuoteDraft(r.id(), new BigDecimal("2.001"), start, start.plusHours(1), start)),
        INVALID_DATA);
    code(
        () ->
            store.createQuote(
                new QuoteDraft(r.id(), BigDecimal.ZERO, start, start.plusHours(1), start)),
        INVALID_DATA);
    assertThat(store.findQuotesForRequest(r.id())).isEmpty();
  }

  @Test
  void availabilityRequiresTransactionAndActualStaffLock() {
    var d = new AvailabilityDraft(staff, start, start.plusHours(8), AvailabilityKind.AVAILABLE);
    code(() -> store.createAvailability(d), TRANSACTION_REQUIRED);
    code(() -> store.inTransaction(tx -> tx.createAvailability(d)), TRANSACTION_REQUIRED);
    AvailabilityData saved =
        store.inTransaction(
            tx -> {
              tx.lockStaff(staff);
              return tx.createAvailability(d);
            });
    assertThat(store.findAvailability(staff, start.plusHours(1), start.plusHours(2)))
        .contains(saved);
    assertThat(store.findAvailability(staff, start.plusHours(8), start.plusHours(9))).isEmpty();
    AvailabilityData edited =
        store.inTransaction(
            tx -> {
              tx.lockStaff(staff);
              return tx.updateAvailability(
                  saved.id(),
                  0,
                  new AvailabilityDraft(
                      staff, start, start.plusHours(7), AvailabilityKind.AVAILABLE));
            });
    assertThat(edited.version()).isEqualTo(1);
    code(
        () ->
            store.inTransaction(
                tx -> {
                  tx.lockStaff(secondStaff);
                  return tx.updateAvailability(
                      saved.id(),
                      1,
                      new AvailabilityDraft(
                          secondStaff, start, start.plusHours(2), AvailabilityKind.AVAILABLE));
                }),
        INVALID_DATA);
  }

  @Test
  void scheduleUsesLocksUniquenessAndHalfOpenIntervals() {
    RequestData r = request("schedule");
    code(
        () -> store.createSchedule(new ScheduleDraft(r.id(), staff, start, start.plusHours(1))),
        TRANSACTION_REQUIRED);
    ScheduleData s = schedule(r.id());
    assertThat(store.findCurrentSchedule(r.id())).contains(s);
    assertThat(store.findOverlappingSchedules(staff, start.plusMinutes(30), start.plusHours(2)))
        .contains(s);
    assertThat(store.findOverlappingSchedules(staff, start.plusHours(1), start.plusHours(2)))
        .isEmpty();
    assertThat(store.findSchedulesForStaff(staff, start, start.plusDays(1))).contains(s);
    code(() -> schedule(r.id()), DUPLICATE);
    store.saveStatusAndEvent(
        r.id(),
        0,
        RequestStatus.DELIVERED,
        new EventDraft(EventType.DELIVERED, start.plusHours(1), office, "storage test"));
    assertThat(store.findOverlappingSchedules(staff, start, start.plusHours(1))).isEmpty();
    assertThat(store.findSchedulesForStaff(staff, start, start.plusDays(1))).hasSize(1);
  }

  @Test
  void scheduleUpdateIsVersionedAndMustLockBothStaff() {
    RequestData r = request("reassign");
    ScheduleData s = schedule(r.id());
    ScheduleData changed =
        store.inTransaction(
            tx -> {
              tx.lockStaff(staff);
              tx.lockStaff(secondStaff);
              tx.lockRequest(r.id());
              return tx.updateSchedule(
                  s.id(),
                  0,
                  new ScheduleDraft(r.id(), secondStaff, start.plusHours(1), start.plusHours(2)));
            });
    assertThat(changed.version()).isEqualTo(1);
    assertThat(changed.staffId()).isEqualTo(secondStaff);
    code(
        () ->
            store.inTransaction(
                tx -> {
                  tx.lockStaff(secondStaff);
                  tx.lockRequest(r.id());
                  return tx.updateSchedule(
                      s.id(), 0, new ScheduleDraft(r.id(), secondStaff, start, start.plusHours(1)));
                }),
        STALE_DATA);
  }

  @Test
  void timeProposalPreservesScheduleUntilAtomicConfirmation() {
    RequestData r = request("change");
    ScheduleData s = schedule(r.id());
    ScheduleChangeData change =
        store.createScheduleChange(
            new ScheduleChangeDraft(s.id(), s.version(), start.plusHours(2), start.plusHours(3)));
    assertThat(store.findCurrentSchedule(r.id()).orElseThrow().pickupAt().toInstant())
        .isEqualTo(start.toInstant());
    store.inTransaction(
        tx -> {
          tx.lockStaff(staff);
          tx.lockRequest(r.id());
          tx.updateScheduleChangeDecision(change.id(), 0, ChangeDecision.CONFIRMED);
          return tx.updateSchedule(
              s.id(),
              0,
              new ScheduleDraft(
                  r.id(), staff, change.proposedPickupAt(), change.proposedDeliveryAt()));
        });
    assertThat(store.findScheduleChanges(s.id()).get(0).decision())
        .isEqualTo(ChangeDecision.CONFIRMED);
    assertThat(store.findCurrentSchedule(r.id()).orElseThrow().version()).isEqualTo(1);
    code(
        () ->
            store.createScheduleChange(
                new ScheduleChangeDraft(s.id(), 0, start, start.plusHours(1))),
        STALE_DATA);
  }

  @Test
  void staleProposalCannotOverwriteNewSchedule() {
    RequestData r = request("stale-change");
    ScheduleData s = schedule(r.id());
    var change =
        store.createScheduleChange(
            new ScheduleChangeDraft(s.id(), 0, start.plusHours(2), start.plusHours(3)));
    store.inTransaction(
        tx -> {
          tx.lockStaff(staff);
          tx.lockRequest(r.id());
          return tx.updateSchedule(
              s.id(), 0, new ScheduleDraft(r.id(), staff, start.plusHours(1), start.plusHours(2)));
        });
    code(
        () ->
            store.inTransaction(
                tx -> {
                  tx.lockStaff(staff);
                  tx.lockRequest(r.id());
                  return tx.updateScheduleChangeDecision(change.id(), 0, ChangeDecision.CONFIRMED);
                }),
        STALE_DATA);
    assertThat(store.findScheduleChanges(s.id()).get(0).decision())
        .isEqualTo(ChangeDecision.PENDING);
  }

  @Test
  void eventAndStatusRollBackOnInvalidActor() {
    RequestData r = request("event");
    code(
        () ->
            store.saveStatusAndEvent(
                r.id(),
                0,
                RequestStatus.QUOTED,
                new EventDraft(EventType.QUOTED, start, 999999, "")),
        INVALID_DATA);
    assertThat(store.findEvents(r.id())).isEmpty();
    assertThat(store.findRequest(r.id()).orElseThrow().version()).isZero();
    var updated =
        store.saveStatusAndEvent(
            r.id(),
            0,
            RequestStatus.QUOTED,
            new EventDraft(EventType.QUOTED, start, office, "Created"));
    store.saveStatusAndEvent(
        r.id(),
        updated.version(),
        RequestStatus.QUOTED,
        new EventDraft(EventType.PICKUP_FAILED, start.plusHours(1), office, "storage-only test"));
    assertThat(store.findEvents(r.id())).hasSize(2);
    code(
        () ->
            store.saveStatusAndEvent(
                r.id(),
                0,
                RequestStatus.DELIVERED,
                new EventDraft(EventType.DELIVERED, start, office, "")),
        STALE_DATA);
  }

  @Test
  void lockOrderIsEnforced() {
    RequestData r = request("locks");
    code(
        () ->
            store.inTransaction(
                tx -> {
                  tx.lockRequest(r.id());
                  tx.lockStaff(staff);
                  return null;
                }),
        INVALID_DATA);
    code(
        () ->
            store.inTransaction(
                tx -> {
                  tx.lockStaff(secondStaff);
                  tx.lockStaff(staff);
                  return null;
                }),
        INVALID_DATA);
  }

  @Test
  void concurrentBookingPatternSerializesCheckAndWrite() throws Exception {
    // Integration example for C: the repository provides locks, C must perform the overlap check.
    long first = request("parallel-1").id(), second = request("parallel-2").id();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (long id : List.of(first, second)) {
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!go.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timeout");
                  }
                  return store.inTransaction(
                      tx -> {
                        tx.lockStaff(staff);
                        tx.lockRequest(id);
                        if (!tx.findOverlappingSchedules(staff, start, start.plusHours(1))
                            .isEmpty()) {
                          return false;
                        }
                        tx.createSchedule(new ScheduleDraft(id, staff, start, start.plusHours(1)));
                        return true;
                      });
                }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      int successful = 0;
      for (var result : results) {
        if (result.get(10, TimeUnit.SECONDS)) {
          successful++;
        }
      }
      assertThat(successful).isEqualTo(1);
      assertThat(store.findOverlappingSchedules(staff, start, start.plusHours(1))).hasSize(1);
    } finally {
      go.countDown();
      pool.shutdownNow();
    }
  }
}
