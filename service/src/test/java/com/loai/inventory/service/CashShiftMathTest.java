package com.loai.inventory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.model.CashMovement;
import com.loai.inventory.domain.model.CashMovementKind;
import com.loai.inventory.domain.model.CashShift;
import com.loai.inventory.domain.repository.CashShiftRepository.Totals;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The SQL-free half of {@code stories/cash_shift.md}: the expected-cash arithmetic and the two
 * models' rules (a float and a count are never negative, a shift closes once, a movement has a
 * reason). Pinned here so the IT can spend its time on the stamps.
 */
class CashShiftMathTest {

  private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);
  private static final UUID ORG = UUID.randomUUID();
  private static final UUID OWNER = UUID.randomUUID();

  private static Totals totals(String sales, String change, String refunds, String in, String out) {
    return new Totals(
        new BigDecimal(sales),
        new BigDecimal(change),
        new BigDecimal(refunds),
        new BigDecimal("75.00"),
        new BigDecimal(in),
        new BigDecimal(out),
        3,
        BigDecimal.ZERO);
  }

  /** The story's worked example: 200 + 210 − 10 − 30 + 100 − 80 = 390. */
  @Test
  void expectedCash_isFloatPlusCashInMinusCashOut() {
    BigDecimal expected =
        CashShiftService.expectedCash(
            new BigDecimal("200"), totals("210.00", "10.00", "30.00", "100.00", "80.00"));
    assertEquals(new BigDecimal("390.00"), expected);
  }

  @Test
  void expectedCash_ignoresInstapayAndTreatsNullsAsZero() {
    Totals t = new Totals(null, null, null, new BigDecimal("999.00"), null, null, 0, null);
    assertEquals(new BigDecimal("50.00"), CashShiftService.expectedCash(new BigDecimal("50"), t));
    assertEquals(new BigDecimal("0.00"), CashShiftService.expectedCash(null, t));
  }

  @Test
  void shift_opensWithAScaledFloat_andRefusesANegativeOne() {
    CashShift s =
        CashShift.open(UUID.randomUUID(), ORG, OWNER, new BigDecimal("300"), false, "  ", NOW);
    assertTrue(s.isOpen());
    assertEquals(new BigDecimal("300.00"), s.getStartingCash());
    assertNull(s.getNote());
    assertNull(s.getDifference());
    assertFalse(s.isAutoOpened());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CashShift.open(UUID.randomUUID(), ORG, OWNER, new BigDecimal("-1"), false, null, NOW));
  }

  @Test
  void shift_closesOnce_andTheDifferenceIsCountedMinusExpected() {
    CashShift s =
        CashShift.open(UUID.randomUUID(), ORG, OWNER, new BigDecimal("200"), true, null, NOW);
    s.close(OWNER, new BigDecimal("385"), new BigDecimal("390.00"), "short by a fiver", NOW);
    assertFalse(s.isOpen());
    assertEquals(new BigDecimal("-5.00"), s.getDifference());
    assertEquals("short by a fiver", s.getNote());
    assertThrows(
        IllegalStateException.class,
        () -> s.close(OWNER, new BigDecimal("390"), new BigDecimal("390.00"), null, NOW));
    assertThrows(IllegalStateException.class, () -> s.setStartingCash(new BigDecimal("1"), NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          CashShift open =
              CashShift.open(UUID.randomUUID(), ORG, OWNER, BigDecimal.ZERO, true, null, NOW);
          open.close(OWNER, new BigDecimal("-1"), BigDecimal.ZERO, null, NOW);
        });
  }

  @Test
  void movement_needsAPositiveAmountAndAReason() {
    UUID shift = UUID.randomUUID();
    CashMovement m =
        CashMovement.create(
            UUID.randomUUID(),
            ORG,
            shift,
            CashMovementKind.PAY_OUT,
            new BigDecimal("500"),
            " Bank ",
            OWNER,
            NOW);
    assertEquals(new BigDecimal("500.00"), m.getAmount());
    assertEquals("Bank", m.getReason());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CashMovement.create(
                UUID.randomUUID(),
                ORG,
                shift,
                CashMovementKind.PAY_IN,
                BigDecimal.ZERO,
                "x",
                OWNER,
                NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CashMovement.create(
                UUID.randomUUID(),
                ORG,
                shift,
                CashMovementKind.PAY_IN,
                BigDecimal.ONE,
                "   ",
                OWNER,
                NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CashMovement.create(
                UUID.randomUUID(),
                ORG,
                shift,
                CashMovementKind.PAY_IN,
                BigDecimal.ONE,
                "r".repeat(201),
                OWNER,
                NOW));
  }

  @Test
  void view_differenceFollowsTheShift() {
    CashShift s =
        CashShift.open(UUID.randomUUID(), ORG, OWNER, new BigDecimal("100"), false, null, NOW);
    Totals t = totals("50.00", "0", "0", "0", "0");
    CashShiftService.ShiftView open =
        new CashShiftService.ShiftView(
            s, t, CashShiftService.expectedCash(s.getStartingCash(), t), null, null);
    assertNull(open.difference());
    s.close(OWNER, new BigDecimal("160"), new BigDecimal("150.00"), null, NOW);
    CashShiftService.ShiftView closed =
        new CashShiftService.ShiftView(s, t, s.getExpectedCash(), null, null);
    assertEquals(new BigDecimal("10.00"), closed.difference());
  }
}
