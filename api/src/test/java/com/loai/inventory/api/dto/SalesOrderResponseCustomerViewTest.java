package com.loai.inventory.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.loai.inventory.domain.model.CouponType;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.SalesOrder;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The anonymous magic-link route must not leak staff-facing {@code notes} — nor, by the same rule,
 * the walk-in contact snapshot (V87) or the counter-discount block (V88, which names a staff user
 * id). {@link SalesOrderResponse#forCustomerView} withholds them; {@link SalesOrderResponse#from}
 * (authenticated view) keeps them. {@code discount_total} itself stays public on both.
 */
class SalesOrderResponseCustomerViewTest {

  private static SalesOrder walkInOrder() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    SalesOrder o =
        SalesOrder.createDraft(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            "SO-2026-00002",
            OrderChannel.IN_STORE,
            "EGP",
            "idem-key",
            now);
    o.setWalkInContact("Mohamed gamal", "01006123584");
    return o;
  }

  private static final UUID MANAGER = UUID.randomUUID();

  private static SalesOrder discountedOrder() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    SalesOrder o =
        SalesOrder.createDraft(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            "SO-2026-00003",
            OrderChannel.IN_STORE,
            "EGP",
            "idem-key",
            now);
    o.setTotals(
        new BigDecimal("300.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("30.00"), now);
    o.applyCounterDiscount(CouponType.PERCENT, new BigDecimal("10"), "damaged box", MANAGER, now);
    return o;
  }

  private static SalesOrder orderWithNotes(String notes) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    SalesOrder o =
        SalesOrder.createDraft(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "SO-2026-00001",
            OrderChannel.ONLINE,
            "EGP",
            "idem-key",
            now);
    o.updateNotes(notes, now);
    return o;
  }

  @Test
  void customerView_omitsNotes() {
    SalesOrder order = orderWithNotes("FRAUD RISK — call before shipping");
    SalesOrderResponse view = SalesOrderResponse.forCustomerView(order, List.of());
    assertNull(view.getNotes(), "internal notes must not reach the anonymous customer view");
  }

  @Test
  void authenticatedView_keepsNotes() {
    SalesOrder order = orderWithNotes("FRAUD RISK — call before shipping");
    SalesOrderResponse view = SalesOrderResponse.from(order, List.of());
    assertEquals("FRAUD RISK — call before shipping", view.getNotes());
  }

  @Test
  void customerView_omitsWalkInContact() {
    SalesOrderResponse view = SalesOrderResponse.forCustomerView(walkInOrder(), List.of());
    assertNull(view.getCustomerName(), "walk-in name is an internal contact field");
    assertNull(view.getCustomerPhone(), "walk-in phone is an internal contact field");
  }

  @Test
  void authenticatedView_keepsWalkInContact() {
    SalesOrderResponse view = SalesOrderResponse.from(walkInOrder(), List.of());
    assertEquals("Mohamed gamal", view.getCustomerName());
    assertEquals("01006123584", view.getCustomerPhone());
  }

  @Test
  void customerView_omitsCounterDiscount() {
    SalesOrderResponse view = SalesOrderResponse.forCustomerView(discountedOrder(), List.of());
    assertNull(view.getCounterDiscount(), "the block names a staff user id");
    // The money itself is not a secret — the shopper's total already shows it.
    assertEquals(0, new BigDecimal("30.00").compareTo(view.getDiscountTotal()));
    assertEquals(0, new BigDecimal("270.00").compareTo(view.getGrandTotal()));
  }

  @Test
  void authenticatedView_keepsCounterDiscount() {
    SalesOrderResponse view = SalesOrderResponse.from(discountedOrder(), List.of());
    SalesOrderResponse.CounterDiscount d = view.getCounterDiscount();
    assertEquals(CouponType.PERCENT, d.type());
    assertEquals(0, new BigDecimal("10").compareTo(d.value()));
    assertEquals("damaged box", d.reason());
    assertEquals(MANAGER, d.by());
  }

  @Test
  void authenticatedView_omitsTheBlockWhenThereWasNoDiscount() {
    SalesOrderResponse view = SalesOrderResponse.from(walkInOrder(), List.of());
    assertNull(view.getCounterDiscount(), "a plain sale carries no block (Jackson drops nulls)");
  }
}
