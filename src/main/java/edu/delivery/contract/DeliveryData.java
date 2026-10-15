package edu.delivery.contract;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Exchange data only. Domain behavior belongs to the business modules. */
public final class DeliveryData {
  private DeliveryData() {}

  public enum Role {
    CUSTOMER,
    OFFICE_STAFF,
    DELIVERY_STAFF
  }

  public enum RequestStatus {
    REQUESTED,
    QUOTED,
    ACCEPTED,
    PICKED_UP,
    IN_TRANSIT,
    DELIVERED,
    CANCELLED
  }

  public enum QuoteStatus {
    ISSUED,
    ACCEPTED,
    DECLINED,
    EXPIRED
  }

  public enum AvailabilityKind {
    AVAILABLE,
    UNAVAILABLE
  }

  public enum ChangeDecision {
    PENDING,
    CONFIRMED,
    DECLINED
  }

  public enum EventType {
    QUOTED,
    QUOTE_ACCEPTED,
    PICKED_UP,
    IN_TRANSIT,
    DELIVERED,
    PICKUP_FAILED,
    DELIVERY_FAILED
  }

  /** Internal authentication data; never serialize passwordHash to a browser. */
  public record AccountData(
      long id,
      String username,
      String passwordHash,
      Role role,
      String displayName,
      boolean active) {}

  public record RequestDetails(
      String customerName,
      String customerPhone,
      String pickupAddress,
      String deliveryAddress,
      String recipientName,
      String recipientPhone,
      String parcelDescription,
      String handlingNotes) {}

  /** submissionKey identifies one form submission, not a delivery's contents. */
  public record RequestDraft(long customerId, String submissionKey, RequestDetails details) {}

  public record RequestData(
      long id,
      long customerId,
      RequestDetails details,
      RequestStatus status,
      OffsetDateTime createdAt,
      long version) {}

  /** Null customerId/status means no filter; offset >= 0, 1 <= limit <= 100. */
  public record RequestFilter(Long customerId, RequestStatus status, int offset, int limit) {}

  public record QuoteDraft(
      long requestId,
      BigDecimal amount,
      OffsetDateTime pickupAt,
      OffsetDateTime deliveryAt,
      OffsetDateTime expiresAt) {}

  public record QuoteData(
      long id,
      long requestId,
      BigDecimal amount,
      OffsetDateTime pickupAt,
      OffsetDateTime deliveryAt,
      OffsetDateTime expiresAt,
      QuoteStatus status,
      long version) {}

  public record StaffData(long id, long userId, String displayName, boolean active) {}

  public record AvailabilityDraft(
      long staffId, OffsetDateTime startAt, OffsetDateTime endAt, AvailabilityKind kind) {}

  public record AvailabilityData(
      long id,
      long staffId,
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      AvailabilityKind kind,
      long version) {}

  public record ScheduleDraft(
      long requestId, long staffId, OffsetDateTime pickupAt, OffsetDateTime deliveryAt) {}

  public record ScheduleData(
      long id,
      long requestId,
      long staffId,
      OffsetDateTime pickupAt,
      OffsetDateTime deliveryAt,
      long version) {}

  public record ScheduleChangeDraft(
      long scheduleId,
      long baseScheduleVersion,
      OffsetDateTime proposedPickupAt,
      OffsetDateTime proposedDeliveryAt) {}

  public record ScheduleChangeData(
      long id,
      long scheduleId,
      long baseScheduleVersion,
      OffsetDateTime proposedPickupAt,
      OffsetDateTime proposedDeliveryAt,
      ChangeDecision decision,
      OffsetDateTime createdAt,
      long version) {}

  public record EventDraft(
      EventType type, OffsetDateTime occurredAt, long actorUserId, String note) {}

  public record EventData(
      long id,
      long requestId,
      EventType type,
      OffsetDateTime occurredAt,
      long actorUserId,
      String note) {}
}
