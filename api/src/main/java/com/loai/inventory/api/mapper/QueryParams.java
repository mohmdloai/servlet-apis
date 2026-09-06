package com.loai.inventory.api.mapper;

import com.loai.inventory.common.exception.ValidationException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * The worklist query-string vocabulary, parsed one way for every list ({@code /invoices}, {@code
 * /inventory}, {@code /sales-orders}): blank is absent, a malformed value is a 400 that names the
 * parameter, and the caller composes the cross-field rules ({@code from < to}, {@code min <= max}).
 */
final class QueryParams {

  private QueryParams() {}

  /** An ISO-8601 date-time (the {@code /reports} convention) — a bare date is a 400. */
  static OffsetDateTime parseTs(String name, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new ValidationException("'" + name + "' must be an ISO-8601 date-time");
    }
  }

  /** A non-negative decimal amount. */
  static BigDecimal parseMoney(String name, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    BigDecimal parsed;
    try {
      parsed = new BigDecimal(value.trim());
    } catch (NumberFormatException e) {
      throw new ValidationException("'" + name + "' must be a number");
    }
    if (parsed.signum() < 0) {
      throw new ValidationException("'" + name + "' must not be negative");
    }
    return parsed;
  }

  /** A whitelisted enum value, case-insensitive; the 400 names the allowed set. */
  static <E extends Enum<E>> E parseEnum(String name, String value, Class<E> type, String allowed) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ValidationException("'" + name + "' must be one of: " + allowed);
    }
  }
}
