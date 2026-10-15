package edu.delivery.web;

import edu.delivery.auth.AccessGuard;
import edu.delivery.contract.DeliveryData.Role;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
  private final AccessGuard access;

  public SessionController(AccessGuard access) {
    this.access = access;
  }

  public record SessionView(long userId, String username, String displayName, Role role) {}

  @GetMapping("/api/session")
  public SessionView session() {
    var p = access.current();
    return new SessionView(p.userId(), p.getUsername(), p.displayName(), p.role());
  }
}
