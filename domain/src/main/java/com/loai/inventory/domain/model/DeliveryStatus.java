package com.loai.inventory.domain.model;

/** Per-channel delivery lifecycle. SENT/DELIVERED/FAILED are terminal for dispatch purposes. */
public enum DeliveryStatus {
  PENDING,
  SENT,
  DELIVERED,
  FAILED
}
