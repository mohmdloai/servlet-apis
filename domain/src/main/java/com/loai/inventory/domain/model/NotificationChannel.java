package com.loai.inventory.domain.model;

/**
 * Delivery channel. The DB stores the lower-case literal ({@code in_app}, {@code email}); {@link
 * #dbValue()} / {@link #fromDbValue(String)} bridge the enum and that literal.
 */
public enum NotificationChannel {
  IN_APP("in_app"),
  EMAIL("email"),

  /**
   * WhatsApp Cloud API (V82, slice B) — the reach channel for a market where email is largely
   * unread. Unlike its two siblings this one is <b>conditional</b>: it exists for a notification
   * only when the org has connected an ACTIVE WhatsApp Business Account <em>and</em> the customer
   * has a dialable {@code phone_e164} (V79) <em>and</em> the event has an approved utility
   * template. Any of those missing is a suppressed channel, never an error.
   */
  WHATSAPP("whatsapp");

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
