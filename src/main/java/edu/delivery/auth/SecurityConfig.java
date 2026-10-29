package edu.delivery.auth;

import edu.delivery.contract.DeliveryStore;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  UserDetailsService userDetailsService(DeliveryStore store) {
    return username ->
        store
            .findAccountByUsername(username)
            .map(DeliveryPrincipal::new)
            .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
        auth ->
            auth.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD)
                .permitAll()
                .requestMatchers("/login", "/css/**", "/error")
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/", "/api/session")
                .authenticated()
                .requestMatchers("/portal/customer")
                .hasRole("CUSTOMER")
                .requestMatchers("/portal/office")
                .hasRole("OFFICE_STAFF")
                .requestMatchers("/portal/driver")
                .hasRole("DELIVERY_STAFF")
                .requestMatchers(
                    "/requests/**", "/quotes/**", "/tracking/**", "/schedule-changes/**")
                .hasAnyRole("CUSTOMER", "OFFICE_STAFF")
                .requestMatchers("/scheduling/**")
                .hasRole("OFFICE_STAFF")
                .requestMatchers("/availability/**", "/delivery/**")
                .hasAnyRole("OFFICE_STAFF", "DELIVERY_STAFF")
                .anyRequest()
                .denyAll());
    http.formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true).permitAll());
    http.logout(
        logout ->
            logout
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"));
    http.sessionManagement(
        session -> session.sessionFixation(fixation -> fixation.migrateSession()));
    http.headers(
        headers ->
            headers.contentSecurityPolicy(
                policy ->
                    policy.policyDirectives(
                        "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self'"
                            + " data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none';"
                            + " form-action 'self'")));
    // CSRF protection remains enabled. All forms must include the CSRF token.
    return http.build();
  }
}
