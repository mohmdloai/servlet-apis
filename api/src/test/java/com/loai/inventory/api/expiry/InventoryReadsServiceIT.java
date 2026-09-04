package com.loai.inventory.api.expiry;

import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.InventoryLog;
import com.loai.inventory.domain.model.InventoryStockFilter;
import com.loai.inventory.domain.model.ReservationStatus;
import com.loai.inventory.domain.model.StockReason;
import com.loai.inventory.domain.repository.InventoryRepository;
import com.loai.inventory.domain.repository.InventoryReservationRepository.ProductReservationRow;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.service.InventoryService;
import com.loai.inventory.service.InventoryService.LogPage;
import com.loai.inventory.service.InventoryService.OrderReservations;
import com.loai.inventory.service.InventoryService.OverviewPage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Integration coverage for the inventory read queries ({@code stories/inventory_reads.md}) against
 * real PostgreSQL: the product-driven overview LEFT JOIN + filters, the paginated ledger with
 * batch-loaded order numbers, and the order/product reservation reads. Reuses {@link
 * ExpiryIntegrationTestBase} for the container + seeding helpers (and its ground-truth invariant).
 */
class InventoryReadsServiceIT extends ExpiryIntegrationTestBase {

  private InventoryService service() {
    return new InventoryService(
        dsl,
        inventoryRepoFactory,
        inventoryLogRepoFactory,
        reservationRepoFactory,
        new ProductRepositoryImpl(dsl),
        salesOrderRepoFactory);
  }

  // overview list

  @Test
  void overview_isProductDriven_untrackedAppearsWithNullStock() {
    UUID org = createOrg("ov");
    UUID zebra = createProduct(org, "Zebra");
    UUID apple = createProduct(org, "Apple"); // untracked — no inventory row
    createInventory(org, zebra, 8);

    OverviewPage page = service().listOverview(org, null, null, null, 0, 20);

    assertEquals(2, page.total());
    // name ASC: Apple before Zebra.
    InventoryRepository.OverviewRow first = page.rows().get(0);
    assertEquals(apple, first.productId());
    assertFalse(first.tracked(), "untracked product surfaces as a row");
    assertNull(first.stockQty(), "untracked stock is null");
    assertNull(first.availableQty());

    InventoryRepository.OverviewRow second = page.rows().get(1);
    assertEquals(zebra, second.productId());
    assertTrue(second.tracked());
    assertEquals(8, second.stockQty());
    assertEquals(8, second.availableQty());
  }

  @Test
  void overview_stockFilters_partitionTheCatalog() {
    UUID org = createOrg("filters");
    UUID healthy = createProduct(org, "Healthy");
    UUID out = createProduct(org, "OutOfStock");
    UUID low = createProduct(org, "LowStock");
    UUID untracked = createProduct(org, "Untracked");
    createInventory(org, healthy, 100);
    createInventory(org, out, 0); // available 0 → OUT
    createInventory(org, low, 3); // available 3 → LOW at lowLte>=3

    assertEquals(
        List.of(untracked),
        ids(service().listOverview(org, null, InventoryStockFilter.UNTRACKED, null, 0, 20)));
    assertEquals(
        List.of(out),
        ids(service().listOverview(org, null, InventoryStockFilter.OUT, null, 0, 20)));
    // tracked = the three with inventory rows (name ASC: Healthy, LowStock, OutOfStock).
    assertEquals(
        List.of(healthy, low, out),
        ids(service().listOverview(org, null, InventoryStockFilter.TRACKED, null, 0, 20)));
    // LOW with an explicit bound of 3 catches LowStock (3) and OutOfStock (0), not Healthy (100).
    assertEquals(
        List.of(low, out),
        ids(service().listOverview(org, null, InventoryStockFilter.LOW, 3, 0, 20)));
  }

  @Test
  void overview_lowDefaultsBoundToFive_andQMatchesNameOrSku() {
    UUID org = createOrg("lowq");
    UUID widget = createProduct(org, "BlueWidget");
    UUID gadget = createProduct(org, "RedGadget");
    createInventory(org, widget, 4); // available 4 <= default 5 → LOW
    createInventory(org, gadget, 50);

    // stock=low with no bound → default 5.
    assertEquals(
        List.of(widget),
        ids(service().listOverview(org, null, InventoryStockFilter.LOW, null, 0, 20)));
    // q is a case-insensitive substring on name.
    assertEquals(List.of(widget), ids(service().listOverview(org, "widget", null, null, 0, 20)));
  }

