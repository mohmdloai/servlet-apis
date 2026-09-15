package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.ledger.LedgerHealth;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/orgs/{orgId}/ledger/health} — the ledger judged against the rest of the app
 * (stories/general_ledger.md §Health). {@code ok} is the one-word verdict; the rest says why.
 */
public record LedgerHealthResponse(
    boolean ok,
    long postedEntries,
    long unbalancedEntries,
    Map<String, Long> unposted,
    Map<String, Long> skipped,
    long uncostedStockMoves,
    List<Check> checks,
    OffsetDateTime lastPostedAt) {

  public record Check(String name, BigDecimal ledger, BigDecimal source, boolean ok) {}

  public static LedgerHealthResponse from(LedgerHealth h) {
    return new LedgerHealthResponse(
        h.ok(),
        h.postedEntries(),
        h.unbalancedEntries(),
        h.unposted(),
        h.skipped(),
        h.uncostedStockMoves(),
        h.checks().stream().map(c -> new Check(c.name(), c.ledger(), c.source(), c.ok())).toList(),
        h.lastPostedAt());
  }
}
