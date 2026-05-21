package com.loai.inventory.domain.model;

public enum OrderStatus {
  DRAFT,
  PENDING_PAYMENT,
  PAID,
  FULFILLING,
  FULFILLED,
  CLOSED,
  CANCELLED,
  EXPIRED
}
