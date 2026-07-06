package com.loai.inventory.api.dto;

import java.math.BigDecimal;

/**
 * Body for {@code PATCH /api/admin/orgs/{orgId}} (admin-plane org edit, divergence #3). {@code
 * name} is required; the policy knobs are optional - a {@code null} leaves that knob unchanged.
 */
public class AdminUpdateOrgRequest {
  private String name;
  private BigDecimal refundApprovalThreshold;
  private Integer orderTtlMinutes;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public BigDecimal getRefundApprovalThreshold() {
    return refundApprovalThreshold;
  }

  public void setRefundApprovalThreshold(BigDecimal refundApprovalThreshold) {
    this.refundApprovalThreshold = refundApprovalThreshold;
  }

  public Integer getOrderTtlMinutes() {
    return orderTtlMinutes;
  }

  public void setOrderTtlMinutes(Integer orderTtlMinutes) {
    this.orderTtlMinutes = orderTtlMinutes;
  }
}
