package com.loai.inventory.api.dto;

import java.time.OffsetDateTime;

/**
 * Body for {@code PATCH /coupons/{id}} (roadmap item 9) — the three mutable knobs only. A null
 * field is leave-unchanged. {@code code}, {@code type} and {@code value} are absent by design: an
 * order that redeemed the code froze its arithmetic, so editing it would rewrite what a shopper
 * already agreed to. Make a new code instead.
 */
public class PatchCouponRequest {
  private Boolean active;
  private OffsetDateTime expiresAt;
  private Integer maxRedemptions;

  public PatchCouponRequest() {}

  public Boolean getActive() {
    return active;
  }

  public void setActive(Boolean active) {
    this.active = active;
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