  // movement ledger

  @Test
  void log_newestFirst_withBatchLoadedOrderNumber_andNullForNonOrderRows() {
    UUID org = createOrg("log");
    UUID product = createProduct(org, "Logged");
    createInventory(org, product, 100);
    UUID order =
        seedOrder(
            org, OrderStatus.PENDING_PAYMENT, minutesAhead(60), List.of(new Line(product, 2)));

    var logRepo = inventoryLogRepoFactory.create(dsl);
    // Oldest first insert; the read must return them newest-first.
    logRepo.insert(org, product, 10, 0, 110, 0, StockReason.RESTOCK, null, null);
    logRepo.insert(org, product, 0, 2, 110, 2, StockReason.RESERVED, order, null);

    LogPage page = service().listLog(org, product, 0, 20);

    assertEquals(2, page.total());
    InventoryLog newest = page.logs().get(0);
    assertEquals(StockReason.RESERVED, newest.getReason(), "newest first: the RESERVED row leads");
    assertEquals(order, newest.getOrderId());
    assertEquals("SO-1", page.orderNumbers().get(order), "order number batch-loaded");

    InventoryLog oldest = page.logs().get(1);
    assertEquals(StockReason.RESTOCK, oldest.getReason());
    assertNull(oldest.getOrderId(), "restock row has no order");
  }

  @Test
  void log_untrackedProductWithNoRows_isEmptyNot404_butMissingProductIs404() {
    UUID org = createOrg("logempty");
    UUID product = createProduct(org, "NeverMoved"); // exists, no inventory, no log rows

    assertEquals(0, service().listLog(org, product, 0, 20).total());

    UUID ghost = UUID.randomUUID();
    assertThrows(NotFoundException.class, () -> service().listLog(org, ghost, 0, 20));
  }

  // order reservations

  @Test
  void orderReservations_returnsHeaderAllStatusesAndProductNames() {
    UUID org = createOrg("orderres");
    UUID product = createProduct(org, "Widget");
    createInventory(org, product, 100);
    UUID order =
        seedOrder(
            org,
            OrderStatus.FULFILLING,
            minutesAhead(60),
            List.of(new Line(product, 2), new Line(product, 3)));

    OrderReservations result = service().listOrderReservations(org, order);

    assertEquals(order, result.order().getId());
    assertEquals(2, result.reservations().size());
    assertEquals("Widget", result.productNames().get(product));
    assertTrue(
        result.reservations().stream().allMatch(r -> r.getStatus() == ReservationStatus.ACTIVE));

    UUID ghost = UUID.randomUUID();
    assertThrows(NotFoundException.class, () -> service().listOrderReservations(org, ghost));
  }

  // per-product reservations

  @Test
  void productReservations_carryOrderContext_andFilterByStatus() {
    UUID org = createOrg("prodres");
    UUID product = createProduct(org, "Widget");
    createInventory(org, product, 100);
    UUID orderA =
        seedOrder(
            org, OrderStatus.PENDING_PAYMENT, minutesAhead(60), List.of(new Line(product, 2)));
    UUID orderB =
        seedOrder(
            org, OrderStatus.PENDING_PAYMENT, minutesAhead(60), List.of(new Line(product, 4)));

    List<ProductReservationRow> active =
        service().listProductReservations(org, product, ReservationStatus.ACTIVE);

    assertEquals(2, active.size(), "both orders' ACTIVE holds on this product");
    assertTrue(active.stream().anyMatch(r -> r.salesOrderId().equals(orderA)));
    assertTrue(active.stream().anyMatch(r -> r.salesOrderId().equals(orderB)));
    assertTrue(active.stream().allMatch(r -> r.salesOrderNumber().startsWith("SO-")));

    // Sum of ACTIVE quantities equals reserved_qty (the invariant the UI leans on) for order-placed
    // holds — 2 + 4 = 6.
    int activeSum = active.stream().mapToInt(r -> r.reservation().getQuantity()).sum();
    assertEquals(reservedQty(org, product), activeSum);

    // No RELEASED holds yet.
    assertTrue(
        service().listProductReservations(org, product, ReservationStatus.RELEASED).isEmpty());

    UUID ghost = UUID.randomUUID();
    assertThrows(
        NotFoundException.class,
        () -> service().listProductReservations(org, ghost, ReservationStatus.ACTIVE));
  }

