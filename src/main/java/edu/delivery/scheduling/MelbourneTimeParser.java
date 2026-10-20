package edu.delivery.scheduling;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

public final class MelbourneTimeParser {
  private static final ZoneId MELBOURNE = ZoneId.of("Australia/Melbourne");

  private MelbourneTimeParser() {}

  public static OffsetDateTime parse(String value) {
    try {
      LocalDateTime local = LocalDateTime.parse(value);
      var offsets = MELBOURNE.getRules().getValidOffsets(local);
      if (offsets.size() != 1) {
        throw new IllegalArgumentException("Choose a valid, unambiguous Melbourne local time.");
      }
      return OffsetDateTime.of(local, offsets.get(0));
    } catch (DateTimeParseException | NullPointerException ex) {
      throw new IllegalArgumentException("Enter a valid date and time.", ex);
    }
  }
}
