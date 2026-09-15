package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.ledger.AccountStatement;
import com.loai.inventory.domain.model.ledger.JournalPage;
import com.loai.inventory.domain.model.ledger.LedgerHealth;
import com.loai.inventory.domain.model.ledger.PostingSummary;
import com.loai.inventory.domain.model.ledger.TrialBalanceRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The general ledger's store (stories/general_ledger.md). Every method is org-scoped. The poster
 * methods run inside the caller's transaction; the reads trust their inputs (window validation is
 * {@code LedgerService}'s job).
 */
public interface LedgerRepository {

  /** Materialise the standard chart for the org — idempotent, a no-op once the rows exist. */
  void ensureChart(UUID orgId);

  /**
   * Post every entry the org's source rows imply that is not posted yet — all event kinds, one
   * set-based idempotent statement each, under the org's advisory lock. Returns what was inserted.
   */
  PostingSummary postMissing(UUID orgId);

  /**
   * Delete the org's journal (entries + lines) so a rebuild starts clean. Returns entries removed.
   */
  int reset(UUID orgId);

  /** Every account on the chart with opening / movement / closing over {@code [from, to)}. */
  List<TrialBalanceRow> trialBalance(UUID orgId, OffsetDateTime from, OffsetDateTime to);

  /**
   * Entries posted in {@code [from, to)}, oldest first, optionally only those with a leg on {@code
   * accountCode}.
   */
  JournalPage journal(
      UUID orgId,
      OffsetDateTime from,
      OffsetDateTime to,
      String accountCode,
      int offset,
      int limit);

  /** One account's statement over {@code [from, to)}; empty when the code is not on the chart. */
  java.util.Optional<AccountStatement> statement(
      UUID orgId,
      String accountCode,
      OffsetDateTime from,
      OffsetDateTime to,
      int offset,
      int limit);

  /** The reliability read — see {@link LedgerHealth}. */
  LedgerHealth health(UUID orgId);
}
