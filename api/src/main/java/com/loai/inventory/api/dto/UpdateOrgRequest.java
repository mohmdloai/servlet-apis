package com.loai.inventory.api.dto;

import java.math.BigDecimal;

public class UpdateOrgRequest {
  private String name;
  private BigDecimal refundApprovalThreshold;

  public UpdateOrgRequest() {}

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
}
