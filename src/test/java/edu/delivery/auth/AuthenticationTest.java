package edu.delivery.auth;

import static edu.delivery.contract.DeliveryData.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.delivery.contract.DeliveryStore;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:auth;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AuthenticationTest {
  @Autowired MockMvc mvc;
  @Autowired DeliveryStore store;
  @Autowired AccessGuard access;
  @Autowired PasswordEncoder encoder;
  @Autowired DemoDataInitializer initializer;
  static final String PASSWORD = "Group5-Demo-2026!";

  MockHttpSession login(String username) throws Exception {
    return (MockHttpSession)
        mvc.perform(formLogin().user(username).password(PASSWORD))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"))
            .andReturn()
            .getRequest()
            .getSession(false);
  }

  @Test
  void loginPageRendersAndContainsCsrf() throws Exception {
    mvc.perform(get("/login"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("_csrf")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/form-validation.js")));
    mvc.perform(get("/js/form-validation.js"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Please fill out this field.")));
    mvc.perform(get("/")).andExpect(status().is3xxRedirection());
  }

  @Test
  void credentialsAreHashedAndSeedIsIdempotent() throws Exception {
    var before = store.findAccountByUsername("customer.alice").orElseThrow();
    assertThat(before.passwordHash()).isNotEqualTo(PASSWORD);
    assertThat(encoder.matches(PASSWORD, before.passwordHash())).isTrue();
    initializer.run(null);
    assertThat(store.findAccountByUsername("customer.alice")).contains(before);
    assertThat(store.findActiveStaff()).hasSize(2);
  }

  @Test
  void successfulSessionDoesNotExposePassword() throws Exception {
    var session = login("customer.alice");
    mvc.perform(get("/api/session").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("customer.alice"))
        .andExpect(jsonPath("$.role").value("CUSTOMER"))
        .andExpect(jsonPath("$.passwordHash").doesNotExist())
        .andExpect(jsonPath("$.password").doesNotExist());
    mvc.perform(get("/").session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Alice Customer")));
  }

  @Test
  void invalidPasswordAndUnknownAccountFailIdentically() throws Exception {
    mvc.perform(formLogin().user("customer.alice").password("wrong"))
        .andExpect(redirectedUrl("/login?error"));
    mvc.perform(formLogin().user("nobody").password("wrong"))
        .andExpect(redirectedUrl("/login?error"));
    mvc.perform(formLogin().user("' OR 1=1 --").password("wrong"))
        .andExpect(redirectedUrl("/login?error"));
  }

  @Test
  void roleRoutesAndDefaultDenyAreEnforced() throws Exception {
    var customer = login("customer.alice");
    var office = login("office.demo");
    var driver = login("driver.li");
    mvc.perform(get("/portal/customer").session(customer)).andExpect(status().isOk());
    mvc.perform(get("/portal/office").session(customer)).andExpect(status().isForbidden());
    mvc.perform(get("/portal/office").session(office)).andExpect(status().isOk());
    mvc.perform(get("/portal/driver").session(driver)).andExpect(status().isOk());
    mvc.perform(get("/requests").session(driver)).andExpect(status().isForbidden());
    mvc.perform(get("/scheduling").session(customer)).andExpect(status().isForbidden());
    mvc.perform(get("/unknown-admin-route").session(customer)).andExpect(status().isForbidden());
  }

  @Test
  void csrfAndLogoutAreEnforced() throws Exception {
    var session = login("customer.alice");
    mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
    mvc.perform(get("/api/session").session(session)).andExpect(status().isOk());
    mvc.perform(post("/logout").session(session).with(csrf()))
        .andExpect(redirectedUrl("/login?logout"));
    assertThat(session.isInvalid()).isTrue();
  }

  @Test
  void recordOwnershipAndTaskAssignmentAreChecked() {
    var alice = store.findAccountByUsername("customer.alice").orElseThrow();
    var bob = store.findAccountByUsername("customer.bob").orElseThrow();
    var driver = store.findAccountByUsername("driver.li").orElseThrow();
    var staff =
        store.findActiveStaff().stream()
            .filter(s -> s.userId() == driver.id())
            .findFirst()
            .orElseThrow();
    var request =
        store.createRequest(
            new RequestDraft(
                alice.id(),
                "ownership",
                new RequestDetails(
                    "Alice",
                    "0400000000",
                    "Pickup",
                    "Destination",
                    "Bob",
                    "0400000001",
                    "Box",
                    "")));
    try {
      setPrincipal(alice);
      assertThatCode(() -> access.requireRequestRead(request.id())).doesNotThrowAnyException();
      setPrincipal(bob);
      assertThatThrownBy(() -> access.requireRequestRead(request.id()))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      setPrincipal(driver);
      assertThatThrownBy(() -> access.requireAssignedDelivery(request.id()))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      store.inTransaction(
          tx -> {
            tx.lockStaff(staff.id());
            tx.lockRequest(request.id());
            return tx.createSchedule(
                new ScheduleDraft(
                    request.id(),
                    staff.id(),
                    OffsetDateTime.now(),
                    OffsetDateTime.now().plusHours(1)));
          });
      assertThatCode(() -> access.requireAssignedDelivery(request.id())).doesNotThrowAnyException();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private void setPrincipal(AccountData account) {
    var p = new DeliveryPrincipal(account);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities()));
  }
}
