-- Read-path indexes from the measured audit (docs/query-index-audit.md §3 + §5a).
-- Every index here is convicted by a captured EXPLAIN (ANALYZE, BUFFERS) at seeded scale
-- (tools/seed/, 1M orders / 16.3M rows) or is a structurally-required FK/back-reference
-- whose absence turns a bounded read into a full scan as the table grows.
-- IF NOT EXISTS: the perf harness may apply this file directly before Flyway does.

-- ── FK line tables — the worst measured reads (line fetch = seq scan of the whole table) ──
-- 344ms → order detail; 85ms → invoice detail (baseline.txt)
CREATE INDEX IF NOT EXISTS sales_order_line_order_idx    ON sales_order_line (sales_order_id);
CREATE INDEX IF NOT EXISTS sales_invoice_line_invoice_idx ON sales_invoice_line (sales_invoice_id);
CREATE INDEX IF NOT EXISTS credit_note_line_note_idx     ON credit_note_line (credit_note_id);
CREATE INDEX IF NOT EXISTS fulfillment_line_fulfillment_idx ON fulfillment_line (fulfillment_id);
-- review purchase-gate EXISTS chain + reservation-by-order joins
CREATE INDEX IF NOT EXISTS fulfillment_line_order_line_idx  ON fulfillment_line (sales_order_line_id);
CREATE INDEX IF NOT EXISTS inventory_reservation_order_line_idx
    ON inventory_reservation (sales_order_line_id);
CREATE INDEX IF NOT EXISTS refund_allocation_pa_idx ON refund_allocation (payment_allocation_id);
-- top-products grouping / product-side entry into the purchase gate
CREATE INDEX IF NOT EXISTS sales_order_line_product_idx  ON sales_order_line (product_id);

-- ── payment — worklist (150ms), ledger, money story, health rollup ────────────────────────
CREATE INDEX IF NOT EXISTS payment_org_status_idx  ON payment (org_id, status, received_at, id);
CREATE INDEX IF NOT EXISTS payment_org_ledger_idx  ON payment (org_id, received_at, id);
CREATE INDEX IF NOT EXISTS payment_org_order_idx   ON payment (org_id, sales_order_id);

-- ── payment_transaction — unfiltered ledger (92ms; partials cover only the queues) ───────
CREATE INDEX IF NOT EXISTS payment_transaction_org_ledger_idx
    ON payment_transaction (org_id, recorded_at, id);

-- ── fulfillment — non-PENDING queues (82ms), ledger (24ms), shipment story (22ms) ────────
CREATE INDEX IF NOT EXISTS fulfillment_org_status_idx ON fulfillment (org_id, status, created_at, id);
CREATE INDEX IF NOT EXISTS fulfillment_org_ledger_idx ON fulfillment (org_id, created_at, id);
CREATE INDEX IF NOT EXISTS fulfillment_org_order_idx  ON fulfillment (org_id, sales_order_id);

-- ── sales_order — status tabs (sort removal; the (org_id, order_number) unique already
--    prefix-serves the plain org filter) + portal my-orders ───────────────────────────────
CREATE INDEX IF NOT EXISTS sales_order_org_status_idx
    ON sales_order (org_id, status, created_at, id);
CREATE INDEX IF NOT EXISTS sales_order_org_customer_idx
    ON sales_order (org_id, customer_id, placed_at DESC, id DESC);

-- ── sales_invoice — status queue, billing story, portal invoices ─────────────────────────
CREATE INDEX IF NOT EXISTS sales_invoice_org_status_idx
    ON sales_invoice (org_id, status, created_at, id);
CREATE INDEX IF NOT EXISTS sales_invoice_org_order_idx ON sales_invoice (org_id, sales_order_id);
CREATE INDEX IF NOT EXISTS sales_invoice_org_customer_live_idx
    ON sales_invoice (org_id, customer_id, created_at DESC, id DESC) WHERE status <> 'VOID';

-- ── credit_note — crediting story + issuance-cap guard ───────────────────────────────────
CREATE INDEX IF NOT EXISTS credit_note_org_invoice_idx ON credit_note (org_id, sales_invoice_id);

-- ── refund — zero indexes before this; small today (refunds are rare events), structural ──
CREATE INDEX IF NOT EXISTS refund_org_status_idx ON refund (org_id, status, created_at, id);
CREATE INDEX IF NOT EXISTS refund_org_note_idx   ON refund (org_id, credit_note_id);
CREATE INDEX IF NOT EXISTS refund_org_payment_idx ON refund (org_id, payment_id);

-- ── user_org_role — PK is user-first; every org-first read (roster, last-owner guard,
--    ORDER_PLACED staff fan-out) needs the org-first orientation ─────────────────────────
CREATE INDEX IF NOT EXISTS user_org_role_org_idx ON user_org_role (org_id, user_id);
