package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.PlatformQueueRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of {@code GET /api/admin/queues/{kind}} on the wire — one record per kind, mirroring the
 * sealed {@link PlatformQueueRow} whitelist field for field.
 *
 * <p>Kept a sealed hierarchy rather than one wide record with mostly-null fields for the same
 * reason the domain model is: the exposed field set per kind should be readable in one screen and
 * un-widenable without a deliberate edit. Jackson serializes the concrete record and drops nulls,
 * so a refund row carries no {@code provider_ref} key at all rather than a null one.
 */
public sealed interface AdminQueueRowResponse {

  /** The tenant, on every kind. */
  AdminQueueOrgResponse org();

  static AdminQueueRowResponse from(PlatformQueueRow row) {
    AdminQueueOrgResponse org = AdminQueueOrgResponse.from(row.org());
    return switch (row) {
      case PlatformQueueRow.FailedEmail r ->
          new FailedEmail(
              r.id(),
              org,
              r.notificationType(),
              r.toAddress(),
              r.attempts(),
              r.lastError(),
              r.failedAt(),
              r.createdAt());
      case PlatformQueueRow.PendingRefund r ->
          new PendingRefund(
              r.id(),
              org,
              r.amount(),
              r.method(),
              r.salesOrderNumber(),
              r.creditNoteNumber(),
              r.createdAt());
      case PlatformQueueRow.OpenDispute r ->
          new OpenDispute(r.id(), org, r.amount(), r.salesOrderNumber(), r.receivedAt());
      case PlatformQueueRow.OrphanTransaction r ->
          new OrphanTransaction(
              r.id(), org, r.amount(), r.provider(), r.providerRef(), r.occurredAt());
      case PlatformQueueRow.ExpiredPendingOrder r ->
          new ExpiredPendingOrder(
              r.id(), org, r.orderNumber(), r.grandTotal(), r.expiresAt(), r.placedAt());
    };
  }

  /**
   * {@code to_address} is the one PII field that crosses on any queue, and only on this one: a
   * failed-delivery queue without the recipient cannot be triaged — you cannot tell three unrelated
   * failures from three failures to one dead domain. Asserted on purpose in {@code
   * PlatformQueuesIT}, so removing it later is a visible decision.
   */
  record FailedEmail(
      UUID id,
      AdminQueueOrgResponse org,
      String notificationType,
      String toAddress,
      int attempts,
      String lastError,
      OffsetDateTime failedAt,
      OffsetDateTime createdAt)
      implements AdminQueueRowResponse {}

  record PendingRefund(
      UUID id,
      AdminQueueOrgResponse org,
      BigDecimal amount,
      String method,
      String salesOrderNumber,
      String creditNoteNumber,
      OffsetDateTime createdAt)
      implements AdminQueueRowResponse {}

  record OpenDispute(
      UUID id,
      AdminQueueOrgResponse org,
      BigDecimal amount,
      String salesOrderNumber,
      OffsetDateTime receivedAt)
      implements AdminQueueRowResponse {}

  record OrphanTransaction(
      UUID id,
      AdminQueueOrgResponse org,
      BigDecimal amount,
      String provider,
      String providerRef,
      OffsetDateTime occurredAt)
      implements AdminQueueRowResponse {}

  record ExpiredPendingOrder(
      UUID id,
      AdminQueueOrgResponse org,
      String orderNumber,
      BigDecimal grandTotal,
      OffsetDateTime expiresAt,
      OffsetDateTime placedAt)
      implements AdminQueueRowResponse {}
}
