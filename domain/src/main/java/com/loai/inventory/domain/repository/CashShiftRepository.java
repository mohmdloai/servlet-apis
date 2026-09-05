package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.CashShift;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CashShiftRepository {

  void insert(CashShift shift);

  void update(CashShift shift);

  Optional<CashShift> findById(UUID orgId, UUID id);

  /** The org's open shift, or empty. */
  Optional<CashShift> findOpen(UUID orgId);

  /** The org's open shift, row-locked for the caller's transaction. */
  Optional<CashShift> findOpenForUpdate(UUID orgId);

  /** The most recently closed shift — its counted cash is the next float. */
  Optional<CashShift> findLastClosed(UUID orgId);

  List<CashShift> list(UUID orgId, int offset, int limit);

  long count(UUID orgId);

  /**
   * The sums the expected cash is made of, over the rows stamped with the shift plus its movements
   * — one query, NUMERIC kept NUMERIC ({@code stories/cash_shift.md} §Totals).
   */
  Totals totals(UUID orgId, UUID shiftId);

  record Totals(
      BigDecimal cashSales,
      BigDecimal changeGiven,
      BigDecimal cashRefunds,
      BigDecimal instapayTotal,
      BigDecimal payIn,
      BigDecimal payOut,
      long receipts,
      BigDecimal discounts) {}
}
