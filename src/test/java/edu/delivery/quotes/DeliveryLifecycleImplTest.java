package edu.delivery.quotes;

import edu.delivery.contract.DeliveryData.*;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryLifecycleImplTest {
  private final DeliveryLifecycleImpl lifecycle = new DeliveryLifecycleImpl();
  private final OffsetDateTime time = OffsetDateTime.parse("2026-11-01T10:00:00+11:00");

  private RequestData request(RequestStatus state) {
    return new RequestData(1L, 2L, null, state, time.minusDays(1), 0);
  }

  private EventDraft event(EventType type) {
    return new EventDraft(type, time, 99L, "Test event");
  }

  @Test void quoteThenAcceptance() {
    assertEquals(RequestStatus.QUOTED,
        lifecycle.nextStatus(request(RequestStatus.REQUESTED), List.of(), event(EventType.QUOTED)));
    assertEquals(RequestStatus.ACCEPTED,
        lifecycle.nextStatus(request(RequestStatus.QUOTED), List.of(), event(EventType.QUOTE_ACCEPTED)));
  }

  @Test void completeDeliveryInOrder() {
    assertEquals(RequestStatus.PICKED_UP,
        lifecycle.nextStatus(request(RequestStatus.ACCEPTED), List.of(), event(EventType.PICKED_UP)));
    assertEquals(RequestStatus.IN_TRANSIT,
        lifecycle.nextStatus(request(RequestStatus.PICKED_UP), List.of(), event(EventType.IN_TRANSIT)));
    assertEquals(RequestStatus.DELIVERED,
        lifecycle.nextStatus(request(RequestStatus.IN_TRANSIT), List.of(), event(EventType.DELIVERED)));
  }

  @Test void rejectsIllegalTransitions() {
    assertThrows(IllegalArgumentException.class,
        () -> lifecycle.nextStatus(request(RequestStatus.REQUESTED), List.of(), event(EventType.DELIVERED)));
    assertThrows(IllegalArgumentException.class,
        () -> lifecycle.nextStatus(request(RequestStatus.ACCEPTED), List.of(), event(EventType.QUOTE_ACCEPTED)));
  }

  @Test void failedPickupDoesNotAdvanceState() {
    assertEquals(RequestStatus.ACCEPTED,
        lifecycle.nextStatus(request(RequestStatus.ACCEPTED), List.of(), event(EventType.PICKUP_FAILED)));
    assertEquals(RequestStatus.IN_TRANSIT,
        lifecycle.nextStatus(request(RequestStatus.IN_TRANSIT), List.of(), event(EventType.DELIVERY_FAILED)));
  }

  @Test void rejectsOutOfOrderEvents() {
    var past = new EventData(1, 1, EventType.QUOTED, time.plusHours(1), 99, "old");
    assertThrows(IllegalArgumentException.class,
        () -> lifecycle.nextStatus(request(RequestStatus.QUOTED), List.of(past), event(EventType.QUOTE_ACCEPTED)));
  }
}
