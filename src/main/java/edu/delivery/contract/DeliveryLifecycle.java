package edu.delivery.contract;

import static edu.delivery.contract.DeliveryData.*;

import java.util.List;

/**
 * Implemented by B; reused by C. Does not read/write the database. Caller checks authenticated
 * user, task allocation and accepted quote eligibility.
 */
public interface DeliveryLifecycle {
  /**
   * Validate event chronology and permitted lifecycle transition, returning next status. History is
   * timestamp/ID ordered. Failed attempts can retain the current status. Reject unsupported
   * events/transitions with IllegalArgumentException.
   */
  RequestStatus nextStatus(RequestData request, List<EventData> history, EventDraft event);
}
