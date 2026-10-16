package edu.delivery.requests;

import edu.delivery.auth.AccessGuard;
import edu.delivery.auth.DeliveryPrincipal;
import edu.delivery.contract.DeliveryStore;
import edu.delivery.contract.DeliveryData.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RequestServiceTest {
  private RequestDetails details() {
    return new RequestDetails("Alice", "0400123456", "Pickup Road", "Dropoff St",
        "Bob", "0400987654", "Books", "Careful");
  }

  @Test void blankAddressRejectedBeforeSave() {
    DeliveryStore store = mock(DeliveryStore.class);
    AccessGuard access = mock(AccessGuard.class);
    RequestService service = new RequestService(store, access);
    RequestDetails bad = new RequestDetails("Alice", "0400123456", " ", "Dropoff St",
        "Bob", "0400987654", "Books", "Careful");
    assertThrows(IllegalArgumentException.class,
        () -> service.submitRequest(UUID.randomUUID().toString(), bad));
    verify(store, never()).createRequest(any());
  }

  @Test void customerSeesOnlyOwnRequests() {
    DeliveryStore store = mock(DeliveryStore.class);
    AccessGuard access = mock(AccessGuard.class);
    DeliveryPrincipal user = new DeliveryPrincipal(
        new AccountData(7L, "customer.alice", "unused", Role.CUSTOMER, "Alice", true));
    when(access.current()).thenReturn(user);
    when(store.findRequests(any())).thenReturn(List.of());
    new RequestService(store, access).listRequests(0);
    verify(store).findRequests(new RequestFilter(7L, null, 0, 20));
  }
}
