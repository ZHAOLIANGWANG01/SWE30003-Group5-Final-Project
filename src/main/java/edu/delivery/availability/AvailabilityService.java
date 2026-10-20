package edu.delivery.availability;

import static edu.delivery.contract.DeliveryData.*;

import edu.delivery.auth.AccessGuard;
import edu.delivery.contract.DeliveryStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AvailabilityService {
  private static final OffsetDateTime EARLIEST = OffsetDateTime.of(1900, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
  private static final OffsetDateTime LATEST = OffsetDateTime.of(2200, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
  private final DeliveryStore store;
  private final AccessGuard access;
  private final Clock clock;

  public AvailabilityService(DeliveryStore store, AccessGuard access, Clock clock) {
    this.store = store;
    this.access = access;
    this.clock = clock;
  }

  public List<StaffData> staff() {
    access.requireRole(Role.OFFICE_STAFF, Role.DELIVERY_STAFF);
    if (access.current().role() == Role.OFFICE_STAFF) return store.findActiveStaff();
    long userId = access.current().userId();
    return store.findActiveStaff().stream().filter(s -> s.userId() == userId).toList();
  }

  public List<AvailabilityData> list(long staffId) {
    access.requireRole(Role.OFFICE_STAFF, Role.DELIVERY_STAFF);
    access.requireStaff(staffId);
    return store.findAvailability(staffId, EARLIEST, LATEST);
  }

  public AvailabilityData save(long staffId, Long availabilityId, Long expectedVersion,
      OffsetDateTime startAt, OffsetDateTime endAt, AvailabilityKind kind) {
    access.requireRole(Role.OFFICE_STAFF, Role.DELIVERY_STAFF);
    access.requireStaff(staffId);
    validateRange(startAt, endAt);
    if (kind == null) throw new IllegalArgumentException("Choose available or unavailable time.");
    return store.inTransaction(tx -> {
      tx.lockStaff(staffId);
      requireActiveStaff(tx, staffId);
      List<AvailabilityData> existing = tx.findAvailability(staffId, EARLIEST, LATEST);
      if (availabilityId != null && (expectedVersion == null || expectedVersion < 0))
        throw new IllegalArgumentException("Refresh the time period before editing it.");
      AvailabilityData previous = availabilityId == null ? null : existing.stream()
          .filter(a -> a.id() == availabilityId).findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Availability period not found for this staff member."));
      List<AvailabilityData> unchanged = existing.stream()
          .filter(a -> previous == null || a.id() != previous.id()).toList();
      boolean overlapsPeriod = unchanged.stream()
          .anyMatch(a -> a.startAt().isBefore(endAt) && a.endAt().isAfter(startAt));
      if (overlapsPeriod) throw new IllegalArgumentException("This period overlaps another availability entry.");

      Map<Long, ScheduleData> affected = new LinkedHashMap<>();
      addOverlaps(tx, affected, staffId, startAt, endAt);
      if (previous != null) addOverlaps(tx, affected, staffId, previous.startAt(), previous.endAt());
      List<AvailabilityPeriod> proposed = new ArrayList<>();
      unchanged.forEach(a -> proposed.add(new AvailabilityPeriod(a.startAt(), a.endAt(), a.kind())));
      proposed.add(new AvailabilityPeriod(startAt, endAt, kind));
      for (ScheduleData schedule : affected.values()) {
        List<AvailabilityPeriod> matching = proposed.stream()
            .filter(a -> a.startAt().isBefore(schedule.deliveryAt()) && a.endAt().isAfter(schedule.pickupAt())).toList();
        boolean covered = matching.stream().anyMatch(a -> a.kind() == AvailabilityKind.AVAILABLE
            && !a.startAt().isAfter(schedule.pickupAt()) && !a.endAt().isBefore(schedule.deliveryAt()));
        boolean blocked = matching.stream().anyMatch(a -> a.kind() == AvailabilityKind.UNAVAILABLE);
        if (!covered || blocked) {
          throw new IllegalArgumentException("This change affects scheduled request #" + schedule.requestId()
              + ". Reassign or reschedule that delivery first.");
        }
      }
      AvailabilityDraft draft = new AvailabilityDraft(staffId, startAt, endAt, kind);
      if (availabilityId == null) return tx.createAvailability(draft);
      return tx.updateAvailability(availabilityId, expectedVersion, draft);
    });
  }

  private void requireActiveStaff(DeliveryStore tx, long staffId) {
    boolean active = tx.findActiveStaff().stream().anyMatch(s -> s.id() == staffId && s.active());
    if (!active) throw new IllegalArgumentException("The selected delivery staff member is not active.");
  }

  private void addOverlaps(DeliveryStore tx, Map<Long, ScheduleData> affected, long staffId,
      OffsetDateTime startAt, OffsetDateTime endAt) {
    tx.findOverlappingSchedules(staffId, startAt, endAt).forEach(s -> affected.put(s.id(), s));
  }

  private void validateRange(OffsetDateTime startAt, OffsetDateTime endAt) {
    if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
      throw new IllegalArgumentException("End time must be later than start time.");
    }
    if (!endAt.isAfter(OffsetDateTime.now(clock))) {
      throw new IllegalArgumentException("Availability must extend into the future.");
    }
  }

  private record AvailabilityPeriod(OffsetDateTime startAt, OffsetDateTime endAt, AvailabilityKind kind) {}
}
