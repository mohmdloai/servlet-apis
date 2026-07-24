-- Baseline (and after-index re-run) measurements for the audit's ranked gaps.
-- Shapes mirror the jOOQ queries verbatim (docs/query-index-audit.md §2-3).
-- Run twice; keep the second (warm-cache) numbers:
--   docker exec -i inventory_db psql -U postgres -d perfdb \
--     -v org=$(docker exec inventory_db psql -U postgres -d perfdb -tAc \
--       "set search_path=inventorydb; select id from org offset 5 limit 1") \
--     -f - < measure_baseline.sql > baseline_run.txt
SET search_path = inventorydb;
\timing on
\echo ===== pick fixture rows for the parameterized shapes
SELECT id AS order_id FROM sales_order WHERE org_id = :'org' AND status = 'CLOSED' LIMIT 1 \gset
SELECT id AS cust_id  FROM customer    WHERE org_id = :'org' LIMIT 1 \gset
SELECT id AS cn_id    FROM credit_note WHERE org_id = :'org' LIMIT 1 \gset
SELECT id AS pay_id   FROM payment     WHERE org_id = :'org' LIMIT 1 \gset
SELECT id AS inv_id   FROM sales_invoice WHERE org_id = :'org' LIMIT 1 \gset

\echo ===== P1.1 refunds worklist (?status=PENDING queue)
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM refund WHERE org_id = :'org' AND status = 'PENDING'
ORDER BY created_at ASC, id ASC OFFSET 0 LIMIT 25;
\echo ===== P1.1 refunds ledger (unfiltered)
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM refund WHERE org_id = :'org'
ORDER BY created_at DESC, id DESC OFFSET 0 LIMIT 25;
\echo ===== P1.1 credit-note cap meter (sumExecutedByCreditNote)
EXPLAIN (ANALYZE, BUFFERS)
SELECT coalesce(sum(amount), 0) FROM refund
WHERE org_id = :'org' AND credit_note_id = :'cn_id' AND status = 'EXECUTED';
\echo ===== P1.1 refunds by payment
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM refund WHERE org_id = :'org' AND payment_id = :'pay_id'
ORDER BY created_at ASC, id ASC;

\echo ===== P1.2 orders worklist (?status=PENDING_PAYMENT tab)
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM sales_order WHERE org_id = :'org' AND status = 'PENDING_PAYMENT'
ORDER BY created_at ASC, id ASC OFFSET 0 LIMIT 25;
\echo ===== P1.2 orders ledger (all tab)
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM sales_order WHERE org_id = :'org'
ORDER BY created_at DESC, id DESC OFFSET 0 LIMIT 25;
\echo ===== P1.2 orders count (worklist total)
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM sales_order WHERE org_id = :'org' AND status = 'PENDING_PAYMENT';
\echo ===== P1.2 portal my-orders
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM sales_order WHERE org_id = :'org' AND customer_id = :'cust_id'
ORDER BY placed_at DESC, id DESC OFFSET 0 LIMIT 25;

\echo ===== P1.3 payments worklist (?status=DISPUTED — the health-linked queue)
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM payment WHERE org_id = :'org' AND status = 'DISPUTED'
ORDER BY received_at ASC, id ASC OFFSET 0 LIMIT 25;
\echo ===== P1.3 payments ledger
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM payment WHERE org_id = :'org'
ORDER BY received_at DESC, id DESC OFFSET 0 LIMIT 25;
\echo ===== P1.3 order money story
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM payment WHERE org_id = :'org' AND sales_order_id = :'order_id'
ORDER BY received_at ASC, id ASC;
\echo ===== P1.3 health rollup payment aggregates
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FILTER (WHERE status = 'DISPUTED'),
       count(*) FILTER (WHERE unallocated_amount > 0)
FROM payment WHERE org_id = :'org';

\echo ===== P1.4 invoices worklist (?status=ISSUED awaiting-payment)
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM sales_invoice WHERE org_id = :'org' AND status = 'ISSUED'
ORDER BY created_at ASC, id ASC OFFSET 0 LIMIT 25;
\echo ===== P1.4 invoices ledger
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM sales_invoice WHERE org_id = :'org'
ORDER BY created_at DESC, id DESC OFFSET 0 LIMIT 25;
\echo ===== P1.4 order billing story
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM sales_invoice WHERE org_id = :'org' AND sales_order_id = :'order_id'
ORDER BY created_at ASC, id ASC;
\echo ===== P1.4 portal invoices
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM sales_invoice WHERE org_id = :'org' AND customer_id = :'cust_id'
AND status <> 'VOID' ORDER BY created_at DESC, id DESC OFFSET 0 LIMIT 25;

\echo ===== P1.5 fulfillments worklist (?status=SHIPPED — non-partial-covered status)
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM fulfillment WHERE org_id = :'org' AND status = 'SHIPPED'
ORDER BY created_at ASC, id ASC OFFSET 0 LIMIT 25;
\echo ===== P1.5 fulfillments ledger
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM fulfillment WHERE org_id = :'org'
ORDER BY created_at DESC, id DESC OFFSET 0 LIMIT 25;
\echo ===== P1.5 order shipment story
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM fulfillment WHERE org_id = :'org' AND sales_order_id = :'order_id'
ORDER BY created_at ASC;

\echo ===== P1.6 members roster page (org-first user_org_role)
EXPLAIN (ANALYZE, BUFFERS)
SELECT u.id, u.email FROM user_org_role r JOIN app_user u ON u.id = r.user_id
WHERE r.org_id = :'org' GROUP BY u.id, u.email ORDER BY u.email ASC OFFSET 0 LIMIT 25;
\echo ===== P1.6 notification fan-out (findActiveUserIdsByOrgAndRoles)
EXPLAIN (ANALYZE, BUFFERS)
SELECT DISTINCT r.user_id FROM user_org_role r JOIN app_user u ON u.id = r.user_id
WHERE r.org_id = :'org' AND r.role IN ('STAFF','MANAGER','OWNER') AND u.active IS TRUE;

\echo ===== P1.7 transaction ledger (unfiltered)
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM payment_transaction WHERE org_id = :'org'
ORDER BY recorded_at DESC, id DESC OFFSET 0 LIMIT 25;

\echo ===== P2.8 order lines (every order detail read)
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM sales_order_line WHERE sales_order_id = :'order_id';
\echo ===== P2.10 invoice lines
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM sales_invoice_line WHERE sales_invoice_id = :'inv_id' ORDER BY id ASC;
\echo ===== P2.11 invoice crediting story
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM credit_note WHERE org_id = :'org' AND sales_invoice_id = :'inv_id'
ORDER BY created_at ASC, id ASC;

\echo ===== P3.14 inventory movement ledger (paged)
SELECT product_id AS prod_id FROM inventory_log WHERE org_id = :'org' LIMIT 1 \gset
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM inventory_log WHERE org_id = :'org' AND product_id = :'prod_id'
ORDER BY created_at DESC, id DESC OFFSET 0 LIMIT 25;
