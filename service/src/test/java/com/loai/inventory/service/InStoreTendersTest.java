package com.loai.inventory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.service.SalesOrderService.PaymentInput;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link SalesOrderService#normaliseTenders} — rules 3 and 4 of {@code stories/split_tender.md}:
 * cash folds into one, InstaPay first in request order, cash last, ordinals only where two
 * synthesised InstaPay refs would collide.
 */
class InStoreTendersTest {

  private static final PaymentProvider IP = PaymentProvider.INSTAPAY_IN_STORE;
  private static final PaymentProvider CASH = PaymentProvider.CASH;

  private static PaymentInput t(PaymentProvider p, String amount, String ref) {
    return new PaymentInput(p, ref, new BigDecimal(amount));
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected);
  }

  @Test
  void foldsCashIntoOne_summingAmounts_keepingTheFirstRealRef() {
    List<PaymentInput> out =
        SalesOrderService.normaliseTenders(
            List.of(t(CASH, "100.00", null), t(CASH, "200.00", "DRAWER-7"), t(IP, "200.00", "X")),
            "k");
    assertEquals(2, out.size());
    assertEquals(IP, out.get(0).provider());
    assertEquals("X", out.get(0).providerRef());
    assertEquals(CASH, out.get(1).provider());
    assertMoney("300.00", out.get(1).amount());
    assertEquals("DRAWER-7", out.get(1).providerRef());
  }

  @Test
  void ordersInstaPayFirstInRequestOrder_cashLast() {
    List<PaymentInput> out =
        SalesOrderService.normaliseTenders(
            List.of(t(CASH, "100.00", null), t(IP, "450.00", "A"), t(IP, "50.00", "B")), "k");
    assertEquals(List.of("A", "B"), List.of(out.get(0).providerRef(), out.get(1).providerRef()));
    assertEquals(CASH, out.get(2).provider());
    assertNull(
        out.get(2).providerRef(), "a refless cash tender keeps null → CASH-<idem> downstream");
  }

  @Test
  void reflessInstaPay_getsOrdinals_onlyWhenMoreThanOneInstaPayTender() {
    List<PaymentInput> two =
        SalesOrderService.normaliseTenders(
            List.of(t(IP, "300.00", null), t(IP, "200.00", null), t(CASH, "10.00", null)), "k");
    assertEquals("INSTAPAY_IN_STORE-k-1", two.get(0).providerRef());
    assertEquals("INSTAPAY_IN_STORE-k-2", two.get(1).providerRef());

    List<PaymentInput> mixed =
        SalesOrderService.normaliseTenders(
            List.of(t(IP, "300.00", "A"), t(IP, "200.00", " ")), "k");
    assertEquals("A", mixed.get(0).providerRef());
    assertEquals("INSTAPAY_IN_STORE-k-2", mixed.get(1).providerRef(), "ordinal = position");

    List<PaymentInput> lone =
        SalesOrderService.normaliseTenders(
            List.of(t(IP, "400.00", null), t(CASH, "100.00", null)), "k");
    assertNull(lone.get(0).providerRef(), "a lone InstaPay tender keeps today's synthesised ref");

    List<PaymentInput> single =
        SalesOrderService.normaliseTenders(List.of(t(IP, "500.00", null)), "k");
    assertEquals(1, single.size());
    assertNull(single.get(0).providerRef());
  }

  @Test
  void cashOnly_isOneCashTender() {
    List<PaymentInput> out =
        SalesOrderService.normaliseTenders(
            List.of(t(CASH, "20.00", null), t(CASH, "30.00", null)), "k");
    assertEquals(1, out.size());
    assertEquals(CASH, out.get(0).provider());
    assertMoney("50.00", out.get(0).amount());
  }
}
