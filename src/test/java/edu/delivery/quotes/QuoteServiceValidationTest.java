package edu.delivery.quotes;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuoteServiceValidationTest {
  private final Clock clock = Clock.fixed(
      OffsetDateTime.parse("2026-10-20T10:00:00+11:00").toInstant(), ZoneOffset.UTC);
  private final OffsetDateTime now = OffsetDateTime.now(clock);

  @Test void validQuote() {
    assertDoesNotThrow(() -> QuoteService.validateQuote(new BigDecimal("29.99"),
        now.plusDays(2), now.plusDays(3), now.plusHours(4), clock));
  }

  @Test void rejectsNegativeOrOverpreciseAmount() {
    assertThrows(IllegalArgumentException.class,
        () -> QuoteService.validateQuote(new BigDecimal("-1"),
            now.plusDays(2), now.plusDays(3), now.plusHours(4), clock));
    assertThrows(IllegalArgumentException.class,
        () -> QuoteService.validateQuote(new BigDecimal("2.999"),
            now.plusDays(2), now.plusDays(3), now.plusHours(4), clock));
  }

  @Test void rejectsInvalidDatesAndExpiredOffer() {
    assertThrows(IllegalArgumentException.class,
        () -> QuoteService.validateQuote(new BigDecimal("29"),
            now.plusDays(3), now.plusDays(2), now.plusHours(4), clock));
    assertThrows(IllegalArgumentException.class,
        () -> QuoteService.validateQuote(new BigDecimal("29"),
            now.plusDays(2), now.plusDays(3), now.minusMinutes(1), clock));
  }
}
