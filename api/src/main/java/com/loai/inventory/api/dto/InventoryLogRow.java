package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.InventoryLog;
import com.loai.inventory.domain.model.StockReason;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of a product's movement ledger ({@code GET /inventory/{productId}/log}): deltas
 * <b>and</b> running balances so a card can render honest absolute numbers. {@code order_id} is
 * null for non-order reasons ({@code RESTOCK} / {@code ADJUSTMENT}); {@code sales_order_number} is
 * the batch-loaded human-readable number (null when {@code order_id} is null). Since V102 a {@code
 * RESTOCK} row may instead name its delivery — {@code goods_receipt_id} + the batch-loaded {@code
 * goods_receipt_number}, {@code order_id}'s mirror, absent on a hand-keyed restock. {@code
 * impersonator_id} appears only when the actor was impersonated. There is no free-text note column
 * in this slice.
 */
public class InventoryLogRow {

  private Long id;
  private int stockDelta;
  private int reservedDelta;
  private int stockAfter;
  private int reservedAfter;
  private StockReason reason;
  private UUID orderId;
  private String salesOrderNumber;
  private UUID goodsReceiptId;
  private String goodsReceiptNumber;
  private String actorId;
  private ActorType actorType;
  private UUID impersonatorId;
  private OffsetDateTime createdAt;

  private InventoryLogRow() {}

  public static InventoryLogRow from(
      InventoryLog log, String salesOrderNumber, String goodsReceiptNumber) {
    InventoryLogRow r = new InventoryLogRow();
    r.id = log.getId();
    r.stockDelta = log.getStockDelta();
    r.reservedDelta = log.getReservedDelta();
    r.stockAfter = log.getStockAfter();
    r.reservedAfter = log.getReservedAfter();
    r.reason = log.getReason();
    r.orderId = log.getOrderId();
    r.salesOrderNumber = salesOrderNumber;
    r.goodsReceiptId = log.getGoodsReceiptId();
    r.goodsReceiptNumber = goodsReceiptNumber;
    r.actorId = log.getActorId();
    r.actorType = log.getActorType();
    r.impersonatorId = log.getImpersonatorId();
    r.createdAt = log.getCreatedAt();
    return r;
  }

  public Long getId() {
    return id;
  }

  public int getStockDelta() {
    return stockDelta;
  }

  public int getReservedDelta() {
    return reservedDelta;
  }

  public int getStockAfter() {
    return stockAfter;
  }

  public int getReservedAfter() {
    return reservedAfter;
  }

  public StockReason getReason() {
    return reason;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public String getSalesOrderNumber() {
    return salesOrderNumber;
  }

  public UUID getGoodsReceiptId() {
    return goodsReceiptId;
  }

  public String getGoodsReceiptNumber() {
    return goodsReceiptNumber;
  }

  public String getActorId() {
    return actorId;
  }

  public ActorType getActorType() {
    return actorType;
  }

  public UUID getImpersonatorId() {
    return impersonatorId;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
