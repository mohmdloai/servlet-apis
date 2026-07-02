package com.loai.inventory.domain.model;

/**
 * Who a {@link Notification} targets. A CUSTOMER is not an {@code app_user} (CRM-only, no auth).
 */
public enum RecipientType {
  USER,
  CUSTOMER
}
