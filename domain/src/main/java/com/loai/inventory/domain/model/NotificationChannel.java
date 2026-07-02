package com.loai.inventory.domain.model;

/**
 * Delivery channel. The DB stores the lower-case literal ({@code in_app}, {@code email}); {@link
 * #dbValue()} / {@link #fromDbValue(String)} bridge the enum and that literal.
 */
public enum NotificationChannel {
  IN_APP("in_app"),
  EMAIL("email");

  private final String dbValue;

  NotificationChannel(String dbValue) {
    this.dbValue = dbValue;
  }

  public String dbValue() {
    return dbValue;
  }

  public static NotificationChannel fromDbValue(String v) {
    for (NotificationChannel c : values()) {
      if (c.dbValue.equals(v)) {
        return c;
      }
    }
    throw new IllegalArgumentException("Unknown notification channel: " + v);
  }
}
