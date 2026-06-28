package com.loai.inventory.api.dto;

import java.util.UUID;

/**
 * Request body for {@code POST /api/orgs/{orgId}/payment-transactions/{id}/resolve} — the
 * admin-chosen order to attach an ORPHAN transaction's payment to.
 *
 * <p>Jackson data carrier only (snake_case JSON ↔ camelCase Java via the global {@code
 * ObjectMapper}). Target the order by either {@code sales_order_id} or {@code order_number}; at
 * least one is required (both absent → 400 in the service).
 */
public class ResolveOrphanRequest {

  private UUID salesOrderId;
  private String orderNumber;

  public ResolveOrphanRequest() {}

  public UUID getSalesOrderId() {
    return salesOrderId;
  }

  public void setSalesOrderId(UUID salesOrderId) {
    this.salesOrderId = salesOrderId;
  }

  public String getOrderNumber() {
    return orderNumber;
  }

  public void setOrderNumber(String orderNumber) {
    this.orderNumber = orderNumber;
  }
}
