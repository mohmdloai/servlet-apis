package com.loai.inventory.api.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.api.dto.PlaceSalesOrderRequest;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.CouponType;
import com.loai.inventory.service.SalesOrderService.DiscountInput;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The wire → service edge of the counter-discount block ({@code stories/counter_discount.md}). */
class SalesOrderMapperDiscountTest {

  private static PlaceSalesOrderRequest withDiscount(String type, String value, String reason) {
    PlaceSalesOrderRequest req = new PlaceSalesOrderRequest();
    PlaceSalesOrderRequest.DiscountPayload d = new PlaceSalesOrderRequest.DiscountPayload();
    d.setType(type);
    d.setValue(value == null ? null : new BigDecimal(value));
    d.setReason(reason);
    req.setDiscount(d);
    return req;
  }

  @Test
  void noBlock_isNull() {
    assertNull(SalesOrderMapper.toDiscountInput(new PlaceSalesOrderRequest()));
    assertNull(SalesOrderMapper.toDiscountInput(null));
  }

  @Test
  void parsesTypeCaseInsensitively_andCarriesValueAndReason() {
    DiscountInput d = SalesOrderMapper.toDiscountInput(withDiscount("percent", "10", "damaged"));
    assertEquals(CouponType.PERCENT, d.type());
    assertEquals(0, new BigDecimal("10").compareTo(d.value()));
    assertEquals("damaged", d.reason());
    assertEquals(
        CouponType.FIXED,
        SalesOrderMapper.toDiscountInput(withDiscount(" FIXED ", "5", null)).type());
  }

  @Test
  void unknownType_is400NamingTheTwo() {
    ValidationException e =
        assertThrows(
            ValidationException.class,
            () -> SalesOrderMapper.toDiscountInput(withDiscount("HALF", "10", null)));
    assertEquals("discount.type must be PERCENT or FIXED", e.getMessage());
  }

  /** A blank type is left null for the service's own cause-naming 400, not guessed. */
  @Test
  void blankType_isLeftNullForTheService() {
    assertNull(SalesOrderMapper.toDiscountInput(withDiscount("  ", "10", null)).type());
    assertNull(SalesOrderMapper.toDiscountInput(withDiscount(null, "10", null)).type());
  }
}
