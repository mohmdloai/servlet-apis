package com.loai.inventory.domain.repository;

import java.util.List;
import java.util.UUID;

/**
 * Detect and repair a document-number counter that has drifted <em>behind</em> the rows it is
 * supposed to be ahead of — the one-time heal for an already-diverged environment described in
 * {@code stories/number_sequence_integrity.md} §Detect &amp; repair.
 *
 * <p>Scope is deliberately limited to <b>invoice</b> and <b>credit-note</b> numbering. Both use the
 * fixed {@code …-YYYY-%04d} shape, so parsing the trailing integer and comparing on that integer is
 * exact. Order numbering is <b>excluded</b>: its {@code SO-YYYY-%05d} allocator and the 6-digit
 * seed orders live in disjoint namespaces, so a naive integer comparison there is a false positive
 * that would wrongly jump the counter.
 */
public interface NumberSequenceReconciliationRepository {

  /**
   * One counter that trails the table: {@code requiredNextVal} = {@code MAX(numeric suffix) + 1}
   * over the document rows for {@code (orgId, year)}, and {@code currentNextVal} is the counter's
   * present value ({@code null} when no counter row exists yet). Only emitted when a realign is
   * actually needed ({@code currentNextVal} absent or {@code < requiredNextVal}).
   */
  record CounterDrift(UUID orgId, int year, Long currentNextVal, long requiredNextVal) {}

  /** Invoice counters that trail {@code sales_invoice}. */
  List<CounterDrift> detectInvoiceDrift();

  /** Credit-note counters that trail {@code credit_note}. */
  List<CounterDrift> detectCreditNoteDrift();

  /**
   * Forward-only realign of every {@code invoice_number_counter} to {@code GREATEST(next_val,
   * MAX(suffix) + 1)} per {@code (org_id, year)}, creating the row where absent. Never lowers an
   * already-ahead counter, so it can neither reuse nor skip a live number.
   */
  void realignInvoiceCounters();

  /** Forward-only realign of every {@code credit_note_number_counter}. Mirrors invoices. */
  void realignCreditNoteCounters();
}
