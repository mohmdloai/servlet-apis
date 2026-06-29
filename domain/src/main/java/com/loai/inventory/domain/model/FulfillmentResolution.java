package com.loai.inventory.domain.model;

/**
 * How a FAILED fulfillment was resolved. A failure is resolved at most once, so this gates the two
 * mutually exclusive paths: {@link #REFUNDED} (direct payment-backed refund of the fulfillment's
 * value) or {@link #REPLACED} (a new Fulfillment re-ships the goods, funded by the surviving
 * prepayment). See {@code sys-analysis/outbound/fulfillment.md} §FAILED.
 */
public enum FulfillmentResolution {
  REFUNDED,
  REPLACED
}
