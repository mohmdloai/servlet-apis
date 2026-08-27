package com.loai.inventory.api.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.api.dto.PlaceSalesOrderRequest;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.service.SalesOrderService.PaymentInput;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The tender block(s) of an in-store body ({@code stories/split_tender.md}). */
class SalesOrderMapperTendersTest {

  private static PlaceSalesOrderRequest.PaymentPayload tender(String provider, String amount) {
    PlaceSalesOrderRequest.PaymentPayload p = new PlaceSalesOrderRequest.PaymentPayload();
    p.setProvider(provider);
    p.setAmount(amount == null ? null : new BigDecimal(amount));
    return p;
  }

  @Test
  void singlePaymentBlock_isAOneElementList() {
    PlaceSalesOrderRequest req = new PlaceSalesOrderRequest();
    req.setPayment(tender("cash", null));
    List<PaymentInput> out = SalesOrderMapper.toPaymentInputs(req);
    assertEquals(1, out.size());
    assertEquals(PaymentProvider.CASH, out.get(0).provider());
    assertNull(out.get(0).amount());
  }

  @Test
  void paymentsList_mapsElementWise_inRequestOrder() {
    PlaceSalesOrderRequest req = new PlaceSalesOrderRequest();
    req.setPayments(List.of(tender("INSTAPAY_IN_STORE", "450.00"), tender("CASH", "100.00")));
    List<PaymentInput> out = SalesOrderMapper.toPaymentInputs(req);
    assertEquals(2, out.size());
    assertEquals(PaymentProvider.INSTAPAY_IN_STORE, out.get(0).provider());
    assertEquals(0, new BigDecimal("450.00").compareTo(out.get(0).amount()));
    assertEquals(PaymentProvider.CASH, out.get(1).provider());
  }

  @Test
  void bothBlocks_is400_neitherIsNull() {
    PlaceSalesOrderRequest both = new PlaceSalesOrderRequest();
    both.setPayment(tender("cash", null));
    both.setPayments(List.of(tender("cash", "1.00")));
    assertThrows(ValidationException.class, () -> SalesOrderMapper.toPaymentInputs(both));
    assertNull(SalesOrderMapper.toPaymentInputs(new PlaceSalesOrderRequest()));
  }

  @Test
  void unknownProvider_is400() {
    PlaceSalesOrderRequest req = new PlaceSalesOrderRequest();
    req.setPayments(List.of(tender("card", "1.00")));
    assertThrows(ValidationException.class, () -> SalesOrderMapper.toPaymentInputs(req));
  }
}
