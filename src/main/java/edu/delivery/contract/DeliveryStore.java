package edu.delivery.contract;

import static edu.delivery.contract.DeliveryData.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Persistence operations; business services enforce permissions and state rules. */
public interface DeliveryStore {

  /** Exceptions roll back all operations in the callback. */
  <T> T inTransaction(Function<DeliveryStore, T> work);

  /** Requires a transaction; lock request IDs in ascending order. */
  void lockRequest(long requestId);

  /** Lock staff IDs in ascending order before locking requests. */
  void lockStaff(long staffId);

  Optional<AccountData> findAccountByUsername(String username);

  /** Atomically saves the request and parcel; submission keys prevent duplicate retries. */
  RequestData createRequest(RequestDraft draft);

  Optional<RequestData> findRequest(long requestId);

  List<RequestData> findRequests(RequestFilter filter);

  RequestData updateRequestDetails(long requestId, long expectedVersion, RequestDetails details);

  QuoteData createQuote(QuoteDraft draft);

  Optional<QuoteData> findQuote(long quoteId);

  List<QuoteData> findQuotesForRequest(long requestId);

  QuoteData updateQuoteStatus(long quoteId, long expectedVersion, QuoteStatus status);

  List<StaffData> findActiveStaff();

  List<AvailabilityData> findAvailability(long staffId, OffsetDateTime from, OffsetDateTime to);

  AvailabilityData createAvailability(AvailabilityDraft draft);

  AvailabilityData updateAvailability(
      long availabilityId, long expectedVersion, AvailabilityDraft draft);

  Optional<ScheduleData> findCurrentSchedule(long requestId);

  List<ScheduleData> findSchedulesForStaff(long staffId, OffsetDateTime from, OffsetDateTime to);

  /** Active half-open overlaps; hold the staff lock while checking and saving. */
  List<ScheduleData> findOverlappingSchedules(long staffId, OffsetDateTime from, OffsetDateTime to);

  /** Requires staff and request locks in the same transaction. */
  ScheduleData createSchedule(ScheduleDraft draft);

  /** Lock both old and new staff before the request; stale versions are rejected. */
  ScheduleData updateSchedule(long scheduleId, long expectedVersion, ScheduleDraft draft);

  ScheduleChangeData createScheduleChange(ScheduleChangeDraft draft);

  List<ScheduleChangeData> findScheduleChanges(long scheduleId);

  ScheduleChangeData updateScheduleChangeDecision(
      long changeId, long expectedVersion, ChangeDecision decision);

  /** Saves status and history atomically; failed attempts may retain the status. */
  RequestData saveStatusAndEvent(
      long requestId, long expectedVersion, RequestStatus newStatus, EventDraft event);

  List<EventData> findEvents(long requestId);
}
