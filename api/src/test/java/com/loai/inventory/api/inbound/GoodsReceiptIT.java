package com.loai.inventory.api.inbound;

import static com.loai.inventory.repository.generated.Tables.GOODS_RECEIPT;
import static com.loai.inventory.repository.generated.Tables.GOODS_RECEIPT_NUMBER_COUNTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.GoodsReceiptListFilter;
import com.loai.inventory.domain.model.GoodsReceiptStatus;
import com.loai.inventory.domain.model.Supplier;
import com.loai.inventory.repository.generated.tables.records.InventoryLogRecord;
import com.loai.inventory.service.GoodsReceiptService.LineCommand;
import com.loai.inventory.service.GoodsReceiptService.ReceiptCommand;
import com.loai.inventory.service.GoodsReceiptService.ReceiptView;
import com.loai.inventory.service.SupplierService.SupplierEdit;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Posting a delivery ({@code stories/supplier_goods_receipt.md}): stock, the movement's cost and
 * the product's standing cost all move off one document, in one transaction, at most once per key.
 */
@Testcontainers
class GoodsReceiptIT extends InboundItBase {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  @BeforeAll
  static void startInfra() {
    wire(PG);
  }

  @Test
  void twoLineReceipt_movesBothStocks_andLogsTheLineCostNotTheProductsStandingOne() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", "4.00", 10, 0);
    UUID pad = createTrackedProduct(org, "PAD", null, 2, 0);

    ReceiptView view =
        receipts.record(
            org,
            delivery(zaki.getId(), line(pen, 24, "12.50"), line(pad, 3, "40.00")),
            "k-1",
            actor(user),
            user);

    assertFalse(view.replayed());
    assertMoney("420.00", view.receipt().getTotalCost()); // 24 × 12.50 + 3 × 40.00
    assertEquals(34, stockOf(pen));
    assertEquals(5, stockOf(pad));

    InventoryLogRecord penRow = logRows(pen).get(0);
    assertEquals("RESTOCK", penRow.getReason().getLiteral(), "no new stock_reason, by design");
    assertEquals(24, penRow.getStockDelta());
    assertMoney("12.50", penRow.getUnitCost()); // NOT the product's standing 4.00
    assertEquals(view.receipt().getId(), penRow.getGoodsReceiptId());
    assertNull(penRow.getOrderId(), "a receipt is not order-linked");

