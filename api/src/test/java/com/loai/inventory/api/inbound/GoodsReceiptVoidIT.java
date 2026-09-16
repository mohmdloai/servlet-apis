package com.loai.inventory.api.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.GoodsReceiptStatus;
import com.loai.inventory.domain.model.Supplier;
import com.loai.inventory.repository.generated.tables.records.InventoryLogRecord;
import com.loai.inventory.service.GoodsReceiptService.ReceiptView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Undoing a receipt keyed wrong ({@code stories/supplier_goods_receipt.md}): one negative movement
 * per line at the SAME cost and the same document, never an ADJUSTMENT (which would book a keying
 * error to 5100 shrinkage), and never a restore of the product's cost.
 */
@Testcontainers
class GoodsReceiptVoidIT extends InboundItBase {

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
  void void_reversesEveryLine_atTheSameCost_underTheSameDocument() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", "4.00", 10, 0);
    UUID pad = createTrackedProduct(org, "PAD", null, 2, 0);
    UUID id =
        receipts
            .record(
                org,
                delivery(zaki.getId(), line(pen, 24, "12.50"), line(pad, 3, "40.00")),
                "k-1",
                actor(user),
                user)
            .receipt()
            .getId();

    ReceiptView voided =
        receipts.voidReceipt(org, id, "keyed 100 instead of 10", actor(user), user);

    assertEquals(GoodsReceiptStatus.VOIDED, voided.receipt().getStatus());
    assertEquals("keyed 100 instead of 10", voided.receipt().getVoidReason());
    assertNotNull(voided.receipt().getVoidedAt());
    assertNotNull(voided.voidedByName());
    assertMoney("420.00", voided.receipt().getTotalCost(), "a void keeps every figure");

    assertEquals(10, stockOf(pen), "stock is back where it was");
    assertEquals(2, stockOf(pad));

    List<InventoryLogRecord> penRows = logRows(pen);
    assertEquals(2, penRows.size());
    InventoryLogRecord reversal = penRows.get(1);
    assertEquals(-24, reversal.getStockDelta());
    assertEquals("RESTOCK", reversal.getReason().getLiteral(), "not an ADJUSTMENT — see the story");
    assertMoney("12.50", reversal.getUnitCost(), "the same cost, so the ledger nets to zero");
    assertEquals(id, reversal.getGoodsReceiptId());
  }

  @Test
  void void_neverRestoresTheProductsCost() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", "4.00", 10, 0);
    UUID id =
        receipts
            .record(org, delivery(zaki.getId(), line(pen, 10, "12.50")), "k-1", actor(user), user)
            .receipt()
            .getId();

    receipts.voidReceipt(org, id, "wrong supplier", actor(user), user);

    assertMoney(
        "12.50", costOf(pen), "nothing knows the old figure; the merchant can see and edit");
  }

  @Test
  void doubleVoid_is409() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", null, 10, 0);
    UUID id =
        receipts
            .record(org, delivery(zaki.getId(), line(pen, 5, "3.00")), "k-1", actor(user), user)
            .receipt()
            .getId();
    receipts.voidReceipt(org, id, "typo", actor(user), user);

    ConflictException e =
        assertThrows(
            ConflictException.class,
            () -> receipts.voidReceipt(org, id, "again", actor(user), user));

    assertTrue(e.getMessage().contains("already voided"), e.getMessage());
    assertEquals(10, stockOf(pen), "the second void moved nothing");
    assertEquals(2, logRows(pen).size());
  }

  @Test
  void void_thatWouldCrossTheReservedUnits_is409_namingThem_withNothingWritten() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", null, 0, 0);
    UUID id =
        receipts
            .record(org, delivery(zaki.getId(), line(pen, 10, "3.00")), "k-1", actor(user), user)
            .receipt()
            .getId();
    dsl.execute("UPDATE inventory SET reserved_qty = 4 WHERE product_id = ?", pen);

    ConflictException e =
        assertThrows(
            ConflictException.class,
            () -> receipts.voidReceipt(org, id, "typo", actor(user), user));

    assertTrue(e.getMessage().contains("4 units"), e.getMessage());
    assertEquals(10, stockOf(pen), "nothing was written");
    assertEquals(1, logRows(pen).size());
    assertEquals(GoodsReceiptStatus.POSTED, receipts.getById(org, id).receipt().getStatus());
  }

  @Test
  void void_afterAPartialSale_succeedsWhenEnoughRemains() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", null, 6, 0);
    UUID id =
        receipts
            .record(org, delivery(zaki.getId(), line(pen, 10, "3.00")), "k-1", actor(user), user)
            .receipt()
            .getId();
    dsl.execute("UPDATE inventory SET stock_qty = stock_qty - 4 WHERE product_id = ?", pen);

    receipts.voidReceipt(org, id, "wrong delivery note", actor(user), user);

    assertEquals(2, stockOf(pen), "16 received, 4 sold, 10 reversed");
  }

  @Test
  void void_requiresAReason() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", null, 0, 0);
    UUID id =
        receipts
            .record(org, delivery(zaki.getId(), line(pen, 1, "3.00")), "k-1", actor(user), user)
            .receipt()
            .getId();

    assertThrows(
        ValidationException.class, () -> receipts.voidReceipt(org, id, "  ", actor(user), user));
  }

  @Test
  void voidedReceipt_stillReadsItsOriginalStockAfter() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier zaki = suppliers.create(org, named("Zaki Paper"));
    UUID pen = createTrackedProduct(org, "PEN", null, 10, 0);
    UUID id =
        receipts
            .record(org, delivery(zaki.getId(), line(pen, 5, "3.00")), "k-1", actor(user), user)
            .receipt()
            .getId();
    receipts.voidReceipt(org, id, "typo", actor(user), user);

    assertEquals(
        15,
        receipts.getById(org, id).stockAfter().get(pen),
        "the document shows what it did, not what happened after");
  }
}
