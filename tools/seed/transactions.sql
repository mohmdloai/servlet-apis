-- Synthetic transactional volume over the ABO-seeded catalog (phase 2, query-index-audit §5).
-- Pure SQL / generate_series so the whole run is one reproducible server-side pass.
-- psql vars: :orders (sales orders), :logrows (inventory_log events).
-- State-machine consistent: reservations follow order status, invoices only exist for
-- DELIVERED fulfillments (live-invoice partial unique respected), payments always have a
-- 1:1 transaction, counters are seeded ahead of the max minted number (no drift 409s).

\set ON_ERROR_STOP on
SET search_path = inventorydb;
SET work_mem = '256MB';

-- ── staging picks ────────────────────────────────────────────────────────────────
CREATE TEMP TABLE t_cust AS
  SELECT row_number() OVER () AS rn, id, org_id, name FROM customer;
CREATE INDEX ON t_cust (rn);

CREATE TEMP TABLE t_listing AS
  SELECT pl.org_id, pl.product_id, pl.sales_price, p.name,
         row_number() OVER (PARTITION BY pl.org_id ORDER BY pl.id) AS rn
  FROM product_listing pl JOIN product p ON p.id = pl.product_id
  WHERE pl.status = 'PUBLISHED';
CREATE INDEX ON t_listing (org_id, rn);
CREATE TEMP TABLE t_lcount AS SELECT org_id, max(rn) AS cnt FROM t_listing GROUP BY org_id;

-- ── orders ───────────────────────────────────────────────────────────────────────
CREATE TEMP TABLE t_order AS
SELECT gen_random_uuid() AS id, c.org_id, c.id AS customer_id, c.name AS customer_name,
       g AS seq,
       now() - (random() * interval '540 days') AS placed_at,
       (ARRAY['ONLINE','ONLINE','ONLINE','PHONE','IN_STORE'])[1 + floor(random()*5)::int] AS channel,
       random() AS r
-- NOTE: never `JOIN ... ON rn = random()*N` — a volatile RHS is re-evaluated per candidate
-- pair (Poisson matching, heavy skew). An LCG over g is uniform AND deterministic.
FROM generate_series(1, :orders) g
JOIN t_cust c ON c.rn = 1 + ((g::bigint * 48271 + 12345) % (SELECT max(rn) FROM t_cust));

ALTER TABLE t_order ADD COLUMN status text;
UPDATE t_order SET status = CASE
  WHEN channel = 'IN_STORE' THEN 'CLOSED'                -- in-store sale completes in one txn
  WHEN r < 0.50 THEN 'CLOSED'      WHEN r < 0.62 THEN 'FULFILLED'
  WHEN r < 0.70 THEN 'FULFILLING'  WHEN r < 0.77 THEN 'PAID'
  WHEN r < 0.88 THEN 'PENDING_PAYMENT'
  WHEN r < 0.94 THEN 'CANCELLED'   ELSE 'EXPIRED' END;
CREATE INDEX ON t_order (id);

-- lines: 1-3 per order, random published listing of the same org
CREATE TEMP TABLE t_line AS
SELECT gen_random_uuid() AS id, o.id AS sales_order_id, o.org_id, o.status AS ostatus,
       o.placed_at, l.product_id, left(l.name, 200) AS description,
       1 + floor(random()*4)::int AS quantity, l.sales_price AS unit_price
FROM t_order o
JOIN t_lcount lc ON lc.org_id = o.org_id
CROSS JOIN LATERAL generate_series(1, 1 + (o.seq % 2) + (o.seq % 5) / 4) AS ln
JOIN t_listing l ON l.org_id = o.org_id
               AND l.rn = 1 + (abs(hashtext(o.id::text || ln)) % lc.cnt);
CREATE INDEX ON t_line (sales_order_id);

CREATE TEMP TABLE t_money AS
SELECT sales_order_id,
       sum(quantity * unit_price)::numeric(14,2) AS subtotal,
       (sum(quantity * unit_price) * 0.14)::numeric(14,2) AS tax_total
FROM t_line GROUP BY sales_order_id;
CREATE INDEX ON t_money (sales_order_id);

INSERT INTO sales_order (id, org_id, customer_id, order_number, channel, status,
                         subtotal, tax_total, grand_total, prepaid_amount,
                         created_at, updated_at, placed_at, expires_at,
                         cancelled_at, fulfilled_at, closed_at, expired_at)
