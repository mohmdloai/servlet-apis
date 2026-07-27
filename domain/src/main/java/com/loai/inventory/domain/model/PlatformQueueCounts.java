package com.loai.inventory.domain.model;

/**
 * The five cross-org operational backlogs on the platform overview. Every figure is a count of rows
 * matching <em>exactly</em> the predicate of the org-scoped list it will link to once the cross-org
 * queue reads land (slice 2), so a tile and its drill-down can never disagree:
 *
 * <ul>
 *   <li>{@code failedEmails} — {@code notification_delivery.status = 'FAILED'}
 *   <li>{@code pendingRefunds} — {@code refund.status = 'PENDING'} (the to-execute queue)
 *   <li>{@code openDisputes} — {@code payment.status = 'DISPUTED'}
 *   <li>{@code orphanTransactions} — {@code payment_transaction.reconciliation_status = 'ORPHAN'}
 *       <em>and</em> no {@code payment} row, i.e. the org queue's {@code has_payment=false}
 *   <li>{@code expiredPendingOrders} — {@code sales_order.status = 'PENDING_PAYMENT' AND expires_at
 *       < now()}, the sweeper's own candidate set
 * </ul>
 */
public record PlatformQueueCounts(
    long failedEmails,
    long pendingRefunds,
    long openDisputes,
    long orphanTransactions,
    long expiredPendingOrders) {}
