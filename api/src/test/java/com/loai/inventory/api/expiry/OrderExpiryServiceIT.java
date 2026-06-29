package com.loai.inventory.api.expiry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.repository.InventoryReservationRepository;
import com.loai.inventory.domain.repository.InventoryReservationRepositoryFactory;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.service.OrderExpiryService;
import com.loai.inventory.service.OrderExpiryService.Summary;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

/** Acceptance scenarios for the order-TTL sweeper. The ground-truth invariant runs after each. */
class OrderExpiryServiceIT extends ExpiryIntegrationTestBase {

  @Test
  void happyPath_expiresReleasesDecrementsAndLogs() {
    UUID org = createOrg("happy");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 100);
    UUID order =
        seedOrder(org, OrderStatus.PENDING_PAYMENT, minutesAgo(1), List.of(new Line(product, 5)));

    Summary summary = expiryService.sweep(200);

    assertEquals(new Summary(1, 1, 1, 1, 0), summary);
    assertEquals("EXPIRED", orderStatus(order));
    assertNotNull(orderExpiredAt(order), "expired_at must be set");
    assertEquals(0, reservedQty(org, product), "reserved_qty decremented to 0");
    assertEquals(100, stockQty(org, product), "stock_qty unchanged");
    assertEquals(1L, inventoryVersion(org, product), "version incremented exactly once");
    assertEquals(0L, activeReservationCount(order));
    assertEquals(1L, releasedReservationCount(order));
    assertReleasedReason(order, "EXPIRED");

