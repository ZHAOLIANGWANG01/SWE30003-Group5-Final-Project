package edu.delivery.contract;

import static edu.delivery.contract.DeliveryData.*;

import java.util.List;

/** Validates event order and state transitions. */
public interface DeliveryLifecycle {

  RequestStatus nextStatus(RequestData request, List<EventData> history, EventDraft event);
}
