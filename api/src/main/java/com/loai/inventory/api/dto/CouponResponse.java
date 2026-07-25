package com.loai.inventory.api.dto;

import com.loai.inventory.service.CouponService.CouponView;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The admin shape of a coupon (roadmap item 9), carrying its <b>live</b> {@code redemption_count} —
 * a query over the orders holding it, not a stored counter, so a cancelled or expired order is
 * already reflected here with nothing to reconcile.
 */
public class CouponResponse {
  private UUID id;
  private UUID orgId;
  private String code;
  private String type;
  private BigDecimal value;
  private BigDecimal minSubtotal;
  private OffsetDateTime startsAt;
  private OffsetDateTime expiresAt;
  private Integer maxRedemptions;
  private long redemptionCount;
  private boolean active;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  private CouponResponse() {}

  public static CouponResponse from(CouponView v) {
    CouponResponse r = new CouponResponse();
    r.id = v.coupon().getId();
    r.orgId = v.coupon().getOrgId();
    r.code = v.coupon().getCode();
    r.type = v.coupon().getType().name();
    r.value = v.coupon().getValue();
    r.minSubtotal = v.coupon().getMinSubtotal();
    r.startsAt = v.coupon().getStartsAt();
    r.expiresAt = v.coupon().getExpiresAt();
    r.maxRedemptions = v.coupon().getMaxRedemptions();
    r.redemptionCount = v.redemptionCount();
    r.active = v.coupon().isActive();
    r.createdAt = v.coupon().getCreatedAt();
    r.updatedAt = v.coupon().getUpdatedAt();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public String getCode() {
    return code;
  }

  public String getType() {
    return type;
  }

  public BigDecimal getValue() {
    return value;
  }

  public BigDecimal getMinSubtotal() {
    return minSubtotal;
  }

  public OffsetDateTime getStartsAt() {
    return startsAt;
  }

  public OffsetDateTime getExpiresAt() {
    return expiresAt;
  }

  public Integer getMaxRedemptions() {
    return maxRedemptions;
  }

  public long getRedemptionCount() {
    return redemptionCount;
  }

  public boolean isActive() {
    return active;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