    List<Record> logs = logRowsForOrder(order);
    assertEquals(1, logs.size(), "one inventory_log row per affected product");
    Record logRow = logs.get(0);
    assertEquals(0, ((Number) logRow.get("stock_delta")).intValue());
    assertEquals(-5, ((Number) logRow.get("reserved_delta")).intValue());
    assertEquals(100, ((Number) logRow.get("stock_after")).intValue());
    assertEquals(0, ((Number) logRow.get("reserved_after")).intValue());
    assertEquals("RELEASED", logRow.get("reason"));
    assertEquals("order-ttl-sweeper", logRow.get("actor_id"));
    assertEquals("SYSTEM", logRow.get("actor_type"));
  }

  @Test
  void multiLineSameSku_oneLogRowOneDecrementTwoReservationsReleased() {
    UUID org = createOrg("multi");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 100);
    UUID order =
        seedOrder(
            org,
            OrderStatus.PENDING_PAYMENT,
            minutesAgo(1),
            List.of(new Line(product, 3), new Line(product, 2)));

    Summary summary = expiryService.sweep(200);

    assertEquals(1, summary.ordersExpired());
    assertEquals(2, summary.reservationsReleased(), "both reservation rows released");
    assertEquals(1, summary.productsAffected(), "same SKU collapses to one product");
    assertEquals(0, reservedQty(org, product), "single 5-unit decrement");
    assertEquals(1, logRowsForOrder(order).size(), "exactly one log row");
    assertEquals(2L, releasedReservationCount(order));
  }

  @Test
  void ghostOrder_flipsCleanlyWithNoReservationsAndNoLogRow() {
    UUID org = createOrg("ghost");
    UUID order = seedOrder(org, OrderStatus.PENDING_PAYMENT, minutesAgo(1), List.of());

    Summary summary = expiryService.sweep(200);

    assertEquals("EXPIRED", orderStatus(order));
    assertNotNull(orderExpiredAt(order));
    assertEquals(1, summary.ordersExpired());
    assertEquals(0, summary.reservationsReleased());
    assertEquals(0, summary.productsAffected());
    assertTrue(logRowsForOrder(order).isEmpty(), "no inventory_log row for a ghost order");
  }

  @Test
  void siblingAlreadyWon_paidOrderIsNeverScannedOrTouched() {
    UUID org = createOrg("paid");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 100);
    UUID order = seedOrder(org, OrderStatus.PAID, minutesAgo(1), List.of(new Line(product, 4)));

    Summary summary = expiryService.sweep(200);

    assertEquals(0, summary.candidatesScanned(), "PAID order is not a candidate");
    assertEquals(0, summary.ordersExpired());
    assertEquals("PAID", orderStatus(order));
    assertEquals(1L, activeReservationCount(order), "reservations stay ACTIVE");
    assertEquals(0L, releasedReservationCount(order));
    assertEquals(4, reservedQty(org, product), "reserved_qty untouched");
    assertTrue(logRowsForOrder(order).isEmpty());
  }

  @Test
  void notYetExpired_isNotSwept() {
    UUID org = createOrg("future");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 100);
    UUID order =
        seedOrder(
            org, OrderStatus.PENDING_PAYMENT, minutesAhead(10), List.of(new Line(product, 2)));

    Summary summary = expiryService.sweep(200);

    assertEquals(0, summary.candidatesScanned());
    assertEquals("PENDING_PAYMENT", orderStatus(order));
    assertEquals(2, reservedQty(org, product));
  }

  @Test
  void poisonPill_oneFailingOrderDoesNotRollBackTheHealthyPeers() {
    UUID org = createOrg("poison");
    // Five expired PENDING orders, each on its own product, distinct expires_at for stable order.
    UUID[] orders = new UUID[5];
    UUID[] products = new UUID[5];
    for (int i = 0; i < 5; i++) {
      products[i] = createProduct(org, "SKU" + i);
      createInventory(org, products[i], 100);
      orders[i] =
          seedOrder(
              org,
              OrderStatus.PENDING_PAYMENT,
              minutesAgo(10 - i),
              List.of(new Line(products[i], 1)));
    }
    UUID poisonOrder = orders[2];

    // Decorating factory: throw inside the per-order txn (after the status flip) for the poison
    // order only, delegating everything else to the real repository.
    InventoryReservationRepositoryFactory poisonFactory =
        ctx -> {
          InventoryReservationRepository real = reservationRepoFactory.create(ctx);
          return new InventoryReservationRepository() {
            @Override
            public void insertAll(List<com.loai.inventory.domain.model.InventoryReservation> rs) {
              real.insertAll(rs);
            }

            @Override
            public List<com.loai.inventory.domain.model.InventoryReservation> findBySalesOrderId(
                UUID id) {
              return real.findBySalesOrderId(id);
            }

            @Override
            public List<com.loai.inventory.domain.model.InventoryReservation> findActiveByOrderId(
                UUID id) {
              if (id.equals(poisonOrder)) {
                throw new IllegalStateException("injected poison for order " + id);
              }
              return real.findActiveByOrderId(id);
            }

            @Override
            public List<com.loai.inventory.domain.model.InventoryReservation> findByIds(
                java.util.Collection<UUID> ids) {
              return real.findByIds(ids);
            }

            @Override
            public int markReleased(
                UUID orgId, java.util.Collection<UUID> ids, String reason, OffsetDateTime now) {
              return real.markReleased(orgId, ids, reason, now);
            }

            @Override
            public int markConsumed(java.util.Collection<UUID> ids, OffsetDateTime now) {
              return real.markConsumed(ids, now);
            }
          };
        };

    OrderExpiryService poisoned =
        new OrderExpiryService(
            dsl,
            salesOrderRepoFactory,
            new com.loai.inventory.service.ReservationService(
                inventoryRepoFactory, poisonFactory, inventoryLogRepoFactory));

    Summary summary = poisoned.sweep(200);

    assertEquals(5, summary.candidatesScanned());
    assertEquals(4, summary.ordersExpired(), "the 4 healthy orders committed");
    assertEquals(1, summary.orderFailures(), "the poison order failed in isolation");

    for (int i = 0; i < 5; i++) {
      if (i == 2) {
        assertEquals("PENDING_PAYMENT", orderStatus(orders[i]), "poison order rolled back");
        assertEquals(1L, activeReservationCount(orders[i]), "poison reservations stay ACTIVE");
        assertEquals(1, reservedQty(org, products[i]), "poison reserved_qty untouched");
      } else {
        assertEquals("EXPIRED", orderStatus(orders[i]));
        assertEquals(1L, releasedReservationCount(orders[i]));
        assertEquals(0, reservedQty(org, products[i]));
      }
    }
  }

  @Test
  void expireOnePending_isIdempotentOnAlreadyTerminalOrder() {
    UUID org = createOrg("idem");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 100);
    UUID order =
        seedOrder(org, OrderStatus.PENDING_PAYMENT, minutesAgo(1), List.of(new Line(product, 7)));

    assertEquals(1, expiryService.expireOnePending(order), "first call flips");
    assertEquals(0, expiryService.expireOnePending(order), "second call is a noop");
    assertEquals(0, reservedQty(org, product), "no double decrement");
    assertEquals(1L, inventoryVersion(org, product), "still exactly one increment");
  }
}
