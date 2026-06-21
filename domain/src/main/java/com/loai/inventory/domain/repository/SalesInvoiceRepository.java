package com.loai.inventory.domain.repository;

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
   * Find the invoice issued for a given fulfillment ({@code sales_invoice.fulfillment_id} is {@code
   * UNIQUE}). Backs idempotent re-delivery: a retried DELIVERED finds the existing invoice instead
   * of creating a duplicate.
   */
  Optional<SalesInvoice> findByFulfillmentId(UUID orgId, UUID fulfillmentId);

  /** All invoices issued against an order — drives the "all invoices PAID → CLOSED" roll-up. */
  List<SalesInvoice> findByOrderId(UUID orgId, UUID salesOrderId);

  /**
   * Persist the mutable cached state of an invoice: {@code status}, {@code paid_amount}, {@code
   * updated_at}. Scoped by {@code (org_id, id)}.
   */
  void updatePaymentState(SalesInvoice invoice);

  /**
   * Claim the next gapless invoice sequence number for {@code (orgId, year)}. Ensures the counter
   * row exists, then advances it under a {@code SELECT … FOR UPDATE} row lock held for the rest of
   * the transaction — so the increment rolls back with the issuing transaction and no number is
   * ever burned. Returns the claimed value (first claim returns 1).
   */
  long claimInvoiceNumber(UUID orgId, int year);
}
