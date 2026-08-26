package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one implementation of "how much does a {@link CouponType} discount take off a subtotal".
 *
 * <p>Extracted from {@code Coupon.discountFor} when the counter discount arrived ({@code
 * stories/counter_discount.md}): a "10% off" keyed by a manager at the till and a {@code SAVE10}
 * code typed by a shopper must produce the same piastres, and the only way to guarantee that over
 * time is for both to call the same function. {@link Coupon#discountFor} delegates here.
 *
 * <p>The rules, unchanged from the coupon slice ({@code stories/honest_coupons.md}):
 *
 * <ul>
 *   <li>{@code PERCENT} — {@code subtotal × value / 100}, rounded HALF_EVEN at scale 2 (money is
 *       scale-2 HALF_EVEN everywhere in this system; a discount is money).
 *   <li>{@code FIXED} — {@code value}, capped at the subtotal so the goods total can never go
 *       negative.
 *   <li>Neither returns more than {@code subtotal}; a null or non-positive subtotal yields zero.
 *       That is precisely the invariant {@code SalesOrder.setTotals} enforces on the other side.
 * </ul>
 */
public final class DiscountMath {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;
  private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

  private DiscountMath() {}

  public static BigDecimal discountFor(CouponType type, BigDecimal value, BigDecimal subtotal) {
    if (subtotal == null || subtotal.signum() <= 0) {
      return BigDecimal.ZERO.setScale(MONEY_SCALE);
    }
    if (type == null || value == null) {
      throw new IllegalArgumentException("discount type and value are required");
    }
    BigDecimal raw =
        type == CouponType.PERCENT
            ? subtotal.multiply(value).divide(ONE_HUNDRED, MONEY_SCALE, MONEY_ROUNDING)
            : value;
    return raw.min(subtotal).setScale(MONEY_SCALE, MONEY_ROUNDING);
  }
}
