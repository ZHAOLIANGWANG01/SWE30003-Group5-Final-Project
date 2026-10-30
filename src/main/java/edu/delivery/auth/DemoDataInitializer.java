package edu.delivery.auth;

import edu.delivery.contract.DeliveryStore;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Create missing accounts without replacing existing data. */
@Component
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {
  private final JdbcTemplate jdbc;
  private final DeliveryStore store;
  private final PasswordEncoder encoder;
  private final String password;
  private final Clock clock;

  public DemoDataInitializer(
      JdbcTemplate jdbc,
      DeliveryStore store,
      PasswordEncoder encoder,
      @Value("${app.demo.password}") String password,
      Clock clock) {
    this.jdbc = jdbc;
    this.store = store;
    this.encoder = encoder;
    this.password = password;
    this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments args) {
    store.inTransaction(
        tx -> {
          seed("customer.alice", "CUSTOMER", "Alice Customer");
          seed("customer.bob", "CUSTOMER", "Bob Customer");
          seed("office.demo", "OFFICE_STAFF", "Office Coordinator");
          seed("driver.li", "DELIVERY_STAFF", "Li Delivery");
          seed("driver.sam", "DELIVERY_STAFF", "Sam Delivery");
          return null;
        });
  }

  private void seed(String username, String role, String name) {
    if (store.findAccountByUsername(username).isEmpty()) {
      jdbc.update(
          "INSERT INTO users(username,password_hash,role,display_name) VALUES (?,?,?,?)",
          username,
          encoder.encode(password),
          role,
          name);
    }
    if (role.equals("DELIVERY_STAFF")) {
      long userId = store.findAccountByUsername(username).orElseThrow().id();
      int added =
          jdbc.update(
              """
              INSERT INTO delivery_staff(user_id) SELECT ?
              WHERE NOT EXISTS (SELECT 1 FROM delivery_staff WHERE user_id=?)
              """,
              userId,
              userId);
      if (added == 1) {
        long staffId =
            store.findActiveStaff().stream()
                .filter(s -> s.userId() == userId)
                .findFirst()
                .orElseThrow()
                .id();
        ZoneId zone = ZoneId.of("Australia/Melbourne");
        LocalDate day = LocalDate.now(clock.withZone(zone));
        for (int i = 0; i < 14; i++) {
          var date = day.plusDays(i);
          if (date.getDayOfWeek().getValue() <= 5) {
            jdbc.update(
                "INSERT INTO staff_availability(staff_id,start_at,end_at,kind) VALUES"
                    + " (?,?,?,'AVAILABLE')",
                staffId,
                date.atTime(9, 0).atZone(zone).toOffsetDateTime(),
                date.atTime(17, 0).atZone(zone).toOffsetDateTime());
          }
        }
      }
    }
  }
}
