package edu.delivery.auth;

import edu.delivery.contract.DeliveryData.AccountData;
import edu.delivery.contract.DeliveryData.Role;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Internal security principal. Never return this object from a web controller. */
public final class DeliveryPrincipal implements UserDetails {
  private static final long serialVersionUID = 1L;
  private final AccountData account;

  public DeliveryPrincipal(AccountData account) {
    this.account = account;
  }

  public long userId() {
    return account.id();
  }

  public Role role() {
    return account.role();
  }

  public String displayName() {
    return account.displayName();
  }

  @Override
  public String getUsername() {
    return account.username();
  }

  @Override
  public String getPassword() {
    return account.passwordHash();
  }

  @Override
  public boolean isEnabled() {
    return account.active();
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_" + account.role().name()));
  }
}
