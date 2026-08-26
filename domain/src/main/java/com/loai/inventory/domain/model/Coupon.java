package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A merchant-issued discount code (roadmap item 9, {@code stories/honest_coupons.md}). Org-scoped;
 * {@code code} is normalized UPPER and unique per org.
 *
 * <p>The eligibility window ({@code startsAt}/{@code expiresAt}), the {@code minSubtotal} floor and
 * the {@code active} flag are all carried here so the whole "may this apply?" question is
 * answerable from one row plus a redemption count — and the count is deliberately <b>not</b> a
 * column: it is a query over the orders that hold this coupon, so cancelling or expiring an order
 * frees its slot with nothing to reconcile.
 *
 * <p>{@code code}, {@code type} and {@code value} are immutable after creation — orders froze that
 * arithmetic (see {@link CouponType}).
 */
public class Coupon {

  private UUID id;
  private UUID orgId;
  private String code;
  private CouponType type;
  private BigDecimal value;
  private BigDecimal minSubtotal;
  private OffsetDateTime startsAt;
  private OffsetDateTime expiresAt;
  private Integer maxRedemptions;
  private boolean active;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public Coupon() {}

  public Coupon(
      UUID id,
      UUID orgId,
      String code,
      CouponType type,
      BigDecimal value,
      BigDecimal minSubtotal,
      OffsetDateTime startsAt,
      OffsetDateTime expiresAt,
      Integer maxRedemptions,
      boolean active,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.orgId = orgId;
    this.code = code;
    this.type = type;
    this.value = value;
    this.minSubtotal = minSubtotal;
    this.startsAt = startsAt;
    this.expiresAt = expiresAt;
    this.maxRedemptions = maxRedemptions;
    this.active = active;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  /**
   * The discount this coupon takes off {@code subtotal} — the load-bearing arithmetic. Kept on the
   * model so the checkout preview and the placement call the same thing and never disagree by a
   * piastre; the arithmetic itself lives in {@link DiscountMath} so the counter discount ({@code
   * stories/counter_discount.md}) shares it too — one implementation, three callers.
   */
  public BigDecimal discountFor(BigDecimal subtotal) {
    return DiscountMath.discountFor(type, value, subtotal);
  }

  /**
   * True when the coupon is active and {@code now} falls inside its window. Deliberately excludes
   * the {@code minSubtotal} and redemption-cap checks: those two carry different messages to the
   * shopper (one names the threshold, the other must not), so the service decides them separately.
   */
  public boolean isLiveAt(OffsetDateTime now) {
    if (!active) {
      return false;
    }
    if (startsAt != null && now.isBefore(startsAt)) {
      return false;
    }
    return expiresAt == null || !now.isAfter(expiresAt);
  }

  /** True when {@code subtotal} clears this coupon's minimum (no minimum → always true). */
  public boolean meetsMinimum(BigDecimal subtotal) {
    return minSubtotal == null || (subtotal != null && subtotal.compareTo(minSubtotal) >= 0);
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public void setOrgId(UUID orgId) {
    this.orgId = orgId;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public CouponType getType() {
    return type;
  }

  public void setType(CouponType type) {
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

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
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
    return "Coupon{id=" + id + ", orgId=" + orgId + ", code='" + code + "', type=" + type + "}";
  }
}
