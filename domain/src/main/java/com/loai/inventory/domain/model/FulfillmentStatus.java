package com.loai.inventory.domain.model;

/**
 * Lifecycle of a {@link Fulfillment}. Online flow: PENDING → SHIPPED → DELIVERED, with CANCELLED
 * (only before SHIPPED) and FAILED (only after SHIPPED) branches. In-store flow creates a row
 * directly as DELIVERED. See {@code sys-analysis/outbound/fulfillment.md}. This slice implements
 * the PENDING → SHIPPED transition only.
 */
public enum FulfillmentStatus {
  PENDING,
  SHIPPED,
  DELIVERED,
  CANCELLED,
  FAILED
}
