-- A counter discount on an in-store sale, behind a MANAGER gate (stories/counter_discount.md).
--
-- The in-store POS shipped with discount_total = 0 and the note "a counter discount is a different
-- authority question". This answers it: a MANAGER (or OWNER, or platform ADMIN) may take a
-- percentage or a fixed amount off the whole ticket, and the sale records WHO granted it, WHAT it
-- was, and optionally WHY. STAFF asking for the same thing get a 403 that names the role.
--
-- discount_total (V17) stays THE MONEY — every invoice proration, the CLOSED roll-up and the FIFO
-- allocator already read it. These four columns say why it is non-zero for a counter sale:
--   type   — PERCENT or FIXED, the coupon's two meanings (CouponType), so a report can print "10%"
--            instead of reverse-engineering it from the piastres;
--   value  — the rate for PERCENT ((0, 100]) or the amount for FIXED (> 0);
--   reason — free text the manager typed, optional;
--   by     — the granting user. The order row IS the audit: an org-plane money action is answered
--            by its own row, the way a refund's executed_by is — platform_audit is the platform
--            plane's ledger, not this one.
--
-- Deliberately receipt-level (one discount off the ticket, not per line) and deliberately
-- exclusive with a coupon: the in-store POS takes no code in v1, and the CHECK below pins that so a
-- future "both" is a decision, not an accident.

ALTER TABLE sales_order
    ADD COLUMN counter_discount_type   VARCHAR(10)
        CHECK (counter_discount_type IN ('PERCENT', 'FIXED')),
    ADD COLUMN counter_discount_value  NUMERIC(12,2),
    ADD COLUMN counter_discount_reason TEXT,
    ADD COLUMN counter_discount_by     UUID REFERENCES app_user(id),
    -- all-or-nothing: a discount is a (type, value, by) triple; reason is optional
    ADD CONSTRAINT ck_so_counter_discount_shape CHECK (
        (counter_discount_type IS NULL) = (counter_discount_value IS NULL)
        AND (counter_discount_type IS NULL) = (counter_discount_by IS NULL)
        AND (counter_discount_type IS NOT NULL OR counter_discount_reason IS NULL)),
    -- one discount authority per order: a coupon OR a counter discount, never both (v1)
    ADD CONSTRAINT ck_so_one_discount_source CHECK (
        coupon_id IS NULL OR counter_discount_type IS NULL);

COMMENT ON COLUMN sales_order.counter_discount_type IS
    'PERCENT or FIXED — how counter_discount_value became discount_total (DiscountMath, the coupon''s arithmetic). NULL when the sale carried no counter discount.';
COMMENT ON COLUMN sales_order.counter_discount_value IS
    'The rate (PERCENT, (0, 100]) or the amount in EGP (FIXED, > 0) the manager keyed. discount_total is the money; this is the intent.';
COMMENT ON COLUMN sales_order.counter_discount_reason IS
    'Optional free text the granting manager typed (Text.normalizeText, <= 200 chars). Only ever set beside a type.';
COMMENT ON COLUMN sales_order.counter_discount_by IS
    'The app_user who granted the discount — the caller of the sale, who had to hold MANAGER+ (or platform ADMIN) for the request to be accepted. The audit of the grant.';

-- No index and no backfill. Nothing queries orders by discount (a "discounts given" report is a
-- later slice and will aggregate per org over discount_total > 0, which the org_id index serves),
-- and every existing order has none.
