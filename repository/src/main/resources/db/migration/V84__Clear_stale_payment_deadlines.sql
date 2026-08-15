-- stories/clear_expiry_on_paid.md — heal the "Expired but still held" lie for in-flight orders.
--
-- A paid order has no payment-hold window: from this migration on, the PENDING_PAYMENT -> PAID
-- flip nulls sales_order.expires_at and the ACTIVE holds' mirror (V19: "mirrors
-- SalesOrder.expires_at while ACTIVE") in the same transaction. These two idempotent UPDATEs
-- backfill the rows written before the fix. EXPIRED and CANCELLED orders keep their historical
-- deadline on purpose (their holds are RELEASED atomically by those flips, so no ACTIVE-hold
-- surface renders it); PENDING_PAYMENT keeps its live one.

UPDATE sales_order
SET expires_at = NULL
WHERE expires_at IS NOT NULL
  AND status IN ('PAID', 'FULFILLING', 'FULFILLED', 'CLOSED');

UPDATE inventory_reservation r
SET expires_at = NULL
FROM sales_order_line l
JOIN sales_order o ON o.id = l.sales_order_id
WHERE r.sales_order_line_id = l.id
  AND r.status = 'ACTIVE'
  AND r.expires_at IS NOT NULL
  AND o.status <> 'PENDING_PAYMENT';
