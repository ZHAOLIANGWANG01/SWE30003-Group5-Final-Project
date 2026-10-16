package edu.delivery.requests;

import edu.delivery.auth.AccessGuard;
import edu.delivery.contract.DeliveryStore;
import edu.delivery.contract.DeliveryData.*;
import edu.delivery.contract.StoreException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Member B: all request validation and authorization, with persistence delegated to A. */
@Service
public class RequestService {
  private final DeliveryStore store;
  private final AccessGuard access;

  public RequestService(DeliveryStore store, AccessGuard access) {
    this.store = store;
    this.access = access;
  }


  public RequestData submitRequest(String submissionKey, RequestDetails details) {
    access.requireRole(Role.CUSTOMER);

    // Validate input before reading the current user.
    validateSubmissionKey(submissionKey);
    validateDetails(details);

    return createForCustomer(
            access.current().userId(),
            submissionKey,
            details);
  }


  public RequestData submitForCustomer(String username, String submissionKey, RequestDetails details) {
    access.requireRole(Role.OFFICE_STAFF);
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("Customer username is required");
    }
    AccountData customer = store.findAccountByUsername(username.trim())
        .filter(a -> a.role() == Role.CUSTOMER && a.active())
        .orElseThrow(() -> new IllegalArgumentException("Active customer not found"));
    return createForCustomer(customer.id(), submissionKey, details);
  }

  private RequestData createForCustomer(long customerId, String submissionKey, RequestDetails details) {
    validateSubmissionKey(submissionKey);
    validateDetails(details);
    return store.createRequest(new RequestDraft(customerId, submissionKey, clean(details)));
  }

  public RequestData viewRequest(long requestId) {
    access.requireRequestRead(requestId);
    return store.findRequest(requestId)
        .orElseThrow(() -> new StoreException(StoreException.Code.NOT_FOUND, "Request not found"));
  }

  public List<RequestData> listRequests(int page) {
    access.requireRole(Role.CUSTOMER, Role.OFFICE_STAFF);
    if (page < 0 || page > Integer.MAX_VALUE / 20) {
      throw new IllegalArgumentException("Invalid page number");
    }
    Long customerId = access.current().role() == Role.CUSTOMER
        ? access.current().userId() : null;
    return store.findRequests(new RequestFilter(customerId, null, page * 20, 20));
  }

  public RequestData updateRequest(long requestId, long version, RequestDetails details) {
    access.requireRole(Role.CUSTOMER, Role.OFFICE_STAFF);
    validateDetails(details);
    return store.inTransaction(tx -> {
      tx.lockRequest(requestId);
      RequestData existing = tx.findRequest(requestId)
          .orElseThrow(() -> new StoreException(StoreException.Code.NOT_FOUND, "Request not found"));
      access.requireCustomer(existing.customerId());
      if (existing.status() != RequestStatus.REQUESTED) {
        throw new IllegalArgumentException("Only unquoted requests may be edited. Contact the office for a new quote.");
      }
      if (existing.version() != version) {
        throw new StoreException(StoreException.Code.STALE_DATA, "Request changed; reload before editing");
      }
      return tx.updateRequestDetails(requestId, version, clean(details));
    });
  }

  public static void validateSubmissionKey(String submissionKey) {
    if (submissionKey == null || submissionKey.length() > 100) {
      throw new IllegalArgumentException("Submission key is required");
    }
    try {
      UUID.fromString(submissionKey);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid submission key");
    }
  }

  public static void validateDetails(RequestDetails d) {
    if (d == null) throw new IllegalArgumentException("Request details are required");
    required(d.customerName(), 120, "Customer name");
    phone(d.customerPhone(), "Customer phone");
    required(d.pickupAddress(), 500, "Pickup address");
    required(d.deliveryAddress(), 500, "Delivery address");
    required(d.recipientName(), 120, "Recipient name");
    phone(d.recipientPhone(), "Recipient phone");
    required(d.parcelDescription(), 1000, "Parcel description");
    if (d.handlingNotes() != null && d.handlingNotes().length() > 2000) {
      throw new IllegalArgumentException("Handling notes are too long");
    }
  }

  private static RequestDetails clean(RequestDetails d) {
    return new RequestDetails(d.customerName().trim(), d.customerPhone().trim(),
        d.pickupAddress().trim(), d.deliveryAddress().trim(), d.recipientName().trim(),
        d.recipientPhone().trim(), d.parcelDescription().trim(),
        d.handlingNotes() == null ? "" : d.handlingNotes().trim());
  }

  private static void required(String value, int max, String label) {
    if (value == null || value.isBlank() || value.length() > max) {
      throw new IllegalArgumentException(label + " must be provided (max " + max + " characters)");
    }
  }

  private static void phone(String value, String label) {
    required(value, 40, label);
    if (!value.trim().matches("[+0-9() .-]{7,40}")) {
      throw new IllegalArgumentException(label + " has an invalid format");
    }
  }
}
