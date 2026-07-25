package com.loai.inventory.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Body for {@code POST /coupons} (roadmap item 9). {@code code}, {@code type} and {@code value} are
 * required and — once created — immutable, because orders freeze that arithmetic. Everything else
 * is an optional constraint: a window, a minimum basket, a redemption cap. Validation (percent ≤
 * 100, value > 0, sane window, duplicate code) is the service's job.
 */
public class CreateCouponRequest {
  private String code;
  private String type;
  private BigDecimal value;
  private BigDecimal minSubtotal;
  private OffsetDateTime startsAt;
  private OffsetDateTime expiresAt;
  private Integer maxRedemptions;

  public CreateCouponRequest() {}

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public BigDecimal getValue() {
    return value;
  }

  public void setValue(BigDecimal value) {
    this.value = value;
  }

  public BigDecimal getMinSubtotal() {
    return minSubtotal;
  }

  public void setMinSubtotal(BigDecimal minSubtotal) {
    this.minSubtotal = minSubtotal;
  }

  public OffsetDateTime getStartsAt() {
    return startsAt;
  }

  public void setStartsAt(OffsetDateTime startsAt) {
    this.startsAt = startsAt;
  }

  public OffsetDateTime getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(OffsetDateTime expiresAt) {
    this.expiresAt = expiresAt;
  }

  public Integer getMaxRedemptions() {
    return maxRedemptions;
  }

  public void setMaxRedemptions(Integer maxRedemptions) {
    this.maxRedemptions = maxRedemptions;
  }
}
