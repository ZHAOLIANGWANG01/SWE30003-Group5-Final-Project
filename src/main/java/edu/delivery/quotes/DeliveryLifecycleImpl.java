package edu.delivery.quotes;

import edu.delivery.contract.DeliveryLifecycle;
import edu.delivery.contract.DeliveryData.*;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** B's reusable lifecycle rules. Does not access persistence or authenticated sessions. */
@Component
public class DeliveryLifecycleImpl implements DeliveryLifecycle {
  @Override
  public RequestStatus nextStatus(RequestData request, List<EventData> history, EventDraft event) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(history, "history");
    Objects.requireNonNull(event, "event");
    Objects.requireNonNull(event.type(), "event type");
    Objects.requireNonNull(event.occurredAt(), "event time");
    if (event.actorUserId() <= 0 || (event.note() != null && event.note().length() > 2000)) {
      throw new IllegalArgumentException("Invalid delivery event");
    }
    for (int i = 0; i < history.size(); i++) {
      EventData prior = history.get(i);
      if (prior.requestId() != request.id()) throw new IllegalArgumentException("Unrelated event history");
      if (i > 0 && history.get(i - 1).occurredAt().isAfter(prior.occurredAt())) {
        throw new IllegalArgumentException("Event history is not time ordered");
      }
    }
    if (!history.isEmpty() && event.occurredAt().isBefore(history.get(history.size() - 1).occurredAt())) {
      throw new IllegalArgumentException("Event cannot precede previous delivery event");
    }
    RequestStatus previous = request.status();
    return switch (event.type()) {
      case QUOTED -> {
        require(previous == RequestStatus.REQUESTED || previous == RequestStatus.QUOTED, "Quote requires an open request");
        yield RequestStatus.QUOTED;
      }
      case QUOTE_ACCEPTED -> {
        require(previous == RequestStatus.QUOTED, "Quote acceptance requires a quoted request");
        yield RequestStatus.ACCEPTED;
      }
      case PICKED_UP -> {
        require(previous == RequestStatus.ACCEPTED, "Pickup requires accepted delivery");
        yield RequestStatus.PICKED_UP;
      }
      case IN_TRANSIT -> {
        require(previous == RequestStatus.PICKED_UP, "Transit requires pickup");
        yield RequestStatus.IN_TRANSIT;
      }
      case DELIVERED -> {
        require(previous == RequestStatus.IN_TRANSIT, "Delivery requires in-transit status");
        yield RequestStatus.DELIVERED;
      }
      case PICKUP_FAILED -> {
        require(previous == RequestStatus.ACCEPTED, "Pickup failure requires accepted request");
        yield previous;
      }
      case DELIVERY_FAILED -> {
        require(previous == RequestStatus.PICKED_UP || previous == RequestStatus.IN_TRANSIT,
            "Delivery failure requires collected parcel");
        yield previous;
      }
    };
  }

  private static void require(boolean allowed, String message) {
    if (!allowed) throw new IllegalArgumentException(message);
  }
}