SELECT o.id, o.org_id, o.customer_id,
       -- lpad TRUNCATES over-length input; take max() so a big partition can't collide
       'SO-' || lpad((row_number() OVER (PARTITION BY o.org_id ORDER BY o.seq))::text,
                     greatest(6, length((row_number() OVER (PARTITION BY o.org_id ORDER BY o.seq))::text)), '0'),
       o.channel::order_channel, o.status::order_status,
       m.subtotal, m.tax_total, m.subtotal + m.tax_total,
       CASE WHEN o.status IN ('PAID','FULFILLING','FULFILLED','CLOSED')
              THEN m.subtotal + m.tax_total
            WHEN o.status = 'CANCELLED' AND o.r < 0.91            -- paid-then-cancelled half
              THEN m.subtotal + m.tax_total                       -- → feeds the refund set
            WHEN o.status = 'PENDING_PAYMENT' AND o.r < 0.665     -- ~15% of pendings partial
              THEN ((m.subtotal + m.tax_total) * 0.3)::numeric(14,2)
            ELSE 0 END,
       o.placed_at, o.placed_at, o.placed_at,
       CASE WHEN o.status = 'PENDING_PAYMENT' THEN now() + (random() * interval '24 hours')
            WHEN o.status = 'EXPIRED' THEN o.placed_at + interval '24 hours' END,
       CASE WHEN o.status = 'CANCELLED' THEN o.placed_at + interval '2 days' END,
       CASE WHEN o.status IN ('FULFILLED','CLOSED') THEN o.placed_at + interval '4 days' END,
       CASE WHEN o.status = 'CLOSED' THEN o.placed_at + interval '5 days' END,
       CASE WHEN o.status = 'EXPIRED' THEN o.placed_at + interval '24 hours' END
FROM t_order o JOIN t_money m ON m.sales_order_id = o.id;

INSERT INTO sales_order_line (id, sales_order_id, product_id, description, quantity,
                              unit_price, tax_rate, line_subtotal, line_tax, line_total)
SELECT id, sales_order_id, product_id, description, quantity, unit_price, 0.14,
       (quantity * unit_price)::numeric(14,2),
       (quantity * unit_price * 0.14)::numeric(14,2),
       (quantity * unit_price * 1.14)::numeric(14,2)
FROM t_line;

-- ── reservations: ACTIVE for pending, RELEASED for cancelled/expired, else CONSUMED ─
INSERT INTO inventory_reservation (id, org_id, product_id, sales_order_line_id, quantity,
                                   status, created_at, consumed_at, released_at)
SELECT gen_random_uuid(), org_id, product_id, id, quantity,
       CASE WHEN ostatus = 'PENDING_PAYMENT' THEN 'ACTIVE'
            WHEN ostatus IN ('CANCELLED','EXPIRED') THEN 'RELEASED'
            ELSE 'CONSUMED' END::reservation_status,
       placed_at,
       CASE WHEN ostatus NOT IN ('PENDING_PAYMENT','CANCELLED','EXPIRED')
            THEN placed_at + interval '1 hour' END,
       CASE WHEN ostatus IN ('CANCELLED','EXPIRED') THEN placed_at + interval '1 day' END
FROM t_line
WHERE EXISTS (SELECT 1 FROM inventory i
              WHERE i.org_id = t_line.org_id AND i.product_id = t_line.product_id);

-- The schema enforces available >= 0 (inventory_available_non_negative), so Σ ACTIVE per
-- product must not exceed stock. Random reservation generation can overshoot; release the
-- overflow (oldest kept — what the real system would have done) THEN sync reserved_qty to
-- exactly Σ ACTIVE. A least()-cap here is WRONG: it desyncs the invariant and the order-TTL
-- sweeper later underflows reserved_qty when releasing expired orders' holds.
WITH ranked AS (
  SELECT r.id, sum(r.quantity) OVER (PARTITION BY r.org_id, r.product_id
                                     ORDER BY r.created_at, r.id) AS run, i.stock_qty
  FROM inventory_reservation r
  JOIN inventory i ON i.org_id = r.org_id AND i.product_id = r.product_id
  WHERE r.status = 'ACTIVE')
UPDATE inventory_reservation r
SET status = 'RELEASED', released_at = now(), released_reason = 'seed rebalance: exceeded stock'
FROM ranked k WHERE k.id = r.id AND k.run > k.stock_qty;

