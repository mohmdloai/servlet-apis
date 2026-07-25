package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public class SalesOrderResponse {
  private UUID id;
  private UUID orgId;
  private UUID customerId;
  private String orderNumber;
  private OrderChannel channel;
  private OrderStatus status;
  private BigDecimal subtotal;
  private BigDecimal taxTotal;
  private BigDecimal shippingTotal;
  private BigDecimal discountTotal;

  /** The frozen coupon code this order redeemed (roadmap item 9), or null. */
  private String couponCode;

  private BigDecimal grandTotal;
  private String currency;
  private BigDecimal prepaidAmount;
  private OffsetDateTime placedAt;
  private OffsetDateTime expiresAt;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private String notes;
  private List<SalesOrderLineResponse> lines;

  private SalesOrderResponse() {}

  public static SalesOrderResponse from(SalesOrder order, List<SalesOrderLine> lines) {
    return build(order, lines, /* includeInternal= */ true);
  }

  /**
   * The order as shown to the customer over the anonymous magic-link route ({@code
   * /api/public/orders/{token}}). Excludes staff-facing fields: {@code notes} is entered by staff
   * on a STAFF-gated placement endpoint and may hold internal/operational text, so it is withheld
   * from an unauthenticated audience. (A proper customer_note / internal_note split is the durable
   * fix — tracked as follow-up.)
   */
  public static SalesOrderResponse forCustomerView(SalesOrder order, List<SalesOrderLine> lines) {
    return build(order, lines, /* includeInternal= */ false);
  }

  private static SalesOrderResponse build(
      SalesOrder order, List<SalesOrderLine> lines, boolean includeInternal) {
    SalesOrderResponse r = new SalesOrderResponse();
    r.id = order.getId();
    r.orgId = order.getOrgId();
    r.customerId = order.getCustomerId();
    r.orderNumber = order.getOrderNumber();
    r.channel = order.getChannel();
    r.status = order.getStatus();
    r.subtotal = order.getSubtotal();
    r.taxTotal = order.getTaxTotal();
    r.shippingTotal = order.getShippingTotal();
    r.discountTotal = order.getDiscountTotal();
    r.couponCode = order.getCouponCode();
    r.grandTotal = order.getGrandTotal();
    r.currency = order.getCurrency();
    r.prepaidAmount = order.getPrepaidAmount();
    r.placedAt = order.getPlacedAt();
    r.expiresAt = order.getExpiresAt();
    r.createdAt = order.getCreatedAt();
    r.updatedAt = order.getUpdatedAt();
    // notes is staff-facing — omit it from the customer view (Jackson drops nulls).
    r.notes = includeInternal ? order.getNotes() : null;
    r.lines = lines.stream().map(SalesOrderLineResponse::from).toList();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public String getOrderNumber() {
    return orderNumber;
  }

  public OrderChannel getChannel() {
    return channel;
  }

  public OrderStatus getStatus() {
    return status;
  }

  public BigDecimal getSubtotal() {
    return subtotal;
  }

  public BigDecimal getTaxTotal() {
    return taxTotal;
  }

  public BigDecimal getShippingTotal() {
    return shippingTotal;
  }

  public BigDecimal getDiscountTotal() {
    return discountTotal;
  }

  public String getCouponCode() {
    return couponCode;
  }

  public BigDecimal getGrandTotal() {
    return grandTotal;
  }

  public String getCurrency() {
    return currency;
  }

  public BigDecimal getPrepaidAmount() {
    return prepaidAmount;
  }

  public OffsetDateTime getPlacedAt() {
    return placedAt;
  }

  public OffsetDateTime getExpiresAt() {
    return expiresAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public String getNotes() {
    return notes;
  }

  public List<SalesOrderLineResponse> getLines() {
    return lines;
  }
}
