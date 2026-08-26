package com.loai.inventory.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.SalesOrder;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The anonymous magic-link route must not leak staff-facing {@code notes} — nor, by the same rule,
 * the walk-in contact snapshot (V87). {@link SalesOrderResponse#forCustomerView} withholds them;
 * {@link SalesOrderResponse#from} (authenticated view) keeps them.
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
}
