package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public class Org {
  private UUID id;
  private String name;
  private String slug;
  private boolean active;
  private BigDecimal refundApprovalThreshold;
  private int orderTtlMinutes;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public Org() {}

  public Org(
      UUID id,
      String name,
      String slug,
      boolean active,
      BigDecimal refundApprovalThreshold,
      int orderTtlMinutes,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.name = name;
    this.slug = slug;
    this.active = active;
    this.refundApprovalThreshold = refundApprovalThreshold;
    this.orderTtlMinutes = orderTtlMinutes;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getSlug() {
    return slug;
  }

  public void setSlug(String slug) {
    this.slug = slug;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public BigDecimal getRefundApprovalThreshold() {
    return refundApprovalThreshold;
  }

  public void setRefundApprovalThreshold(BigDecimal refundApprovalThreshold) {
    this.refundApprovalThreshold = refundApprovalThreshold;
  }

  /** Payment-hold window in minutes for reserved online/phone orders (V47, default 1440). */
  public int getOrderTtlMinutes() {
    return orderTtlMinutes;
  }

  public void setOrderTtlMinutes(int orderTtlMinutes) {
    this.orderTtlMinutes = orderTtlMinutes;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  @Override
  public String toString() {
    return "Org{id=" + id + ", slug='" + slug + "'}";
  }
}
