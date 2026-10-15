package edu.delivery.contract;

import static edu.delivery.contract.DeliveryData.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Internal persistence facade, implemented by A using H2/JdbcTemplate. Business services enforce
 * authorization and business rules before writes. All methods return non-null values. Updates
 * increment version and reject stale writes. Each write is atomic; related multi-method writes use
 * inTransaction.
 */
public interface DeliveryStore {
  /**
   * Execute all callback store operations in one transaction. Exceptions roll back all writes;
   * never swallow a failure inside the callback. An implementation must make every callback
   * operation join the same transaction.
   */
  <T> T inTransaction(Function<DeliveryStore, T> work);

  /** Transaction required. Database row lock, held until transaction ends. */
  void lockRequest(long requestId);

  /**
   * Transaction required. Lock staff before checking availability/overlaps or writing
   * availability/schedules. Multiple staff IDs are locked in ascending order, then requests.
   */
  void lockStaff(long staffId);

  Optional<AccountData> findAccountByUsername(String username);

  /**
   * Atomically create request and parcel, initially REQUESTED with version 0. Same
   * customer/key/content returns the original request; changed content conflicts.
   */
  RequestData createRequest(RequestDraft draft);

  Optional<RequestData> findRequest(long requestId);

  /** Newest createdAt first, then ID descending; caller enforces permitted visibility. */
  List<RequestData> findRequests(RequestFilter filter);

  /** Details and parcel update together; no lifecycle change. */
  RequestData updateRequestDetails(long requestId, long expectedVersion, RequestDetails details);

  /** Create one ISSUED quote; coordinate the request state change in one transaction. */
  QuoteData createQuote(QuoteDraft draft);

  Optional<QuoteData> findQuote(long quoteId);

  /** Quotes returned newest ID first; caller chooses eligible quote and checks expiry. */
  List<QuoteData> findQuotesForRequest(long requestId);

  /** Valid decision/expiry rules are checked by the quote business service. */
  QuoteData updateQuoteStatus(long quoteId, long expectedVersion, QuoteStatus status);

  List<StaffData> findActiveStaff();

  /** Overlapping half-open periods [from, to); from must precede to. */
  List<AvailabilityData> findAvailability(long staffId, OffsetDateTime from, OffsetDateTime to);

  /** Transaction + staff lock required; business service checks affected schedules. */
  AvailabilityData createAvailability(AvailabilityDraft draft);

  /** Staff association is unchanged; transaction + staff lock required. */
  AvailabilityData updateAvailability(
      long availabilityId, long expectedVersion, AvailabilityDraft draft);

  Optional<ScheduleData> findCurrentSchedule(long requestId);

  /** Include completed tasks; select by planned pickup in [from, to), earliest first. */
  List<ScheduleData> findSchedulesForStaff(long staffId, OffsetDateTime from, OffsetDateTime to);

  /**
   * Only active commitments, excluding completed deliveries. Half-open overlap: existing pickup <
   * to AND existing delivery > from. This query alone does not prevent concurrent double booking;
   * use transaction + locks.
   */
  List<ScheduleData> findOverlappingSchedules(long staffId, OffsetDateTime from, OffsetDateTime to);

  /**
   * Transaction + staff/request locks required. At most one current schedule per request. Business
   * service checks accepted quote, working periods and overlapping commitments.
   */
  ScheduleData createSchedule(ScheduleDraft draft);

  /**
   * requestId is unchanged. Lock old/new staff (ascending), then request. Business service checks
   * customer confirmation and conflicts before saving.
   */
  ScheduleData updateSchedule(long scheduleId, long expectedVersion, ScheduleDraft draft);

  /** Save PENDING proposal only; do not overwrite the confirmed schedule. */
  ScheduleChangeData createScheduleChange(ScheduleChangeDraft draft);

  List<ScheduleChangeData> findScheduleChanges(long scheduleId);

  /**
   * A confirmed revision and its schedule update must share one transaction with locks. Business
   * service validates customer authorization and renewed availability.
   */
  ScheduleChangeData updateScheduleChangeDecision(
      long changeId, long expectedVersion, ChangeDecision decision);

  /**
   * Atomically update request status/version and append its event. Failed attempts can append an
   * event with an unchanged status. Domain service checks event type, chronology, actor and legal
   * transition.
   */
  RequestData saveStatusAndEvent(
      long requestId, long expectedVersion, RequestStatus newStatus, EventDraft event);

  /** Event timestamp then ID ascending. */
  List<EventData> findEvents(long requestId);
}