UPDATE inventory i SET reserved_qty = coalesce(a.q, 0)
FROM (SELECT org_id, product_id, sum(quantity) AS q
      FROM inventory_reservation WHERE status = 'ACTIVE'
      GROUP BY org_id, product_id) a
WHERE a.org_id = i.org_id AND a.product_id = i.product_id;

-- ── fulfillments: one per order past PAID (+ a FAILED slice on CLOSED) ───────────
CREATE TEMP TABLE t_ful AS
SELECT gen_random_uuid() AS id, o.id AS order_id, o.org_id, o.placed_at, o.status AS ostatus,
       CASE WHEN o.status = 'FULFILLING' AND o.r < 0.74 THEN 'PENDING'
            WHEN o.status = 'FULFILLING' THEN 'SHIPPED'
            ELSE 'DELIVERED' END AS fstatus
FROM t_order o
WHERE o.status IN ('FULFILLING','FULFILLED','CLOSED');

INSERT INTO fulfillment (id, org_id, sales_order_id, status, carrier, tracking_number,
                         shipped_at, delivered_at, created_at, updated_at)
SELECT id, org_id, order_id, fstatus::fulfillment_status, 'Bosta', 'TRK' || abs(hashtext(id::text)),
       CASE WHEN fstatus IN ('SHIPPED','DELIVERED') THEN placed_at + interval '1 day' END,
       CASE WHEN fstatus = 'DELIVERED' THEN placed_at + interval '4 days' END,
       placed_at + interval '12 hours', placed_at + interval '12 hours'
FROM t_ful;

-- 2% of CLOSED orders also carry a resolved FAILED shipment (failure story reads)
INSERT INTO fulfillment (id, org_id, sales_order_id, status, failed_at, failed_reason,
                         resolution, created_at, updated_at)
SELECT gen_random_uuid(), org_id, order_id, 'FAILED', placed_at + interval '3 days',
       'package refused', (ARRAY['REFUNDED','REPLACED'])[1 + floor(random()*2)::int],
       placed_at + interval '10 hours', placed_at + interval '3 days'
FROM t_ful WHERE ostatus = 'CLOSED' AND random() < 0.02;

INSERT INTO fulfillment_line (id, fulfillment_id, sales_order_line_id, quantity)
SELECT gen_random_uuid(), f.id, sol.id, sol.quantity
FROM t_ful f JOIN t_line sol ON sol.sales_order_id = f.order_id;

-- ── invoices: one live invoice per DELIVERED fulfillment ─────────────────────────
CREATE TEMP TABLE t_inv AS
SELECT gen_random_uuid() AS id, f.id AS fulfillment_id, f.order_id, f.org_id,
       o.customer_id, o.customer_name, m.subtotal, m.tax_total, f.placed_at,
       CASE WHEN f.ostatus = 'CLOSED' THEN 'PAID'
            WHEN random() < 0.4 THEN 'PAID' ELSE 'ISSUED' END AS istatus
FROM t_ful f
JOIN t_order o ON o.id = f.order_id
JOIN t_money m ON m.sales_order_id = f.order_id
WHERE f.fstatus = 'DELIVERED';

INSERT INTO sales_invoice (id, org_id, customer_id, sales_order_id, fulfillment_id,
                           invoice_number, status, subtotal, tax_total, grand_total,
                           customer_name, paid_amount, issued_at, created_at, updated_at)
SELECT id, org_id, customer_id, order_id, fulfillment_id,
       'INV-' || extract(year FROM placed_at)::int || '-' ||
         lpad((row_number() OVER (PARTITION BY org_id, extract(year FROM placed_at)
                                  ORDER BY placed_at))::text, 6, '0'),
       istatus::invoice_status, subtotal, tax_total, subtotal + tax_total,
       coalesce(customer_name, 'Walk-in'),
       CASE WHEN istatus = 'PAID' THEN subtotal + tax_total
            ELSE ((subtotal + tax_total) * CASE WHEN random() < 0.3 THEN 0.5 ELSE 0 END)::numeric(14,2) END,
       placed_at + interval '4 days', placed_at + interval '4 days', placed_at + interval '4 days'
FROM t_inv;

INSERT INTO sales_invoice_line (id, sales_invoice_id, product_id, description, quantity,
                                unit_price, tax_rate, line_subtotal, line_tax, line_total)
SELECT gen_random_uuid(), i.id, sol.product_id, sol.description, sol.quantity, sol.unit_price,
       0.14, (sol.quantity * sol.unit_price)::numeric(14,2),
       (sol.quantity * sol.unit_price * 0.14)::numeric(14,2),
       (sol.quantity * sol.unit_price * 1.14)::numeric(14,2)
