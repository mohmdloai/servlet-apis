package com.loai.inventory.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The anonymous order-view magic link ({@code GET /api/public/orders/{token}}) must be
 * customer-safe: {@link PublicOrderResponse#forOrderView} carries no internal id, {@code
 * product_id}, {@code prepaid_amount}, {@code channel}, or {@code created_at}/{@code updated_at} —
 * the same whitelist the checkout confirmation upholds. Serialized with the app's SNAKE_CASE mapper
 * so the assertion is over the real wire shape.
 */
class PublicOrderResponseForOrderViewTest {

  private static final ObjectMapper MAPPER = ObjectMapperProvider.build();

  private static final UUID PRODUCT_ID = UUID.randomUUID();

  private static SalesOrder draftOrder() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    SalesOrder o =
        SalesOrder.createDraft(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "SO-2026-00013",
            OrderChannel.ONLINE,
            "EGP",
            "idem-key",
            now);
    o.updateNotes("FRAUD RISK — call before shipping", now);
    return o;
  }

  private static SalesOrderLine line() {
    return SalesOrderLine.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        PRODUCT_ID,
        "Eva",
        1,
        new BigDecimal("60.00"),
        BigDecimal.ZERO);
  }

  @Test
  void orderView_isWhitelisted_noInternalIds() throws Exception {
    PublicOrderResponse view = PublicOrderResponse.forOrderView(draftOrder(), List.of(line()));
    String json = MAPPER.writeValueAsString(view);

    // No internal id / cross-tenant / accounting field ever reaches the anonymous customer.
    for (String forbidden :
        List.of(
            "\"id\"",
            "\"org_id\"",
            "\"customer_id\"",
            "\"product_id\"",
            "\"prepaid_amount\"",
            "\"channel\"",
            "\"created_at\"",
            "\"updated_at\"",
            "\"notes\"")) {
      assertFalse(json.contains(forbidden), "leaked " + forbidden + " in: " + json);
    }
  }

  @Test
  void orderView_carriesTheCustomerFacingFields() throws Exception {
    PublicOrderResponse view = PublicOrderResponse.forOrderView(draftOrder(), List.of(line()));
    String json = MAPPER.writeValueAsString(view);

    assertTrue(json.contains("\"order_number\":\"SO-2026-00013\""), json);
    assertTrue(json.contains("\"status\""), json);
    // The line is labelled with its placement-time description, not the product id.
    assertEquals("Eva", view.getLines().get(0).getTitle());
    assertTrue(json.contains("\"title\":\"Eva\""), json);
  }
}
