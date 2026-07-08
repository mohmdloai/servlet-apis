package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.InvoiceStatus;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SalesInvoiceLine;
import java.util.List;
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
   * One page of the org's invoices — the awaiting-payment worklist / invoice ledger. A non-null
   * {@code status} makes it a queue: {@code created_at ASC, id ASC} ({@code ?status=ISSUED} is the
   * oldest-awaiting-payment first). {@code null} makes it the audit ledger: {@code created_at DESC,
   * id DESC}, every status (VOID included). Same queue-vs-ledger convention as {@code
   * RefundRepository#list}. Lean: the invoice header only — lines are not loaded for the list.
   */
  List<SalesInvoice> list(UUID orgId, InvoiceStatus status, int offset, int limit);

  /** Total rows {@link #list} would page through for the same {@code status}. */
  long count(UUID orgId, InvoiceStatus status);

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
   * Claim the next gapless invoice sequence number for {@code (orgId, year)}. Ensures the counter
   * row exists, then advances it under a {@code SELECT … FOR UPDATE} row lock held for the rest of
   * the transaction — so the increment rolls back with the issuing transaction and no number is
   * ever burned. Returns the claimed value (first claim returns 1).
   */
  long claimInvoiceNumber(UUID orgId, int year);
}
