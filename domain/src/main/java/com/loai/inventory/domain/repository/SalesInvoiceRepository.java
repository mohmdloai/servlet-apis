package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.InvoiceListFilter;
import com.loai.inventory.domain.model.InvoiceListStats;
import com.loai.inventory.domain.model.InvoiceStatus;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SalesInvoiceLine;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the SalesInvoice aggregate (invoice + lines) and the gapless per-org per-year
 * invoice-number counter. Bound to a transactional {@code DSLContext} via {@link
 * SalesInvoiceRepositoryFactory} — every method runs in the caller's transaction.
 */
public interface SalesInvoiceRepository {

  /** Insert the invoice + its lines in one go, inside the caller's txn. */
  void insert(SalesInvoice invoice, List<SalesInvoiceLine> lines);

  /**
   * Find the <em>live</em> (non-VOID) invoice issued for a given fulfillment. Backs idempotent
   * re-delivery: a retried DELIVERED finds the existing invoice instead of creating a duplicate.
   * VOID rows are excluded so that after a void/reissue this returns the corrected invoice, never a
   * cancelled one — consistent with the partial unique index {@code
   * sales_invoice_fulfillment_id_live_uq} (at most one live invoice per fulfillment).
   */
  Optional<SalesInvoice> findByFulfillmentId(UUID orgId, UUID fulfillmentId);

  /** Read an invoice by id, scoped to {@code orgId} — backs CreditNote issuance against it. */
  Optional<SalesInvoice> findById(UUID orgId, UUID id);

  /**
   * Read an invoice by id under a {@code SELECT … FOR UPDATE} row lock, scoped to {@code orgId}.
   * Backs CreditNote issuance: holding the invoice row for the rest of the transaction serializes
   * concurrent issuances against the same invoice, so the cumulative-credit cap sees every
   * committed sibling note instead of racing past a stale sum.
   */
  Optional<SalesInvoice> findByIdForUpdate(UUID orgId, UUID id);

  /** All invoices issued against an order — drives the "all invoices PAID → CLOSED" roll-up. */
  List<SalesInvoice> findByOrderId(UUID orgId, UUID salesOrderId);

  /**
   * One page of the org's invoices — the awaiting-payment worklist / invoice ledger, narrowed by
   * every dimension of the {@link InvoiceListFilter} ({@code stories/invoice_filters.md}). A status
   * makes it a queue: {@code created_at ASC, id ASC} ({@code ?status=ISSUED} is the
   * oldest-awaiting-payment first). None makes it the audit ledger: {@code created_at DESC, id
   * DESC}, every status (VOID included). The other dimensions only narrow — they never reorder.
   * Same queue-vs-ledger convention as {@code RefundRepository#list}. Lean: the invoice header only
   * — lines are not loaded for the list.
   */
  List<SalesInvoice> list(UUID orgId, InvoiceListFilter filter, int offset, int limit);

  /**
   * The count and money figures of everything {@link #list} would page through for the same filter
   * — one query, the same predicate, so a total can never disagree with its rows.
   */
  InvoiceListStats stats(UUID orgId, InvoiceListFilter filter);

  /** Total rows {@link #list} would page through for the same {@code status}. */
  default long count(UUID orgId, InvoiceStatus status) {
    return stats(orgId, InvoiceListFilter.ofStatus(status)).total();
  }

  /**
   * Live rows per status across the whole org ledger — the worklist tabs' numbers. Only statuses
   * with at least one row appear; the service fills the zeros.
   */
  Map<InvoiceStatus, Long> countByStatus(UUID orgId);

  /**
   * One page of a single customer's <em>live</em> (non-VOID) invoices — the customer-portal "my
   * invoices" read (slice P3, {@code stories/portal_invoices.md}). Filtered by the frozen {@code
   * customer_id} snapshot (set from {@code sales_order.customer_id} at issuance, so equivalent to
   * the invoice→order→customer join), which also excludes walk-in in-store receipts (null
   * customer). Newest first ({@code created_at DESC, id DESC}). VOID rows are dropped so a
   * reissue's cancelled predecessor never surfaces to the customer. Lean: the invoice header only.
   */
  List<SalesInvoice> findByCustomerId(UUID orgId, UUID customerId, int offset, int limit);

  /** Total rows {@link #findByCustomerId} would page through for the same customer. */
  long countByCustomerId(UUID orgId, UUID customerId);

  /**
   * Persist the mutable cached state of an invoice: {@code status}, {@code paid_amount}, {@code
   * updated_at}. Scoped by {@code (org_id, id)}.
   */
  void updatePaymentState(SalesInvoice invoice);

  /**
   * Persist a void: {@code status}, {@code voided_at}, {@code void_reason}, {@code updated_at}.
   * Scoped by {@code (org_id, id)}. Backs invoice void / reissue.
   */
  void updateVoidState(SalesInvoice invoice);

  /** The invoice's lines, for response assembly on read / void. */
  List<SalesInvoiceLine> findLinesByInvoiceId(UUID salesInvoiceId);

  /**
   * Mint the next gapless invoice number for {@code (orgId, year)} — {@code INV-YYYY-NNNN}. Ensures
   * the counter row exists, then advances it under a {@code SELECT … FOR UPDATE} row lock held for
   * the rest of the transaction — so the increment rolls back with the issuing transaction and no
   * number is ever burned.
   *
   * <p>This is the <b>single owner</b> of invoice numbering: it both allocates the sequence and
   * formats the {@code INV-YYYY-NNNN} string, so no caller ever constructs an invoice number itself
   * (which is how a counter could drift behind the table — see {@code
   * stories/number_sequence_integrity.md}). Returns the formatted number (first claim of a year
   * returns {@code INV-YYYY-0001}).
   */
  String claimInvoiceNumber(UUID orgId, int year);
}