FROM t_inv i JOIN t_line sol ON sol.sales_order_id = i.order_id;

-- counters seeded ahead of the minted numbers (number_sequence_integrity)
INSERT INTO invoice_number_counter (org_id, year, next_val)
SELECT org_id, split_part(invoice_number, '-', 2)::int,
       max(split_part(invoice_number, '-', 3)::int) + 1
FROM sales_invoice GROUP BY org_id, split_part(invoice_number, '-', 2)::int;

-- ── money: 1:1 transaction+payment for every prepaid order, plus queue noise ─────
CREATE TEMP TABLE t_pay AS
SELECT gen_random_uuid() AS pay_id, gen_random_uuid() AS txn_id, o.id AS order_id,
       o.org_id, o.customer_id, o.placed_at, o.status AS ostatus, o.r,
       so.prepaid_amount AS amount,
       EXISTS (SELECT 1 FROM t_inv iv WHERE iv.order_id = o.id) AS invoiced
FROM t_order o JOIN sales_order so ON so.id = o.id
WHERE so.prepaid_amount > 0;

INSERT INTO payment_transaction (id, org_id, provider, provider_ref, direction, amount,
                                 verification_status, verified_at, reconciliation_status,
                                 occurred_at, recorded_at, created_at, updated_at)
SELECT txn_id, org_id,
       CASE WHEN random() < 0.8 THEN 'instapay_manual' ELSE 'instapay_in_store' END::payment_provider,
       'SEED-' || org_id || '-' || pay_id, 'CREDIT', amount, 'VERIFIED',
       placed_at + interval '30 minutes', 'MATCHED',
       placed_at + interval '20 minutes', placed_at + interval '30 minutes',
       placed_at + interval '30 minutes', placed_at + interval '30 minutes'
FROM t_pay;

INSERT INTO payment (id, org_id, customer_id, sales_order_id, payment_transaction_id,
                     amount, unallocated_amount, status, received_at, disputed_at,
                     dispute_reason, created_at, updated_at)
SELECT pay_id, org_id, customer_id, order_id, txn_id, amount,
       CASE WHEN invoiced THEN 0
            WHEN random() < 0.05 THEN (amount * 0.1)::numeric(14,2)   -- overpay remainder slice
            ELSE amount END,
       CASE WHEN r < 0.01 THEN 'DISPUTED'
            WHEN invoiced THEN 'ALLOCATED' ELSE 'RECEIVED' END::payment_status,
       placed_at + interval '30 minutes',
       CASE WHEN r < 0.01 THEN placed_at + interval '3 days' END,
       CASE WHEN r < 0.01 THEN 'customer claims wrong amount' END,
       placed_at + interval '30 minutes', placed_at + interval '30 minutes'
FROM t_pay;

INSERT INTO payment_allocation (id, org_id, payment_id, sales_invoice_id, amount, received_at)
SELECT gen_random_uuid(), p.org_id, p.pay_id, i.id,
       least(p.amount, i.subtotal + i.tax_total), p.placed_at + interval '30 minutes'
FROM t_pay p JOIN t_inv i ON i.order_id = p.order_id
WHERE p.invoiced;

-- orphan + unverified queue noise: ~1.5% extra transactions with no payment
INSERT INTO payment_transaction (id, org_id, provider, provider_ref, direction, amount,
                                 verification_status, reconciliation_status, occurred_at,
                                 recorded_at, created_at, updated_at)
SELECT gen_random_uuid(), o.id, 'instapay_manual', 'ORPH-' || o.id || '-' || g,
       'CREDIT', (random() * 3000 + 50)::numeric(14,2),
       CASE WHEN g % 3 = 0 THEN 'UNVERIFIED' ELSE 'VERIFIED' END::payment_verification_status,
       CASE WHEN g % 3 = 0 THEN 'PENDING' ELSE 'ORPHAN' END::payment_reconciliation_status,
       now() - (random() * interval '60 days'), now() - (random() * interval '60 days'),
       now(), now()
FROM org o CROSS JOIN generate_series(1, 30) g;

-- ── refunds: cancelled-order prepayments + a slice of credit-note-backed ─────────
INSERT INTO refund (id, org_id, customer_id, payment_id, amount, status, method,
                    executed_at, created_at, updated_at)