  // stories/reorder_point.md — the replenishment worklist is one more filter value

  @Test
  void overview_reorder_selectsTrackedRowsAtOrBelowTheirOwnPoint_deepestFirst() {
    UUID org = createOrg("reorder");
    UUID fine = createProduct(org, "Fine"); // 10 on hand, point 5 → above
    UUID atPoint = createProduct(org, "AtPoint"); // 5 on hand, point 5 → in (diff 0)
    UUID deep = createProduct(org, "Deep"); // 1 on hand, point 8 → in (diff −7)
    UUID mid = createProduct(org, "Mid"); // 2 on hand, point 5 → in (diff −3)
    UUID noRule = createProduct(org, "NoRule"); // 0 on hand, no point → out of the list
    UUID untracked = createProduct(org, "Untracked"); // point 5, no inventory → out
    UUID reservedHeavy = createProduct(org, "Held"); // 10 on hand, 8 reserved, point 5 → in (2)
    setReorderPoint(fine, 5);
    setReorderPoint(atPoint, 5);
    setReorderPoint(deep, 8);
    setReorderPoint(mid, 5);
    setReorderPoint(untracked, 5);
    setReorderPoint(reservedHeavy, 5);
    createInventory(org, fine, 10);
    createInventory(org, atPoint, 5);
    createInventory(org, deep, 1);
    createInventory(org, mid, 2);
    createInventory(org, noRule, 0);
    createInventory(org, reservedHeavy, 10);
    // A real hold (reservation rows + reserved_qty), so the ground-truth invariant stays true.
    seedOrder(
        org, OrderStatus.PENDING_PAYMENT, minutesAhead(60), List.of(new Line(reservedHeavy, 8)));

    OverviewPage page =
        service().listOverview(org, null, InventoryStockFilter.REORDER, null, 0, 20);

    // Deepest below its own point first: Deep (−7), Mid and Held (−3, name ASC), AtPoint (0).
    assertEquals(List.of(deep, reservedHeavy, mid, atPoint), ids(page));
    assertEquals(
        4, service().listOverview(org, null, InventoryStockFilter.REORDER, null, 0, 20).total());
    // Every row carries the point; the compare is on available, not on hand.
    assertEquals(Integer.valueOf(8), page.rows().get(0).reorderPoint());
    assertEquals(Integer.valueOf(2), page.rows().get(1).availableQty());
    // low_lte belongs to LOW and is ignored here; q composes.
    assertEquals(
        List.of(deep, reservedHeavy, mid, atPoint),
        ids(service().listOverview(org, null, InventoryStockFilter.REORDER, 100, 0, 20)));
    assertEquals(
        List.of(mid),
        ids(service().listOverview(org, "mid", InventoryStockFilter.REORDER, null, 0, 20)));
    // Unfiltered rows carry the point too (null when none), in catalog order.
    OverviewPage all = service().listOverview(org, null, null, null, 0, 20);
    assertEquals(
        Integer.valueOf(5),
        all.rows().stream()
            .filter(r -> r.productId().equals(untracked))
            .findFirst()
            .get()
            .reorderPoint());
    assertEquals(
        null,
        all.rows().stream()
            .filter(r -> r.productId().equals(noRule))
            .findFirst()
            .get()
            .reorderPoint());
  }

  private void setReorderPoint(UUID productId, int point) {
    dsl.update(PRODUCT).set(PRODUCT.REORDER_POINT, point).where(PRODUCT.ID.eq(productId)).execute();
  }

  private static List<UUID> ids(OverviewPage page) {
    return page.rows().stream().map(InventoryRepository.OverviewRow::productId).toList();
  }
}
