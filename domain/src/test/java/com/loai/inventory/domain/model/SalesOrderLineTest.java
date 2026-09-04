package com.loai.inventory.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The V92 cost snapshot on a line: carried verbatim, scaled to money, never part of a total. */
class SalesOrderLineTest {

  private static SalesOrderLine line(BigDecimal unitCost) {
    return SalesOrderLine.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "Notebook A5",
        3,
        new BigDecimal("50.00"),
        unitCost,
        new BigDecimal("0.14"));
  }

  @Test
  void create_carriesUnitCostVerbatim_scaledToMoney() {
    SalesOrderLine l = line(new BigDecimal("30"));
    assertEquals(new BigDecimal("30.00"), l.getUnitCost());
    // Totals are what the customer pays; the cost never enters them.
    assertEquals(new BigDecimal("150.00"), l.getLineSubtotal());
    assertEquals(new BigDecimal("21.00"), l.getLineTax());
    assertEquals(new BigDecimal("171.00"), l.getLineTotal());
  }

  @Test
  void create_nullCostStaysNull_andTheSevenArgFactoryMeansUncosted() {
    assertNull(line(null).getUnitCost());
    SalesOrderLine legacy =
        SalesOrderLine.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Pen",
            1,
            new BigDecimal("5.00"),
            BigDecimal.ZERO);
    assertNull(legacy.getUnitCost());
  }

  @Test
  void create_negativeCostIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> line(new BigDecimal("-1")));
  }

  @Test
  void rehydrate_roundTripsTheCost_bothShapes() {
    UUID id = UUID.randomUUID();
    SalesOrderLine costed =
        SalesOrderLine.rehydrate(
            id,
            id,
            id,
            "x",
            2,
            new BigDecimal("10.00"),
            new BigDecimal("6.50"),
            BigDecimal.ZERO,
            new BigDecimal("20.00"),
            BigDecimal.ZERO,
            new BigDecimal("20.00"));
    assertEquals(new BigDecimal("6.50"), costed.getUnitCost());
    SalesOrderLine legacy =
        SalesOrderLine.rehydrate(
            id,
            id,
            id,
            "x",
            2,
            new BigDecimal("10.00"),
            BigDecimal.ZERO,
            new BigDecimal("20.00"),
            BigDecimal.ZERO,
            new BigDecimal("20.00"));
    assertNull(legacy.getUnitCost());
  }
}
