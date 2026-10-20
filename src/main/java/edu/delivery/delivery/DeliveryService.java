package edu.delivery.delivery;

import static edu.delivery.contract.DeliveryData.*;

import edu.delivery.auth.AccessGuard;
import edu.delivery.auth.DeliveryPrincipal;
import edu.delivery.contract.DeliveryLifecycle;
import edu.delivery.contract.DeliveryStore;
import edu.delivery.contract.StoreException;
import edu.delivery.scheduling.SchedulingService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DeliveryService {
  private final DeliveryStore store;
  private final AccessGuard access;
  private final DeliveryLifecycle lifecycle;
  private final SchedulingService schedules;
  private final Clock clock;

  public DeliveryService(DeliveryStore store, AccessGuard access, DeliveryLifecycle lifecycle,
      SchedulingService schedules, Clock clock) {
    this.store = store;
    this.access = access;
    this.lifecycle = lifecycle;
    this.schedules = schedules;
    this.clock = clock;
  }

  public List<SchedulingService.ScheduleCard> myTasks() {
    access.requireRole(Role.DELIVERY_STAFF, Role.OFFICE_STAFF);
    if (access.current().role() == Role.OFFICE_STAFF) return schedules.schedules();
    long staffId = selectedStaffId();
    OffsetDateTime now = OffsetDateTime.now(clock);
    return store.findSchedulesForStaff(staffId, now.minusDays(180), now.plusYears(3)).stream()
        .map(s -> schedules.cardForRequest(s.requestId())).toList();
  }

  public TaskDetails task(long requestId) {
    access.requireRole(Role.DELIVERY_STAFF, Role.OFFICE_STAFF);
    access.requireAssignedDelivery(requestId);
    var schedule = schedules.cardForRequest(requestId);
    var request = store.findRequest(requestId).orElseThrow(() -> notFound("Request not found"));
    return new TaskDetails(schedule, request, store.findEvents(requestId));
  }

  public List<EventData> customerProgress(long requestId) {
    access.requireRole(Role.CUSTOMER, Role.OFFICE_STAFF);
    access.requireRequestRead(requestId);
    return store.findEvents(requestId);
  }

  public SchedulingService.ScheduleCard scheduleForCustomer(long requestId) {
    access.requireRole(Role.CUSTOMER, Role.OFFICE_STAFF);
    access.requireRequestRead(requestId);
    return schedules.cardForRequest(requestId);
  }

  public RequestData record(long requestId, EventType type, String note) {
    access.requireRole(Role.DELIVERY_STAFF, Role.OFFICE_STAFF);
    access.requireAssignedDelivery(requestId);
    if (type == null) throw new IllegalArgumentException("Choose a delivery update.");
    if (type != EventType.PICKED_UP && type != EventType.IN_TRANSIT && type != EventType.DELIVERED
        && type != EventType.PICKUP_FAILED && type != EventType.DELIVERY_FAILED) {
      throw new IllegalArgumentException("Choose a valid delivery update.");
    }
    String safeNote = note == null ? "" : note.trim();
    if ((type == EventType.PICKUP_FAILED || type == EventType.DELIVERY_FAILED) && safeNote.isBlank()) {
      throw new IllegalArgumentException("Add a reason for the failed delivery attempt.");
    }
    if (safeNote.length() > 2000) throw new IllegalArgumentException("Notes must be 2000 characters or fewer.");
    long actorUserId = access.current().userId();
    return store.inTransaction(tx -> {
      tx.lockRequest(requestId);
      access.requireAssignedDelivery(requestId);
      RequestData request = tx.findRequest(requestId)
          .orElseThrow(() -> notFound("Request not found"));
      EventDraft event = new EventDraft(type, OffsetDateTime.now(clock), actorUserId, safeNote);
      RequestStatus next = lifecycle.nextStatus(request, tx.findEvents(requestId), event);
      return tx.saveStatusAndEvent(requestId, request.version(), next, event);
    });
  }

  private long selectedStaffId() {
    DeliveryPrincipal principal = access.current();
    return store.findActiveStaff().stream()
        .filter(s -> s.userId() == principal.userId() && s.active())
        .map(StaffData::id).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("This account is not linked to active delivery staff."));
  }

  private static StoreException notFound(String message) {
    return new StoreException(StoreException.Code.NOT_FOUND, message);
  }

  public record TaskDetails(SchedulingService.ScheduleCard card, RequestData request, List<EventData> events) {}
}