    InventoryLogRecord padRow = logRows(pad).get(0);
    assertMoney("40.00", padRow.getUnitCost(), "an uncosted product is costed by the note");
    assertEquals(view.receipt().getId(), padRow.getGoodsReceiptId());
  }

  @Test
  void receipt_raisesTheProductsCostToTheLineCost_lastCostNotAverage() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", "4.00", 10, 0);

    receipts.record(org, delivery(zaki.getId(), line(pen, 10, "12.50")), "k-1", actor(user), user);
    assertMoney("12.50", costOf(pen));

    receipts.record(org, delivery(zaki.getId(), line(pen, 10, "9.00")), "k-2", actor(user), user);
    assertMoney("9.00", costOf(pen), "last cost, not the 10.75 average");
  }

  @Test
  void numberAllocation_isPerOrgPerYear_andFormatted() {
    UUID orgA = createOrg("acme");
    UUID orgB = createOrg("beta");
    UUID user = createUser("owner@acme.test");
    Supplier a = suppliers.create(orgA, named("Zaki"));
    Supplier b = suppliers.create(orgB, named("Zaki"));
    UUID penA = createTrackedProduct(orgA, "PEN", null, 0, 0);
    UUID penB = createTrackedProduct(orgB, "PEN", null, 0, 0);
    int year = OffsetDateTime.now(ZoneOffset.UTC).getYear();

    String first =
        receipts
            .record(orgA, delivery(a.getId(), line(penA, 1, "1.00")), "a1", actor(user), user)
            .receipt()
            .getReceiptNumber();
    String second =
        receipts
            .record(orgA, delivery(a.getId(), line(penA, 1, "1.00")), "a2", actor(user), user)
            .receipt()
            .getReceiptNumber();
    String otherOrg =
        receipts
            .record(orgB, delivery(b.getId(), line(penB, 1, "1.00")), "b1", actor(user), user)
            .receipt()
            .getReceiptNumber();

    assertEquals("GRN-" + year + "-00001", first);
    assertEquals("GRN-" + year + "-00002", second);
    assertEquals("GRN-" + year + "-00001", otherOrg, "counters are per org");
  }

  @Test
  void replayOfTheKey_returnsThePriorReceipt_andWritesNothing() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", null, 10, 0);
    ReceiptCommand cmd = delivery(zaki.getId(), line(pen, 5, "3.00"));

    ReceiptView first = receipts.record(org, cmd, "k-1", actor(user), user);
    ReceiptView replay = receipts.record(org, cmd, "k-1", actor(user), user);

    assertTrue(replay.replayed());
    assertEquals(first.receipt().getId(), replay.receipt().getId());
    assertEquals(15, stockOf(pen), "the +5 applied once");
    assertEquals(1, logRows(pen).size());
    assertEquals(1, dsl.fetchCount(dsl.selectFrom(GOODS_RECEIPT)));
  }

  @Test
  void sameKeyDifferentBody_is409() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", null, 10, 0);
    receipts.record(org, delivery(zaki.getId(), line(pen, 5, "3.00")), "k-1", actor(user), user);

    ConflictException e =
        assertThrows(
            ConflictException.class,
            () ->
                receipts.record(
                    org, delivery(zaki.getId(), line(pen, 6, "3.00")), "k-1", actor(user), user));

    assertTrue(e.getMessage().contains("different parameters"), e.getMessage());
    assertEquals(15, stockOf(pen));
  }

  @Test
  void missingKey_is400() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", null, 10, 0);

    assertThrows(
        ValidationException.class,
        () ->
            receipts.record(
                org, delivery(zaki.getId(), line(pen, 5, "3.00")), "  ", actor(user), user));
  }

  @Test
  void untrackedProduct_is409_withNothingWritten_andTheKeyStillFree() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID tracked = createTrackedProduct(org, "PEN", null, 10, 0);
    UUID untracked = createProduct(org, "PAD", null);

    ConflictException e =
        assertThrows(
            ConflictException.class,
            () ->
                receipts.record(
                    org,
                    delivery(zaki.getId(), line(tracked, 5, "3.00"), line(untracked, 1, "2.00")),
                    "k-1",
                    actor(user),
                    user));

    assertTrue(e.getMessage().contains("has no inventory record"), e.getMessage());
    assertEquals(10, stockOf(tracked), "the first line rolled back with the rest");
    assertEquals(0, dsl.fetchCount(dsl.selectFrom(GOODS_RECEIPT)));

    // The key was never claimed: the same key retries cleanly once the product is initialised.
    dsl.execute(
        "INSERT INTO inventory (org_id, product_id, stock_qty, reserved_qty) VALUES (?, ?, 0, 0)",
        org,
        untracked);
    ReceiptView retry =
        receipts.record(
            org,
            delivery(zaki.getId(), line(tracked, 5, "3.00"), line(untracked, 1, "2.00")),
            "k-1",
            actor(user),
            user);
    assertFalse(retry.replayed());
  }

  @Test
  void rolledBackReceipt_burnsNoNumber() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID tracked = createTrackedProduct(org, "PEN", null, 10, 0);
    UUID untracked = createProduct(org, "PAD", null);

    assertThrows(
        ConflictException.class,
        () ->
            receipts.record(
                org,
                delivery(zaki.getId(), line(untracked, 1, "2.00")),
                "k-doomed",
                actor(user),
                user));

    assertEquals(
        0,
        dsl.fetchCount(dsl.selectFrom(GOODS_RECEIPT_NUMBER_COUNTER)),
        "the counter row rolled back with the transaction");
    assertEquals(
        "GRN-" + OffsetDateTime.now(ZoneOffset.UTC).getYear() + "-00001",
        receipts
            .record(
                org, delivery(zaki.getId(), line(tracked, 1, "2.00")), "k-ok", actor(user), user)
            .receipt()
            .getReceiptNumber());
  }

  @Test
  void supplier_unknownIs404_foreignIs404_inactiveIs409() {
    UUID org = createOrg("acme");
    UUID other = createOrg("beta");
    UUID user = createUser("owner@acme.test");
    UUID pen = createTrackedProduct(org, "PEN", null, 10, 0);
    Supplier foreign = suppliers.create(other, named("Elsewhere"));
    Supplier retired = suppliers.create(org, named("Retired"));
    suppliers.update(
        org, retired.getId(), new SupplierEdit(null, null, null, null, null, Boolean.FALSE));

    assertThrows(
        NotFoundException.class,
        () ->
            receipts.record(
                org, delivery(UUID.randomUUID(), line(pen, 1, "1.00")), "k1", actor(user), user));
    assertThrows(
        NotFoundException.class,
        () ->
            receipts.record(
                org, delivery(foreign.getId(), line(pen, 1, "1.00")), "k2", actor(user), user));
    ConflictException e =
        assertThrows(
            ConflictException.class,
            () ->
                receipts.record(
                    org, delivery(retired.getId(), line(pen, 1, "1.00")), "k3", actor(user), user));
    assertTrue(e.getMessage().contains("Retired"), e.getMessage());
  }

  @Test
  void badLines_are400_duplicateProduct_nonPositiveQty_negativeCost_futureDate() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", null, 10, 0);

    assertThrows(
        ValidationException.class,
        () ->
            receipts.record(
                org,
                delivery(zaki.getId(), line(pen, 1, "1.00"), line(pen, 2, "2.00")),
                "k1",
                actor(user),
                user));
    assertThrows(
        ValidationException.class,
        () ->
            receipts.record(
                org, delivery(zaki.getId(), line(pen, 0, "1.00")), "k2", actor(user), user));
    assertThrows(
        ValidationException.class,
        () ->
            receipts.record(
                org, delivery(zaki.getId(), line(pen, 1, "-1.00")), "k3", actor(user), user));
    assertThrows(
        ValidationException.class,
        () ->
            receipts.record(
                org,
                new ReceiptCommand(
                    zaki.getId(),
                    OffsetDateTime.now(ZoneOffset.UTC).plusDays(1),
                    null,
                    null,
                    List.of(line(pen, 1, "1.00"))),
                "k4",
                actor(user),
                user));
    assertThrows(
        ValidationException.class,
        () ->
            receipts.record(
                org,
                new ReceiptCommand(zaki.getId(), null, null, null, List.of()),
                "k5",
                actor(user),
                user));
  }

  @Test
  void freeOfChargeLine_postsStockAtZeroCost() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", "4.00", 10, 0);

    ReceiptView view =
        receipts.record(
            org, delivery(zaki.getId(), line(pen, 6, "0.00")), "k-1", actor(user), user);

    assertEquals(16, stockOf(pen));
    assertMoney("0.00", view.receipt().getTotalCost());
    assertMoney("0.00", logRows(pen).get(0).getUnitCost());
    assertMoney("0.00", costOf(pen), "free goods really did cost nothing");
  }

  @Test
  void detail_carriesTheSupplier_theProducts_andEachLinesStockAfter() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", null, 10, 0);

    UUID id =
        receipts
            .record(org, delivery(zaki.getId(), line(pen, 5, "3.00")), "k-1", actor(user), user)
            .receipt()
            .getId();

    ReceiptView view = receipts.getById(org, id);
    assertEquals("Zaki Paper", view.supplier().getName());
    assertNotNull(view.products().get(pen));
    assertEquals(15, view.stockAfter().get(pen));
    assertEquals(GoodsReceiptStatus.POSTED, view.receipt().getStatus());
    assertNull(view.voidedByName());
  }

  @Test
  void list_isNewestReceivedFirst_andFiltersNarrow() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    Supplier other = suppliers.create(org, named("Other"));
    UUID pen = createTrackedProduct(org, "PEN", null, 0, 0);
    OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusDays(3);
    OffsetDateTime recent = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);

    receipts.record(
        org,
        new ReceiptCommand(zaki.getId(), old, "DN-1", null, List.of(line(pen, 1, "1.00"))),
        "k1",
        actor(user),
        user);
    receipts.record(
        org,
        new ReceiptCommand(other.getId(), recent, "DN-2", null, List.of(line(pen, 1, "1.00"))),
        "k2",
        actor(user),
        user);

    var all = receipts.list(org, GoodsReceiptListFilter.none(), 0, 20);
    assertEquals(2, all.total());
    assertEquals("DN-2", all.receipts().get(0).getSupplierReference(), "received_at DESC");
    assertEquals(1, all.lineCounts().get(all.receipts().get(0).getId()));
    assertNotNull(all.suppliers().get(other.getId()));

    assertEquals(
        1, receipts.list(org, GoodsReceiptListFilter.ofSupplier(zaki.getId()), 0, 20).total());
    assertEquals(
        0,
        receipts.list(org, GoodsReceiptListFilter.ofSupplier(UUID.randomUUID()), 0, 20).total(),
        "an unknown supplier_id narrows to nothing — it is a filter, not a lookup");
    assertEquals(
        1,
        receipts
            .list(org, new GoodsReceiptListFilter(null, null, null, null, "dn-1"), 0, 20)
            .total(),
        "q matches their reference, case-insensitively");
    assertEquals(
        1,
        receipts
            .list(
                org,
                new GoodsReceiptListFilter(
                    null, null, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), null, null),
                0,
                20)
            .total(),
        "the window is half-open on received_at");
  }

  @Test
  void receiptsAreOrgScoped() {
    UUID org = createOrg("acme");
    UUID other = createOrg("beta");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", null, 0, 0);
    UUID id =
        receipts
            .record(org, delivery(zaki.getId(), line(pen, 1, "1.00")), "k1", actor(user), user)
            .receipt()
            .getId();

    assertThrows(NotFoundException.class, () -> receipts.getById(other, id));
    assertEquals(0, receipts.list(other, GoodsReceiptListFilter.none(), 0, 20).total());
  }

  @Test
  void unitCostScale_isRefusedBeyondTwoPlaces() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", null, 0, 0);

    assertThrows(
        ValidationException.class,
        () ->
            receipts.record(
                org,
                delivery(zaki.getId(), new LineCommand(pen, 1, new BigDecimal("1.005"))),
                "k1",
                actor(user),
                user));
  }
}
