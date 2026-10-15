package edu.delivery.auth;

import static edu.delivery.contract.DeliveryData.*;

import edu.delivery.contract.DeliveryStore;
import java.util.Arrays;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Shared record-level checks. URL roles alone do not protect ownership. */
@Component("access")
public class AccessGuard {
  private final DeliveryStore store;

  public AccessGuard(DeliveryStore store) {
    this.store = store;
  }

  public DeliveryPrincipal current() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null
        || !auth.isAuthenticated()
        || !(auth.getPrincipal() instanceof DeliveryPrincipal p)) {
      throw new AuthenticationCredentialsNotFoundException("Sign in required");
    }
    return p;
  }

  public void requireRole(Role... allowed) {
    if (!Arrays.asList(allowed).contains(current().role())) {
      deny();
    }
  }

  public void requireCustomer(long customerUserId) {
    var p = current();
    if (p.role() == Role.OFFICE_STAFF) {
      return;
    }
    if (p.role() != Role.CUSTOMER || p.userId() != customerUserId) {
      deny();
    }
  }

  public void requireRequestRead(long requestId) {
    var p = current();
    if (p.role() == Role.OFFICE_STAFF) {
      return;
    }
    var request =
        store.findRequest(requestId).orElseThrow(() -> new AccessDeniedException("Access denied"));
    if (p.role() == Role.CUSTOMER && request.customerId() == p.userId()) {
      return;
    }
    if (p.role() == Role.DELIVERY_STAFF) {
      requireAssignedDelivery(requestId);
      return;
    }
    deny();
  }

  public void requireAssignedDelivery(long requestId) {
    var p = current();
    if (p.role() == Role.OFFICE_STAFF) {
      return;
    }
    if (p.role() != Role.DELIVERY_STAFF) {
      deny();
    }
    var schedule =
        store
            .findCurrentSchedule(requestId)
            .orElseThrow(() -> new AccessDeniedException("Access denied"));
    boolean own =
        store.findActiveStaff().stream()
            .anyMatch(s -> s.id() == schedule.staffId() && s.userId() == p.userId());
    if (!own) {
      deny();
    }
  }

  public void requireStaff(long staffId) {
    var p = current();
    if (p.role() == Role.OFFICE_STAFF) {
      return;
    }
    if (p.role() != Role.DELIVERY_STAFF
        || store.findActiveStaff().stream()
            .noneMatch(s -> s.id() == staffId && s.userId() == p.userId())) {
      deny();
    }
  }

  private void deny() {
    throw new AccessDeniedException("Access denied");
  }
}
