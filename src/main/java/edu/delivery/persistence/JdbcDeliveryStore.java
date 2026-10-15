package edu.delivery.persistence;

import static edu.delivery.contract.DeliveryData.*;
import static edu.delivery.contract.StoreException.Code.*;

import edu.delivery.contract.DeliveryStore;
import edu.delivery.contract.StoreException;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** All SQL and atomic storage operations. Domain rules remain in B/C services. */
@Repository
public class JdbcDeliveryStore implements DeliveryStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final ThreadLocal<Locks> heldLocks = new ThreadLocal<>();
  private static final String REQUEST_SELECT =
      """
      SELECT r.*, p.description, p.handling_notes
      FROM delivery_requests r JOIN parcels p ON p.request_id=r.id
      """;

  public JdbcDeliveryStore(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
    this.clock = clock;
  }

  @Override
  public <T> T inTransaction(Function<DeliveryStore, T> work) {
    Objects.requireNonNull(work, "work");
    return access(
        () ->
            transactions.execute(
                status -> {
                  boolean outer = heldLocks.get() == null;
                  if (outer) {
                    heldLocks.set(new Locks());
                  }
                  try {
                    return work.apply(this);
                  } finally {
                    if (outer) {
                      heldLocks.remove();
                    }
                  }
                }));
  }

  @Override
  public void lockRequest(long requestId) {
    access(
        () -> {
          Locks locks = locks();
          if (!locks.requests.contains(requestId)) {
            if (!locks.requests.isEmpty() && requestId < Collections.max(locks.requests)) {
              throw problem(INVALID_DATA, "Lock requests in ascending ID order");
            }
            requireRow("SELECT id FROM delivery_requests WHERE id=? FOR UPDATE", requestId);
            locks.requests.add(requestId);
          }
          return null;
        });
  }

  @Override
  public void lockStaff(long staffId) {
    access(
        () -> {
          Locks locks = locks();
          if (!locks.staff.contains(staffId)) {
            if (!locks.requests.isEmpty()
                || (!locks.staff.isEmpty() && staffId < Collections.max(locks.staff))) {
              throw problem(INVALID_DATA, "Lock staff in ascending order before locking requests");
            }
            requireRow("SELECT id FROM delivery_staff WHERE id=? FOR UPDATE", staffId);
            locks.staff.add(staffId);
          }
          return null;
        });
  }

  @Override
  public Optional<AccountData> findAccountByUsername(String username) {
    text(username, 80);
    return one(
        "SELECT * FROM users WHERE username=?",
        (r, n) ->
            new AccountData(
                r.getLong("id"),
                r.getString("username"),
                r.getString("password_hash"),
                Role.valueOf(r.getString("role")),
                r.getString("display_name"),
                r.getBoolean("active")),
        username);
  }

  @Override
  public RequestData createRequest(RequestDraft draft) {
    required(draft);
    text(draft.submissionKey(), 100);
    validateDetails(draft.details());
    return inTransaction(
        store -> {
          var account =
              one(
                      "SELECT * FROM users WHERE id=? FOR UPDATE",
                      (r, n) ->
                          List.of(r.getString("role"), Boolean.toString(r.getBoolean("active"))),
                      draft.customerId())
                  .orElseThrow(() -> problem(NOT_FOUND, "Customer not found"));
          if (!account.equals(List.of("CUSTOMER", "true"))) {
            throw problem(INVALID_DATA, "Request owner must be an active customer");
          }
          String hash = fingerprint(draft.details());
          var original =
              one(
                  "SELECT id, original_payload FROM delivery_requests WHERE customer_id=? AND"
                      + " submission_key=?",
                  (r, n) -> Map.entry(r.getLong("id"), r.getString("original_payload")),
                  draft.customerId(),
                  draft.submissionKey());
          if (original.isPresent()) {
            if (!original.get().getValue().equals(hash)) {
              throw problem(CONFLICT, "Submission key was already used with different details");
            }
            return request(original.get().getKey());
          }
          RequestDetails d = draft.details();
          long id =
              insert(
                  """
                  INSERT INTO delivery_requests(customer_id,submission_key,original_payload,customer_name,
                  customer_phone,pickup_address,delivery_address,recipient_name,recipient_phone,status,created_at)
                  VALUES (?,?,?,?,?,?,?,?,?,'REQUESTED',?)
                  """,
                  draft.customerId(),
                  draft.submissionKey(),
                  hash,
                  d.customerName(),
                  d.customerPhone(),
                  d.pickupAddress(),
                  d.deliveryAddress(),
                  d.recipientName(),
                  d.recipientPhone(),
                  now());
          jdbc.update(
              "INSERT INTO parcels(request_id,description,handling_notes) VALUES (?,?,?)",
              id,
              d.parcelDescription(),
              note(d.handlingNotes()));
          return request(id);
        });
  }

  @Override
  public Optional<RequestData> findRequest(long requestId) {
    return one(REQUEST_SELECT + " WHERE r.id=?", REQUEST_MAPPER, requestId);
  }

  @Override
  public List<RequestData> findRequests(RequestFilter filter) {
    required(filter);
    if (filter.offset() < 0 || filter.limit() < 1 || filter.limit() > 100) {
      throw problem(INVALID_DATA, "Invalid pagination");
    }
    StringBuilder sql = new StringBuilder(REQUEST_SELECT + " WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (filter.customerId() != null) {
      sql.append(" AND r.customer_id=?");
      args.add(filter.customerId());
    }
    if (filter.status() != null) {
      sql.append(" AND r.status=?");
      args.add(filter.status().name());
    }
    sql.append(" ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?");
    args.add(filter.limit());
    args.add(filter.offset());
    return list(sql.toString(), REQUEST_MAPPER, args.toArray());
  }

  @Override
  public RequestData updateRequestDetails(long id, long version, RequestDetails d) {
    validateDetails(d);
    return inTransaction(
        store -> {
          int changed =
              jdbc.update(
                  """
                  UPDATE delivery_requests SET customer_name=?,customer_phone=?,pickup_address=?,delivery_address=?,
                  recipient_name=?,recipient_phone=?,version=version+1 WHERE id=? AND version=?
                  """,
                  d.customerName(),
                  d.customerPhone(),
                  d.pickupAddress(),
                  d.deliveryAddress(),
                  d.recipientName(),
                  d.recipientPhone(),
                  id,
                  version);
          updated(changed, "delivery_requests", id);
          int parcels =
              jdbc.update(
                  "UPDATE parcels SET description=?,handling_notes=? WHERE request_id=?",
                  d.parcelDescription(),
                  note(d.handlingNotes()),
                  id);
          if (parcels != 1) {
            throw problem(STORAGE_ERROR, "Missing request parcel");
          }
          return request(id);
        });
  }

  @Override
  public QuoteData createQuote(QuoteDraft d) {
    required(d);
    required(d.amount());
    period(d.pickupAt(), d.deliveryAt());
    required(d.expiresAt());
    if (d.amount().signum() <= 0
        || d.amount().compareTo(new java.math.BigDecimal("9999999999.99")) > 0) {
      throw problem(INVALID_DATA, "Quote amount out of range");
    }
    try {
      d.amount().setScale(2, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw problem(INVALID_DATA, "Amount must have at most two decimal places");
    }
    return inTransaction(
        store -> {
          long id =
              insert(
                  """
                  INSERT INTO delivery_quotes(request_id,amount,pickup_at,delivery_at,expires_at,status)
                  VALUES (?,?,?,?,?,'ISSUED')
                  """,
                  d.requestId(),
                  d.amount(),
                  d.pickupAt(),
                  d.deliveryAt(),
                  d.expiresAt());
          return quote(id);
        });
  }

  @Override
  public Optional<QuoteData> findQuote(long id) {
    return one("SELECT * FROM delivery_quotes WHERE id=?", QUOTE_MAPPER, id);
  }

  @Override
  public List<QuoteData> findQuotesForRequest(long id) {
    return list(
        "SELECT * FROM delivery_quotes WHERE request_id=? ORDER BY id DESC", QUOTE_MAPPER, id);
  }

  @Override
  public QuoteData updateQuoteStatus(long id, long version, QuoteStatus status) {
    required(status);
    return inTransaction(
        store -> {
          updated(
              jdbc.update(
                  "UPDATE delivery_quotes SET status=?,version=version+1 WHERE id=? AND version=?",
                  status.name(),
                  id,
                  version),
              "delivery_quotes",
              id);
          return quote(id);
        });
  }

  @Override
  public List<StaffData> findActiveStaff() {
    return list(
        """
        SELECT s.id,s.user_id,u.display_name,s.active FROM delivery_staff s
        JOIN users u ON u.id=s.user_id WHERE s.active=TRUE AND u.active=TRUE ORDER BY s.id
        """,
        (r, n) ->
            new StaffData(
                r.getLong("id"),
                r.getLong("user_id"),
                r.getString("display_name"),
                r.getBoolean("active")));
  }

  @Override
  public List<AvailabilityData> findAvailability(
      long staffId, OffsetDateTime from, OffsetDateTime to) {
    period(from, to);
    return list(
        "SELECT * FROM staff_availability WHERE staff_id=? AND start_at<? AND end_at>? ORDER BY"
            + " start_at,id",
        AVAILABILITY_MAPPER,
        staffId,
        to,
        from);
  }

  @Override
  public AvailabilityData createAvailability(AvailabilityDraft d) {
    required(d);
    period(d.startAt(), d.endAt());
    required(d.kind());
    return access(
        () -> {
          staffLocked(d.staffId());
          long id =
              insert(
                  "INSERT INTO staff_availability(staff_id,start_at,end_at,kind) VALUES (?,?,?,?)",
                  d.staffId(),
                  d.startAt(),
                  d.endAt(),
                  d.kind().name());
          return availability(id);
        });
  }

  @Override
  public AvailabilityData updateAvailability(long id, long version, AvailabilityDraft d) {
    required(d);
    period(d.startAt(), d.endAt());
    required(d.kind());
    return access(
        () -> {
          staffLocked(d.staffId());
          if (availability(id).staffId() != d.staffId()) {
            throw problem(INVALID_DATA, "Availability staff is immutable");
          }
          updated(
              jdbc.update(
                  "UPDATE staff_availability SET start_at=?,end_at=?,kind=?,version=version+1 WHERE"
                      + " id=? AND version=?",
                  d.startAt(),
                  d.endAt(),
                  d.kind().name(),
                  id,
                  version),
              "staff_availability",
              id);
          return availability(id);
        });
  }

  @Override
  public Optional<ScheduleData> findCurrentSchedule(long requestId) {
    return one("SELECT * FROM delivery_schedules WHERE request_id=?", SCHEDULE_MAPPER, requestId);
  }

  @Override
  public List<ScheduleData> findSchedulesForStaff(
      long staffId, OffsetDateTime from, OffsetDateTime to) {
    period(from, to);
    return list(
        "SELECT * FROM delivery_schedules WHERE staff_id=? AND pickup_at>=? AND pickup_at<? ORDER"
            + " BY pickup_at,id",
        SCHEDULE_MAPPER,
        staffId,
        from,
        to);
  }

  @Override
  public List<ScheduleData> findOverlappingSchedules(
      long staffId, OffsetDateTime from, OffsetDateTime to) {
    period(from, to);
    return list(
        """
        SELECT s.* FROM delivery_schedules s JOIN delivery_requests r ON r.id=s.request_id
        WHERE s.staff_id=? AND s.pickup_at<? AND s.delivery_at>? AND r.status NOT IN ('DELIVERED','CANCELLED')
        ORDER BY s.pickup_at,s.id
        """,
        SCHEDULE_MAPPER,
        staffId,
        to,
        from);
  }

  @Override
  public ScheduleData createSchedule(ScheduleDraft d) {
    required(d);
    period(d.pickupAt(), d.deliveryAt());
    return access(
        () -> {
          staffLocked(d.staffId());
          requestLocked(d.requestId());
          long id =
              insert(
                  "INSERT INTO delivery_schedules(request_id,staff_id,pickup_at,delivery_at) VALUES"
                      + " (?,?,?,?)",
                  d.requestId(),
                  d.staffId(),
                  d.pickupAt(),
                  d.deliveryAt());
          return schedule(id);
        });
  }

  @Override
  public ScheduleData updateSchedule(long id, long version, ScheduleDraft d) {
    required(d);
    period(d.pickupAt(), d.deliveryAt());
    return access(
        () -> {
          ScheduleData old = schedule(id);
          staffLocked(old.staffId());
          staffLocked(d.staffId());
          requestLocked(old.requestId());
          if (old.requestId() != d.requestId()) {
            throw problem(INVALID_DATA, "Schedule request is immutable");
          }
          updated(
              jdbc.update(
                  "UPDATE delivery_schedules SET"
                      + " staff_id=?,pickup_at=?,delivery_at=?,version=version+1 WHERE id=? AND"
                      + " version=?",
                  d.staffId(),
                  d.pickupAt(),
                  d.deliveryAt(),
                  id,
                  version),
              "delivery_schedules",
              id);
          return schedule(id);
        });
  }

  @Override
  public ScheduleChangeData createScheduleChange(ScheduleChangeDraft d) {
    required(d);
    period(d.proposedPickupAt(), d.proposedDeliveryAt());
    return inTransaction(
        store -> {
          ScheduleData schedule = schedule(d.scheduleId());
          if (schedule.version() != d.baseScheduleVersion()) {
            throw problem(STALE_DATA, "Schedule has changed");
          }
          long id =
              insert(
                  """
                  INSERT INTO schedule_changes(schedule_id,base_schedule_version,proposed_pickup_at,
                  proposed_delivery_at,decision,created_at) VALUES (?,?,?,?,'PENDING',?)
                  """,
                  d.scheduleId(),
                  d.baseScheduleVersion(),
                  d.proposedPickupAt(),
                  d.proposedDeliveryAt(),
                  now());
          return change(id);
        });
  }

  @Override
  public List<ScheduleChangeData> findScheduleChanges(long scheduleId) {
    return list(
        "SELECT * FROM schedule_changes WHERE schedule_id=? ORDER BY id DESC",
        CHANGE_MAPPER,
        scheduleId);
  }

  @Override
  public ScheduleChangeData updateScheduleChangeDecision(
      long id, long version, ChangeDecision decision) {
    required(decision);
    return inTransaction(
        store -> {
          ScheduleChangeData change = change(id);
          if (decision == ChangeDecision.CONFIRMED) {
            ScheduleData schedule = schedule(change.scheduleId());
            staffLocked(schedule.staffId());
            requestLocked(schedule.requestId());
            if (schedule.version() != change.baseScheduleVersion()) {
              throw problem(STALE_DATA, "Schedule proposal is stale");
            }
          }
          updated(
              jdbc.update(
                  "UPDATE schedule_changes SET decision=?,version=version+1 WHERE id=? AND"
                      + " version=?",
                  decision.name(),
                  id,
                  version),
              "schedule_changes",
              id);
          return change(id);
        });
  }

  @Override
  public RequestData saveStatusAndEvent(
      long id, long version, RequestStatus status, EventDraft event) {
    required(status);
    required(event);
    required(event.type());
    required(event.occurredAt());
    note(event.note());
    return inTransaction(
        store -> {
          updated(
              jdbc.update(
                  "UPDATE delivery_requests SET status=?,version=version+1 WHERE id=? AND"
                      + " version=?",
                  status.name(),
                  id,
                  version),
              "delivery_requests",
              id);
          jdbc.update(
              "INSERT INTO delivery_events(request_id,type,occurred_at,actor_user_id,note) VALUES"
                  + " (?,?,?,?,?)",
              id,
              event.type().name(),
              event.occurredAt(),
              event.actorUserId(),
              note(event.note()));
          return request(id);
        });
  }

  @Override
  public List<EventData> findEvents(long requestId) {
    return list(
        "SELECT * FROM delivery_events WHERE request_id=? ORDER BY occurred_at,id",
        (r, n) ->
            new EventData(
                r.getLong("id"),
                r.getLong("request_id"),
                EventType.valueOf(r.getString("type")),
                time(r, "occurred_at"),
                r.getLong("actor_user_id"),
                r.getString("note")),
        requestId);
  }

  private RequestData request(long id) {
    return findRequest(id).orElseThrow(() -> problem(NOT_FOUND, "Request not found"));
  }

  private QuoteData quote(long id) {
    return findQuote(id).orElseThrow(() -> problem(NOT_FOUND, "Quote not found"));
  }

  private AvailabilityData availability(long id) {
    return one("SELECT * FROM staff_availability WHERE id=?", AVAILABILITY_MAPPER, id)
        .orElseThrow(() -> problem(NOT_FOUND, "Availability not found"));
  }

  private ScheduleData schedule(long id) {
    return one("SELECT * FROM delivery_schedules WHERE id=?", SCHEDULE_MAPPER, id)
        .orElseThrow(() -> problem(NOT_FOUND, "Schedule not found"));
  }

  private ScheduleChangeData change(long id) {
    return one("SELECT * FROM schedule_changes WHERE id=?", CHANGE_MAPPER, id)
        .orElseThrow(() -> problem(NOT_FOUND, "Schedule change not found"));
  }

  private void updated(int count, String table, long id) {
    if (count == 1) {
      return;
    }
    if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id=?", Integer.class, id)
        == 0) {
      throw problem(NOT_FOUND, "Record not found");
    }
    throw problem(STALE_DATA, "Record was changed; reload and retry");
  }

  private void requireRow(String sql, long id) {
    if (jdbc.query(sql, (r, n) -> r.getLong(1), id).isEmpty()) {
      throw problem(NOT_FOUND, "Record not found");
    }
  }

  private long insert(String sql, Object... args) {
    // SQL statements are fixed internal strings; values are always bound parameters.
    var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var statement = connection.prepareStatement(sql, new String[] {"id"});
          for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
          }
          return statement;
        },
        keys);
    Number key = keys.getKey();
    if (key == null) {
      throw problem(STORAGE_ERROR, "Database did not return an ID");
    }
    return key.longValue();
  }

  private <T> List<T> list(String sql, RowMapper<T> mapper, Object... args) {
    return access(() -> List.copyOf(jdbc.query(sql, mapper, args)));
  }

  private <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... args) {
    List<T> rows = list(sql, mapper, args);
    if (rows.size() > 1) {
      throw problem(STORAGE_ERROR, "Expected a unique record");
    }
    return rows.stream().findFirst();
  }

  private <T> T access(Supplier<T> action) {
    try {
      return action.get();
    } catch (StoreException e) {
      throw e;
    } catch (DuplicateKeyException e) {
      throw new StoreException(DUPLICATE, "Duplicate record", e);
    } catch (DataIntegrityViolationException e) {
      throw new StoreException(INVALID_DATA, "Invalid data or related record", e);
    } catch (PessimisticLockingFailureException e) {
      throw new StoreException(CONFLICT, "Record is busy; retry the operation", e);
    } catch (org.springframework.transaction.TransactionException e) {
      throw new StoreException(CONFLICT, "Transaction could not complete", e);
    } catch (DataAccessException e) {
      throw new StoreException(STORAGE_ERROR, "Database operation failed", e);
    }
  }

  private Locks locks() {
    Locks locks = heldLocks.get();
    if (locks == null) {
      throw problem(TRANSACTION_REQUIRED, "Use DeliveryStore.inTransaction for this operation");
    }
    return locks;
  }

  private void staffLocked(long id) {
    if (!locks().staff.contains(id)) {
      throw problem(TRANSACTION_REQUIRED, "Lock affected staff first");
    }
  }

  private void requestLocked(long id) {
    if (!locks().requests.contains(id)) {
      throw problem(TRANSACTION_REQUIRED, "Lock affected request first");
    }
  }

  private static class Locks {
    final Set<Long> staff = new HashSet<>();
    final Set<Long> requests = new HashSet<>();
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }

  private static StoreException problem(StoreException.Code code, String message) {
    return new StoreException(code, message);
  }

  private static void required(Object value) {
    if (value == null) {
      throw problem(INVALID_DATA, "Required value is missing");
    }
  }

  private static void text(String value, int max) {
    if (value == null || value.isBlank() || value.length() > max) {
      throw problem(INVALID_DATA, "Required text is empty or too long");
    }
  }

  private static String note(String note) {
    if (note == null) {
      return "";
    }
    if (note.length() > 2000) {
      throw problem(INVALID_DATA, "Note is too long");
    }
    return note;
  }

  private static void period(OffsetDateTime from, OffsetDateTime to) {
    required(from);
    required(to);
    if (!from.isBefore(to)) {
      throw problem(INVALID_DATA, "Start must precede end");
    }
  }

  private static void validateDetails(RequestDetails d) {
    required(d);
    text(d.customerName(), 120);
    text(d.customerPhone(), 40);
    text(d.pickupAddress(), 500);
    text(d.deliveryAddress(), 500);
    text(d.recipientName(), 120);
    text(d.recipientPhone(), 40);
    text(d.parcelDescription(), 1000);
    note(d.handlingNotes());
  }

  private static String fingerprint(RequestDetails d) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (String value :
          List.of(
              d.customerName(),
              d.customerPhone(),
              d.pickupAddress(),
              d.deliveryAddress(),
              d.recipientName(),
              d.recipientPhone(),
              d.parcelDescription(),
              note(d.handlingNotes()))) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static OffsetDateTime time(ResultSet r, String field) throws SQLException {
    return r.getObject(field, OffsetDateTime.class);
  }

  private static final RowMapper<RequestData> REQUEST_MAPPER =
      (r, n) ->
          new RequestData(
              r.getLong("id"),
              r.getLong("customer_id"),
              new RequestDetails(
                  r.getString("customer_name"),
                  r.getString("customer_phone"),
                  r.getString("pickup_address"),
                  r.getString("delivery_address"),
                  r.getString("recipient_name"),
                  r.getString("recipient_phone"),
                  r.getString("description"),
                  r.getString("handling_notes")),
              RequestStatus.valueOf(r.getString("status")),
              time(r, "created_at"),
              r.getLong("version"));
  private static final RowMapper<QuoteData> QUOTE_MAPPER =
      (r, n) ->
          new QuoteData(
              r.getLong("id"),
              r.getLong("request_id"),
              r.getBigDecimal("amount"),
              time(r, "pickup_at"),
              time(r, "delivery_at"),
              time(r, "expires_at"),
              QuoteStatus.valueOf(r.getString("status")),
              r.getLong("version"));
  private static final RowMapper<AvailabilityData> AVAILABILITY_MAPPER =
      (r, n) ->
          new AvailabilityData(
              r.getLong("id"),
              r.getLong("staff_id"),
              time(r, "start_at"),
              time(r, "end_at"),
              AvailabilityKind.valueOf(r.getString("kind")),
              r.getLong("version"));
  private static final RowMapper<ScheduleData> SCHEDULE_MAPPER =
      (r, n) ->
          new ScheduleData(
              r.getLong("id"),
              r.getLong("request_id"),
              r.getLong("staff_id"),
              time(r, "pickup_at"),
              time(r, "delivery_at"),
              r.getLong("version"));
  private static final RowMapper<ScheduleChangeData> CHANGE_MAPPER =
      (r, n) ->
          new ScheduleChangeData(
              r.getLong("id"),
              r.getLong("schedule_id"),
              r.getLong("base_schedule_version"),
              time(r, "proposed_pickup_at"),
              time(r, "proposed_delivery_at"),
              ChangeDecision.valueOf(r.getString("decision")),
              time(r, "created_at"),
              r.getLong("version"));
}
