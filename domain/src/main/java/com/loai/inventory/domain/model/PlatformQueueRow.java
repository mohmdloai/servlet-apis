package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of a cross-org queue — <strong>and the field whitelist itself</strong>.
 *
 * <p>Rule 3 of {@link com.loai.inventory.domain.repository.PlatformQueueRepository} says each kind
 * exposes the tenant's identity plus the minimum facts needed to triage it and nothing else. That
 * rule is enforced structurally rather than by review: this is a sealed hierarchy of records, so a
 * field an operator should not see cannot be carried without editing one of the five declarations
 * below, on purpose, in a diff. No customer name, phone or address. No order lines. No
 * payment-proof URL. No object keys.
 *
 * <p><strong>One deliberate PII crossing, on one kind:</strong> {@code toAddress} on {@link
 * FailedEmail}. A failed-delivery queue without the recipient cannot be triaged — you cannot tell
 * three unrelated failures from three failures to one dead domain, which is the first question
 * worth asking. It crosses there and nowhere else, and {@code PlatformQueuesIT} asserts it on
 * purpose so removing it later is a visible decision.
 *
 * <p>This widens <em>default exposure</em>, not capability: a platform ADMIN already bypasses org
 * checks and SUPPORT already has read-only impersonation, so nothing here is newly reachable. What
 * changes is what an operator sees without asking, which is why it is enumerated per kind rather
 * than joined to whatever was convenient.
 */
public sealed interface PlatformQueueRow {

  /** The tenant this row belongs to; present on every kind. */
  PlatformQueueOrg org();

  /** The row's own identifier, in its own table. */
  UUID id();

  /**
   * A notification delivery that gave up. There is no detail page for one of these anywhere in this
   * product, so the row <em>is</em> the triage: who it was for, how many attempts, and what the
   * transport last said.
   */
  record FailedEmail(
      PlatformQueueOrg org,
      UUID id,
      String notificationType,
      String toAddress,
      int attempts,
      String lastError,
      OffsetDateTime failedAt,
      OffsetDateTime createdAt)
      implements PlatformQueueRow {}

  /** Money owed and not yet sent back. The org twin is {@code GET /refunds?status=PENDING}. */
  record PendingRefund(
      PlatformQueueOrg org,
      UUID id,
      BigDecimal amount,
      String method,
      String salesOrderNumber,
      String creditNoteNumber,
      OffsetDateTime createdAt)
      implements PlatformQueueRow {}

  /** A payment frozen mid-dispute. The org twin is {@code GET /payments?status=DISPUTED}. */
  record OpenDispute(
      PlatformQueueOrg org,
      UUID id,
      BigDecimal amount,
      String salesOrderNumber,
      OffsetDateTime receivedAt)
      implements PlatformQueueRow {}

  /**
   * Money that arrived and matches no order. The org twin is {@code GET
   * /payment-transactions?reconciliation_status=ORPHAN&has_payment=false}.
   */
  record OrphanTransaction(
      PlatformQueueOrg org,
      UUID id,
      BigDecimal amount,
      String provider,
      String providerRef,
      OffsetDateTime occurredAt)
      implements PlatformQueueRow {}

  /**
   * An order whose payment hold has run out. No org-scoped list exists — the org order worklist
   * filters on {@code status}, not on {@code expires_at}.
   */
  record ExpiredPendingOrder(
      PlatformQueueOrg org,
      UUID id,
      String orderNumber,
      BigDecimal grandTotal,
      OffsetDateTime expiresAt,
      OffsetDateTime placedAt)
      implements PlatformQueueRow {}
}
