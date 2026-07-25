package com.loai.inventory.api.dto;

import com.loai.inventory.service.CouponService.Applied;
import java.math.BigDecimal;

/**
 * The preview answer (roadmap item 9): exactly the normalized {@code code} and the {@code discount}
 * it would take off the basket that was quoted. Nothing else crosses — not the coupon's id, not its
 * type or percent, not its cap or how many redemptions remain. A shopper needs to know what comes
 * off; the shape of the merchant's promotion is not theirs to enumerate, and a code endpoint that
 * described its own rules would be an enumeration surface.
 */
public class PublicCouponResponse {
  private String code;
  private BigDecimal discount;

  private PublicCouponResponse() {}

  public static PublicCouponResponse from(Applied applied) {
    PublicCouponResponse r = new PublicCouponResponse();
    r.code = applied.code();
    r.discount = applied.discount();
    return r;
  }

  public String getCode() {
    return code;
  }

  public BigDecimal getDiscount() {
    return discount;
  }
}
