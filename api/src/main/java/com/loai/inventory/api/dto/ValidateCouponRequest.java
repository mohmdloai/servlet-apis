package com.loai.inventory.api.dto;

import java.math.BigDecimal;

/**
 * Body for the anonymous pre-checkout preview {@code POST /api/public/{orgSlug}/coupons/validate}
 * (roadmap item 9): the code the shopper typed and the goods {@code subtotal} in decimal EGP (the
 * wire form — the same as every other public money field).
 */
public class ValidateCouponRequest {
  private String code;
  private BigDecimal subtotal;

  public ValidateCouponRequest() {}

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public BigDecimal getSubtotal() {
    return subtotal;
  }

  public void setSubtotal(BigDecimal subtotal) {
    this.subtotal = subtotal;
  }
}