SELECT gen_random_uuid(), p.org_id, p.customer_id, p.pay_id, p.amount,
       CASE WHEN random() < 0.15 THEN 'PENDING'
            WHEN random() < 0.05 THEN 'CANCELLED' ELSE 'EXECUTED' END::refund_status,
       'instapay_manual',
       CASE WHEN random() >= 0.15 THEN p.placed_at + interval '6 days' END,
       p.placed_at + interval '5 days', p.placed_at + interval '5 days'
FROM t_pay p
WHERE p.ostatus = 'CANCELLED';   -- t_pay already narrows to prepaid > 0

-- credit notes against ~1.5% of invoices, each with a linked refund
CREATE TEMP TABLE t_cn AS
SELECT gen_random_uuid() AS id, i.id AS invoice_id, i.org_id, i.customer_id,
       i.placed_at, (i.subtotal * 0.25)::numeric(14,2) AS sub
FROM t_inv i WHERE random() < 0.015;

INSERT INTO credit_note (id, org_id, customer_id, sales_invoice_id, reason, subtotal,
                         tax_total, total, credit_note_number, issued_at, status,
                         created_at, updated_at)
SELECT id, org_id, customer_id, invoice_id, 'RETURN', sub, (sub * 0.14)::numeric(14,2),
       (sub * 1.14)::numeric(14,2),
       'CN-' || extract(year FROM placed_at)::int || '-' ||
         lpad((row_number() OVER (PARTITION BY org_id, extract(year FROM placed_at)
                                  ORDER BY placed_at))::text, 6, '0'),
       placed_at + interval '10 days',
       CASE WHEN random() < 0.7 THEN 'SETTLED' ELSE 'ISSUED' END::credit_note_status,
       placed_at + interval '10 days', placed_at + interval '10 days'
FROM t_cn;

INSERT INTO credit_note_line (id, credit_note_id, description, quantity, unit_price,
                              tax_rate, line_subtotal, line_tax, line_total)
SELECT gen_random_uuid(), id, 'returned item', 1, sub, 0.14, sub,
       (sub * 0.14)::numeric(14,2), (sub * 1.14)::numeric(14,2)
FROM t_cn;

INSERT INTO credit_note_number_counter (org_id, year, next_val)
SELECT org_id, split_part(credit_note_number, '-', 2)::int,
       max(split_part(credit_note_number, '-', 3)::int) + 1
FROM credit_note GROUP BY org_id, split_part(credit_note_number, '-', 2)::int;

INSERT INTO refund (id, org_id, customer_id, credit_note_id, amount, status, method,
                    executed_at, created_at, updated_at)
SELECT gen_random_uuid(), org_id, customer_id, id, (sub * 1.14)::numeric(14,2),
       CASE WHEN random() < 0.8 THEN 'EXECUTED' ELSE 'PENDING' END::refund_status, 'instapay_manual',
       placed_at + interval '12 days', placed_at + interval '11 days', placed_at + interval '11 days'
FROM t_cn;

-- ── inventory_log: the big append-only ledger, coherent running balances ─────────
CREATE TEMP TABLE t_tracked AS
  SELECT row_number() OVER () AS rn, org_id, product_id FROM inventory;
CREATE INDEX ON t_tracked (rn);

CREATE TEMP TABLE t_ev AS
SELECT t.org_id, t.product_id,
       CASE WHEN random() < 0.45 THEN 'SOLD' WHEN random() < 0.8 THEN 'RESTOCK'
            ELSE 'ADJUSTMENT' END AS reason,
       now() - (random() * interval '540 days') AS created_at,
       g AS seq
FROM generate_series(1, :logrows) g
JOIN t_tracked t ON t.rn = 1 + ((g::bigint * 69621 + 7) % (SELECT max(rn) FROM t_tracked));

-- id is BIGSERIAL — let the sequence fill it
INSERT INTO inventory_log (org_id, product_id, stock_delta, reserved_delta,
                           stock_after, reserved_after, reason, actor_type, created_at)
SELECT org_id, product_id, delta, 0,
       greatest(0, sum(delta) OVER (PARTITION BY product_id ORDER BY created_at, seq)), 0,
       reason::stock_reason, 'USER', created_at
FROM (SELECT *, CASE WHEN reason = 'SOLD' THEN -(1 + floor(random()*3)::int)
                     WHEN reason = 'RESTOCK' THEN (10 + floor(random()*90)::int)
                     ELSE (floor(random()*21) - 10)::int END AS delta
      FROM t_ev) d;

ANALYZE;
