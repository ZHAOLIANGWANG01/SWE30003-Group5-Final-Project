package edu.delivery.persistence;

import static edu.delivery.contract.DeliveryData.*;
import static org.assertj.core.api.Assertions.*;

import edu.delivery.contract.DeliveryStore;
import java.nio.file.Path;
import java.time.Clock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FilePersistenceTest {
  @TempDir Path directory;

  @Test
  void committedDataSurvivesClosingAndReopeningFileDatabase() {
    String url = "jdbc:h2:file:" + directory.resolve("delivery").toAbsolutePath();
    var source = new DriverManagerDataSource(url, "sa", "");
    Flyway.configure().dataSource(source).load().migrate();
    var jdbc = new JdbcTemplate(source);
    jdbc.update(
        "INSERT INTO users(username,password_hash,role,display_name) VALUES"
            + " ('alice','hash','CUSTOMER','Alice')");
    DeliveryStore first =
        new JdbcDeliveryStore(jdbc, new DataSourceTransactionManager(source), Clock.systemUTC());
    long customer = first.findAccountByUsername("alice").orElseThrow().id();
    var saved =
        first.createRequest(
            new RequestDraft(
                customer,
                "persist",
                new RequestDetails(
                    "Alice",
                    "0400000000",
                    "Pickup",
                    "Destination",
                    "Bob",
                    "0400000001",
                    "Box",
                    "")));
    jdbc.execute("SHUTDOWN");
    var reopenedSource = new DriverManagerDataSource(url, "sa", "");
    var reopenedJdbc = new JdbcTemplate(reopenedSource);
    var reopened =
        new JdbcDeliveryStore(
            reopenedJdbc, new DataSourceTransactionManager(reopenedSource), Clock.systemUTC());
    assertThat(reopened.findRequest(saved.id())).contains(saved);
    assertThat(reopened.findAccountByUsername("alice").orElseThrow().id()).isEqualTo(customer);
    Flyway.configure().dataSource(reopenedSource).load().migrate();
    assertThat(reopenedJdbc.queryForObject("SELECT COUNT(*) FROM delivery_requests", Integer.class))
        .isEqualTo(1);
    reopenedJdbc.execute("SHUTDOWN");
  }
}
