package com.loai.inventory.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The discount arithmetic's first direct pins ({@code stories/counter_discount.md}). Until the
 * counter discount arrived this lived on {@code Coupon.discountFor} and was exercised only through
 * the checkout ITs; now {@link DiscountMath} is the one implementation and {@link Coupon} delegates
 * to it, so these cases cover the coupon path too — a "10% off" keyed at the till and a {@code
 * SAVE10} code must agree to the piastre.
 */
class DiscountMathTest {

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(bd(expected), actual, "expected " + expected + " but was " + actual);
  }

  @Test
  void percent_roundsHalfEvenAtScale2() {
    assertMoney("30.00", DiscountMath.discountFor(CouponType.PERCENT, bd("10"), bd("300.00")));
    assertMoney("3.30", DiscountMath.discountFor(CouponType.PERCENT, bd("33"), bd("10.00")));
    // 12.5% of 0.10 = 0.0125 — below the half-piastre, rounds down under any mode.
    assertMoney("0.01", DiscountMath.discountFor(CouponType.PERCENT, bd("12.5"), bd("0.10")));
    // 50% of 0.05 = 0.025 — an exact tie: HALF_EVEN keeps the even 2 (0.02); HALF_UP would say
    // 0.03. This is the case that tells the two modes apart.
    assertMoney("0.02", DiscountMath.discountFor(CouponType.PERCENT, bd("50"), bd("0.05")));
    // 50% of 0.15 = 0.075 — a tie whose even neighbour is above: 0.08.
    assertMoney("0.08", DiscountMath.discountFor(CouponType.PERCENT, bd("50"), bd("0.15")));
    // 12.5% of 0.30 = 0.0375 — above the half, rounds up to 0.04 under any mode.
    assertMoney("0.04", DiscountMath.discountFor(CouponType.PERCENT, bd("12.5"), bd("0.30")));
  }

  @Test
  void percentHundred_isTheWholeSubtotal() {
    assertMoney("270.00", DiscountMath.discountFor(CouponType.PERCENT, bd("100"), bd("270.00")));
  }

  @Test
  void fixed_isTheAmount_cappedAtTheSubtotal() {
    assertMoney("25.00", DiscountMath.discountFor(CouponType.FIXED, bd("25"), bd("100.00")));
    assertMoney("60.00", DiscountMath.discountFor(CouponType.FIXED, bd("100"), bd("60.00")));
    assertMoney("100.00", DiscountMath.discountFor(CouponType.FIXED, bd("100"), bd("100.00")));
  }

  @Test
  void result_isAlwaysScale2_evenFromAScale0Value() {
    BigDecimal d = DiscountMath.discountFor(CouponType.FIXED, bd("25"), bd("100.00"));
    assertEquals(2, d.scale());
    BigDecimal p = DiscountMath.discountFor(CouponType.PERCENT, bd("10"), bd("300"));
    assertEquals(2, p.scale());
  }

  @Test
  void nullOrNonPositiveSubtotal_isZero() {
    assertMoney("0.00", DiscountMath.discountFor(CouponType.PERCENT, bd("10"), null));
    assertMoney("0.00", DiscountMath.discountFor(CouponType.FIXED, bd("10"), BigDecimal.ZERO));
    assertMoney("0.00", DiscountMath.discountFor(CouponType.FIXED, bd("10"), bd("-1.00")));
  }

  @Test
  void missingTypeOrValue_isAProgrammerError() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DiscountMath.discountFor(null, bd("10"), bd("100.00")));
    assertThrows(
        IllegalArgumentException.class,
        () -> DiscountMath.discountFor(CouponType.PERCENT, null, bd("100.00")));
  }

  /** The delegation itself: a coupon and the helper cannot disagree, by construction. */
  @Test
  void coupon_delegatesToTheSameArithmetic() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Coupon percent =
        new Coupon(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "SAVE33",
            CouponType.PERCENT,
            bd("33"),
            null,
            null,
            null,
            null,
            true,
            now,
            now);
    Coupon fixed =
        new Coupon(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "FLAT100",
            CouponType.FIXED,
            bd("100"),
            null,
            null,
            null,
            null,
            true,
            now,
            now);
    for (String subtotal : new String[] {"10.00", "0.05", "0.10", "300.00", "60.00"}) {
      assertEquals(
          DiscountMath.discountFor(CouponType.PERCENT, bd("33"), bd(subtotal)),
          percent.discountFor(bd(subtotal)),
          "PERCENT on " + subtotal);
      assertEquals(
          DiscountMath.discountFor(CouponType.FIXED, bd("100"), bd(subtotal)),
          fixed.discountFor(bd(subtotal)),
          "FIXED on " + subtotal);
    }
  }
}
