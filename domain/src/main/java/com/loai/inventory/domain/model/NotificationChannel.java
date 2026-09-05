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
  WHATSAPP("whatsapp"),

  /**
   * Web Push (V96, {@code stories/web_push_channel.md}) — the first channel a <b>staff</b>
   * recipient has besides the in-app feed. Conditional like WhatsApp: it exists for a notification
   * only when the user has at least one <em>live</em> {@code push_subscription} (one whose {@code
   * token_version_at_subscribe} still equals {@code app_user.token_version}), and it is the one
   * channel with <b>one delivery row per device</b> rather than per channel — V96 relaxes the
   * {@code (notification_id, channel)} unique to exclude it. Push accelerates; the feed row and its
   * poll still own delivery.
   */
  PUSH("push");

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
