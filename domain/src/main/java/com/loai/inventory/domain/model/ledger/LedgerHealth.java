package com.loai.inventory.domain.model.ledger;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * The ledger's own reliability read (stories/general_ledger.md §Health), taken after a catch-up.
 *
 * <ul>
 *   <li>{@code unposted} — per event kind, source rows the poster left without an entry. After a
 *       catch-up every count should be 0; a non-zero is a row the templates refuse (an invoice
 *       whose totals do not add up, say) and the number the health screen shows for it.
 *   <li>{@code skipped} — per {@code kind:reason}, source rows the poster judged unpostable and
 *       recorded in {@code ledger_skip} (UNCOSTED, TOTALS_MISMATCH, UNKNOWN_RAIL) — coverage, the
 *       way {@code /reports/profit} reports costed units; {@code uncostedStockMoves} is the one of
 *       them the reports already speak of, pulled out by name.
 *   <li>{@code checks} — the ledger balance of an account against the SAME figure computed from the
 *       domain's own cached fields (invoice paid_amount, payment unallocated_amount). Two
 *       independent derivations of one number: equal means the ledger tells the truth the rest of
 *       the app tells.
 * </ul>
 */
public record LedgerHealth(
    long postedEntries,
    long unbalancedEntries,
    Map<String, Long> unposted,
    Map<String, Long> skipped,
    long uncostedStockMoves,
    List<Check> checks,
    OffsetDateTime lastPostedAt) {

  /** One cross-check: the ledger's figure, the domain's figure, and whether they agree. */
  public record Check(String name, BigDecimal ledger, BigDecimal source) {
    public boolean ok() {
      return ledger.compareTo(source) == 0;
    }
  }

  public boolean ok() {
    return unbalancedEntries == 0
        && unposted.values().stream().allMatch(n -> n == 0)
        && checks.stream().allMatch(Check::ok);
  }
}
