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
  private BigDecimal discountTotal;
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
    SalesOrderResponse r = new SalesOrderResponse();
    r.id = order.getId();
    r.orgId = order.getOrgId();
    r.customerId = order.getCustomerId();
    r.orderNumber = order.getOrderNumber();
    r.channel = order.getChannel();
    r.status = order.getStatus();
    r.subtotal = order.getSubtotal();
    r.taxTotal = order.getTaxTotal();
    r.discountTotal = order.getDiscountTotal();
    r.grandTotal = order.getGrandTotal();
    r.currency = order.getCurrency();
    r.prepaidAmount = order.getPrepaidAmount();
    r.placedAt = order.getPlacedAt();
    r.expiresAt = order.getExpiresAt();
    r.createdAt = order.getCreatedAt();
    r.updatedAt = order.getUpdatedAt();
    r.notes = order.getNotes();
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

  public BigDecimal getDiscountTotal() {
    return discountTotal;
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
