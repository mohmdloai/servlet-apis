package com.loai.inventory.domain.model;

import java.util.UUID;

/**
 * Polymorphic notification target — a {@code USER} (an {@code app_user}) or a {@code CUSTOMER} (a
 * CRM record). Exactly one id is set; the static factories are the only way to build one, so the
 * invariant the DB enforces via {@code notification_recipient_ck} holds in code too.
 */
public record NotificationRecipient(RecipientType type, UUID userId, UUID customerId) {

  public static NotificationRecipient user(UUID userId) {
    if (userId == null) {
      throw new IllegalArgumentException("userId required");
    }
    return new NotificationRecipient(RecipientType.USER, userId, null);
  }

  public static NotificationRecipient customer(UUID customerId) {
    if (customerId == null) {
      throw new IllegalArgumentException("customerId required");
    }
    return new NotificationRecipient(RecipientType.CUSTOMER, null, customerId);
  }
}
