package edu.delivery.scheduling;

import static edu.delivery.contract.DeliveryData.*;

import edu.delivery.auth.AccessGuard;
import edu.delivery.contract.DeliveryStore;
import edu.delivery.contract.StoreException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SchedulingService {
  private final DeliveryStore store;
  private final AccessGuard access;
  private final Clock clock;

  public SchedulingService(DeliveryStore store, AccessGuard access, Clock clock) {
    this.store = store;
    this.access = access;
    this.clock = clock;
  }

  public List<StaffData> staff() {
    access.requireRole(Role.OFFICE_STAFF);
    return store.findActiveStaff().stream().filter(StaffData::active).toList();
  }

  public List<AssignmentCandidate> acceptedRequestsToSchedule() {
    access.requireRole(Role.OFFICE_STAFF);
    List<AssignmentCandidate> candidates = new ArrayList<>();
    for (RequestData request : requestsByStatus(RequestStatus.ACCEPTED)) {
      if (store.findCurrentSchedule(request.id()).isPresent()) continue;
      acceptedQuote(request.id()).ifPresent(q -> candidates.add(new AssignmentCandidate(request, q)));
    }
    return candidates;
  }

  public List<ScheduleCard> schedules() {
    access.requireRole(Role.OFFICE_STAFF);
    OffsetDateTime from = OffsetDateTime.now(clock).minusDays(30);
    OffsetDateTime to = OffsetDateTime.now(clock).plusYears(3);
    Map<Long, ScheduleData> records = new LinkedHashMap<>();
    for (StaffData person : store.findActiveStaff()) {
      store.findSchedulesForStaff(person.id(), from, to).forEach(s -> records.put(s.id(), s));
    }
    return records.values().stream().map(s -> card(store, s)).toList();
  }

  public ScheduleData assign(long requestId, long staffId) {
    access.requireRole(Role.OFFICE_STAFF);
    return store.inTransaction(tx -> {
      tx.lockStaff(staffId);
      tx.lockRequest(requestId);
      RequestData request = tx.findRequest(requestId)
          .orElseThrow(() -> notFound("Request not found"));
      requireAcceptedRequest(tx, request);
      if (tx.findCurrentSchedule(requestId).isPresent()) {
        throw new IllegalArgumentException("This request already has a schedule. Reload the current assignment.");
      }
      QuoteData quote = acceptedQuote(tx, requestId);
      requireActiveStaff(tx, staffId);
      validateWorkingPeriod(tx, staffId, quote.pickupAt(), quote.deliveryAt());
      if (!tx.findOverlappingSchedules(staffId, quote.pickupAt(), quote.deliveryAt()).isEmpty()) {
        throw new IllegalArgumentException("This delivery staff member already has an overlapping task.");
      }
      return tx.createSchedule(new ScheduleDraft(requestId, staffId, quote.pickupAt(), quote.deliveryAt()));
    });
  }

  public ScheduleData reassign(long requestId, long newStaffId) {
    access.requireRole(Role.OFFICE_STAFF);
    ScheduleData initial = store.findCurrentSchedule(requestId)
        .orElseThrow(() -> notFound("Schedule not found"));
    return store.inTransaction(tx -> {
      lockStaffInOrder(tx, initial.staffId(), newStaffId);
      tx.lockRequest(requestId);
      ScheduleData current = tx.findCurrentSchedule(requestId)
          .orElseThrow(() -> notFound("Schedule not found"));
      if (current.id() != initial.id() || current.version() != initial.version()
          || current.staffId() != initial.staffId()) {
        throw stale("Schedule changed; reload before reassigning");
      }
      if (current.staffId() == newStaffId) throw new IllegalArgumentException("This staff member is already assigned.");
      RequestData request = tx.findRequest(requestId)
          .orElseThrow(() -> notFound("Request not found"));
      if (request.status() != RequestStatus.ACCEPTED) {
        throw new IllegalArgumentException("Only an accepted delivery that has not started can be reassigned.");
      }
      requireActiveStaff(tx, newStaffId);
      validateWorkingPeriod(tx, newStaffId, current.pickupAt(), current.deliveryAt());
      boolean conflict = tx.findOverlappingSchedules(newStaffId, current.pickupAt(), current.deliveryAt())
          .stream().anyMatch(s -> s.id() != current.id());
      if (conflict) throw new IllegalArgumentException("The selected delivery staff member has an overlapping task.");
      return tx.updateSchedule(current.id(), current.version(),
          new ScheduleDraft(requestId, newStaffId, current.pickupAt(), current.deliveryAt()));
    });
  }

  public ScheduleChangeData proposeTimeChange(long requestId, long expectedScheduleVersion,
      OffsetDateTime pickupAt, OffsetDateTime deliveryAt) {
    access.requireRole(Role.OFFICE_STAFF);
    requireRange(pickupAt, deliveryAt);
    ScheduleData initial = store.findCurrentSchedule(requestId)
        .orElseThrow(() -> notFound("Schedule not found"));
    return store.inTransaction(tx -> {
      tx.lockStaff(initial.staffId());
      tx.lockRequest(requestId);
      ScheduleData current = tx.findCurrentSchedule(requestId)
          .orElseThrow(() -> notFound("Schedule not found"));
      if (current.version() != expectedScheduleVersion || current.version() != initial.version()) {
        throw stale("Schedule changed; reload before proposing a time change");
      }
      RequestData request = tx.findRequest(requestId)
          .orElseThrow(() -> notFound("Request not found"));
      if (request.status() != RequestStatus.ACCEPTED) {
        throw new IllegalArgumentException("Time changes are unavailable after delivery execution begins.");
      }
      validateWorkingPeriod(tx, current.staffId(), pickupAt, deliveryAt);
      boolean conflict = tx.findOverlappingSchedules(current.staffId(), pickupAt, deliveryAt)
          .stream().anyMatch(s -> s.id() != current.id());
      if (conflict) throw new IllegalArgumentException("The proposed time overlaps another delivery.");
      return tx.createScheduleChange(new ScheduleChangeDraft(current.id(), current.version(), pickupAt, deliveryAt));
    });
  }

  public List<ChangeCard> pendingChangesForOffice() {
    access.requireRole(Role.OFFICE_STAFF);
    List<ChangeCard> result = new ArrayList<>();
    for (ScheduleCard card : schedules()) {
      for (ScheduleChangeData change : store.findScheduleChanges(card.schedule().id())) {
        if (change.decision() == ChangeDecision.PENDING) result.add(new ChangeCard(card, change));
      }
    }
    return result;
  }

  public List<ChangeCard> pendingChangesForCustomer() {
    access.requireRole(Role.CUSTOMER);
    long customerId = access.current().userId();
    List<ChangeCard> result = new ArrayList<>();
    for (RequestData request : requestsForCustomer(customerId)) {
      store.findCurrentSchedule(request.id()).ifPresent(schedule -> {
        for (ScheduleChangeData change : store.findScheduleChanges(schedule.id())) {
          if (change.decision() == ChangeDecision.PENDING) {
            result.add(new ChangeCard(card(store, schedule), change));
          }
        }
      });
    }
    return result;
  }

  public DecisionResult decideTimeChange(long requestId, long changeId, long expectedVersion,
      boolean accept) {
    access.requireRole(Role.CUSTOMER);
    RequestData initialRequest = store.findRequest(requestId)
        .orElseThrow(() -> notFound("Request not found"));
    access.requireCustomer(initialRequest.customerId());
    ScheduleData initialSchedule = store.findCurrentSchedule(requestId)
        .orElseThrow(() -> notFound("Schedule not found"));
    return store.inTransaction(tx -> {
      tx.lockStaff(initialSchedule.staffId());
      tx.lockRequest(requestId);
      RequestData request = tx.findRequest(requestId)
          .orElseThrow(() -> notFound("Request not found"));
      access.requireCustomer(request.customerId());
      ScheduleData current = tx.findCurrentSchedule(requestId)
          .orElseThrow(() -> notFound("Schedule not found"));
      if (current.id() != initialSchedule.id() || current.staffId() != initialSchedule.staffId()) {
        throw stale("Schedule changed; reload before deciding");
      }
      ScheduleChangeData change = tx.findScheduleChanges(current.id()).stream()
          .filter(c -> c.id() == changeId).findFirst()
          .orElseThrow(() -> notFound("Schedule change not found"));
      if (change.version() != expectedVersion || change.decision() != ChangeDecision.PENDING) {
        throw stale("This proposal has already been handled. Reload the page.");
      }
      if (current.version() != change.baseScheduleVersion()) {
        tx.updateScheduleChangeDecision(change.id(), change.version(), ChangeDecision.DECLINED);
        return DecisionResult.STALE;
      }
      if (!accept) {
        tx.updateScheduleChangeDecision(change.id(), change.version(), ChangeDecision.DECLINED);
        return DecisionResult.DECLINED;
      }
      if (request.status() != RequestStatus.ACCEPTED) {
        throw new IllegalArgumentException("This delivery has already started and cannot be rescheduled.");
      }
      validateWorkingPeriod(tx, current.staffId(), change.proposedPickupAt(), change.proposedDeliveryAt());
      boolean conflict = tx.findOverlappingSchedules(current.staffId(),
              change.proposedPickupAt(), change.proposedDeliveryAt())
          .stream().anyMatch(s -> s.id() != current.id());
      if (conflict) throw new IllegalArgumentException("The proposed time is no longer available. Contact the office.");
      tx.updateScheduleChangeDecision(change.id(), change.version(), ChangeDecision.CONFIRMED);
      tx.updateSchedule(current.id(), current.version(), new ScheduleDraft(requestId, current.staffId(),
          change.proposedPickupAt(), change.proposedDeliveryAt()));
      return DecisionResult.CONFIRMED;
    });
  }

  public List<ScheduleCard> schedulesForCustomer() {
    access.requireRole(Role.CUSTOMER);
    List<ScheduleCard> result = new ArrayList<>();
    for (RequestData request : requestsForCustomer(access.current().userId())) {
      store.findCurrentSchedule(request.id()).ifPresent(s -> result.add(card(store, s)));
    }
    return result;
  }

  public ScheduleCard cardForRequest(long requestId) {
    access.requireRequestRead(requestId);
    ScheduleData schedule = store.findCurrentSchedule(requestId)
        .orElseThrow(() -> notFound("Schedule not found"));
    return card(store, schedule);
  }

  private List<RequestData> requestsByStatus(RequestStatus status) {
    List<RequestData> result = new ArrayList<>();
    for (int offset = 0; ; offset += 100) {
      List<RequestData> page = store.findRequests(new RequestFilter(null, status, offset, 100));
      result.addAll(page);
      if (page.size() < 100) return result;
    }
  }

  private List<RequestData> requestsForCustomer(long customerId) {
    List<RequestData> result = new ArrayList<>();
    for (int offset = 0; ; offset += 100) {
      List<RequestData> page = store.findRequests(new RequestFilter(customerId, null, offset, 100));
      result.addAll(page);
      if (page.size() < 100) return result;
    }
  }

  private java.util.Optional<QuoteData> acceptedQuote(long requestId) {
    return store.findQuotesForRequest(requestId).stream()
        .filter(q -> q.status() == QuoteStatus.ACCEPTED)
        .max(Comparator.comparingLong(QuoteData::id));
  }

  private QuoteData acceptedQuote(DeliveryStore tx, long requestId) {
    return tx.findQuotesForRequest(requestId).stream()
        .filter(q -> q.status() == QuoteStatus.ACCEPTED)
        .max(Comparator.comparingLong(QuoteData::id))
        .orElseThrow(() -> new IllegalArgumentException("This request has no accepted quote."));
  }

  private void requireAcceptedRequest(DeliveryStore tx, RequestData request) {
    if (request.status() != RequestStatus.ACCEPTED) {
      throw new IllegalArgumentException("Only a customer-accepted request can be scheduled.");
    }
    acceptedQuote(tx, request.id());
  }

  private void validateWorkingPeriod(DeliveryStore tx, long staffId, OffsetDateTime from, OffsetDateTime to) {
    requireRange(from, to);
    if (!from.isAfter(OffsetDateTime.now(clock))) {
      throw new IllegalArgumentException("Pickup time must be in the future.");
    }
    List<AvailabilityData> periods = tx.findAvailability(staffId, from, to);
    boolean covered = periods.stream().anyMatch(a -> a.kind() == AvailabilityKind.AVAILABLE
        && !a.startAt().isAfter(from) && !a.endAt().isBefore(to));
    if (!covered) throw new IllegalArgumentException("No available period covers the entire delivery time.");
    if (periods.stream().anyMatch(a -> a.kind() == AvailabilityKind.UNAVAILABLE
        && a.startAt().isBefore(to) && a.endAt().isAfter(from))) {
      throw new IllegalArgumentException("This time overlaps an unavailable period.");
    }
  }

  private void requireActiveStaff(DeliveryStore tx, long staffId) {
    boolean active = tx.findActiveStaff().stream().anyMatch(s -> s.id() == staffId && s.active());
    if (!active) throw new IllegalArgumentException("The selected delivery staff member is not active.");
  }

  private ScheduleCard card(DeliveryStore tx, ScheduleData schedule) {
    RequestData request = tx.findRequest(schedule.requestId())
        .orElseThrow(() -> notFound("Request not found"));
    String staffName = tx.findActiveStaff().stream().filter(s -> s.id() == schedule.staffId())
        .map(StaffData::displayName).findFirst().orElse("Inactive delivery staff");
    return new ScheduleCard(schedule, request, staffName);
  }

  private static void lockStaffInOrder(DeliveryStore tx, long first, long second) {
    if (first == second) tx.lockStaff(first);
    else {
      tx.lockStaff(Math.min(first, second));
      tx.lockStaff(Math.max(first, second));
    }
  }

  private static void requireRange(OffsetDateTime from, OffsetDateTime to) {
    if (from == null || to == null || !from.isBefore(to)) {
      throw new IllegalArgumentException("Delivery time must end after it starts.");
    }
  }

  private static StoreException notFound(String message) {
    return new StoreException(StoreException.Code.NOT_FOUND, message);
  }

  private static StoreException stale(String message) {
    return new StoreException(StoreException.Code.STALE_DATA, message);
  }

  public record AssignmentCandidate(RequestData request, QuoteData quote) {}
  public record ScheduleCard(ScheduleData schedule, RequestData request, String staffName) {}
  public record ChangeCard(ScheduleCard card, ScheduleChangeData change) {}
  public enum DecisionResult { CONFIRMED, DECLINED, STALE }
}
