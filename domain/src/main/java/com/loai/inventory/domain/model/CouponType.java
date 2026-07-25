package com.loai.inventory.domain.model;

/**
 * How a coupon's {@code value} turns into money (roadmap item 9, {@code
 * stories/honest_coupons.md}). Both compute off the goods <b>subtotal</b> — pre-tax, excluding
 * shipping — so the discount is a reduction on the merchandise, never a rebate of the tax the
 * merchant owes or of a courier's fee.
 *
 * <ul>
 *   <li>{@link #PERCENT} — {@code round(subtotal × value/100, HALF_EVEN)}; {@code value} ∈ (0,
 *       100].
 *   <li>{@link #FIXED} — {@code min(value, subtotal)}: an EGP amount, capped so a generous code on
 *       a small basket can never produce a negative subtotal.
 * </ul>
 *
 * Immutable once a coupon exists: orders froze the arithmetic. Changing a live code's type or value
 * would silently rewrite what a shopper agreed to, so the admin plane refuses it — you make a new
 * code instead.
 */
public enum CouponType {
  PERCENT,
  FIXED
}
