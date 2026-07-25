-- Honest coupon codes (roadmap item 9, stories/honest_coupons.md).
--
-- A merchant-issued code that reduces the REAL total, shown transparently at checkout. This fills
-- the `sales_order.discount_total` field that roadmap item 5 deliberately left at zero. The honesty
-- rule stands untouched: no compare-at price, no was–now, no "% OFF" badge on a listing, no
-- countdown. A coupon is a genuine discount on the money actually charged, and the only places it is
-- ever shown are the checkout and the order surfaces.
--
-- Re-run jOOQ codegen after this migration (mvn generate-sources -Pcodegen -pl repository).

CREATE TABLE coupon (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID         NOT NULL REFERENCES org(id),
    -- Stored NORMALIZED UPPER (the service trims + upper-cases through Text), so "ramadan10" and
    -- "RAMADAN10 " are the same code and the unique index actually prevents duplicates.
    code            VARCHAR(40)  NOT NULL,
    type            TEXT         NOT NULL CHECK (type IN ('PERCENT', 'FIXED')),
    -- PERCENT: 0 < value <= 100 (the upper bound is service-enforced — the column is shared).
    -- FIXED:   an EGP amount > 0, capped at the order subtotal when applied.
    value           NUMERIC(12,2) NOT NULL CHECK (value > 0),
    min_subtotal    NUMERIC(12,2) CHECK (min_subtotal >= 0),   -- nullable = no minimum
    starts_at       TIMESTAMPTZ,                               -- nullable = live immediately
    expires_at      TIMESTAMPTZ,                               -- nullable = never expires
    max_redemptions INT          CHECK (max_redemptions > 0),  -- nullable = unlimited
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (org_id, code)
);

-- The admin list is "this org's coupons, newest first".
CREATE INDEX coupon_org_recent_idx ON coupon (org_id, created_at DESC);

-- The order's coupon: the id for the redemption-count query and the FK integrity, plus a FROZEN
-- code snapshot for display. The snapshot is not redundant — a coupon may be deleted (only while
-- never redeemed, but a future policy could widen that) and an order must still be able to say
-- which code the customer used, exactly as invoice lines freeze their description.
ALTER TABLE sales_order
    ADD COLUMN coupon_id   UUID REFERENCES coupon(id),
    ADD COLUMN coupon_code VARCHAR(40);

-- Redemption counting is a QUERY, not a counter column: live redemptions = orders with this
-- coupon_id whose status is not CANCELLED/EXPIRED. So a cancelled or expired order frees its slot
-- automatically and there is no counter that can drift from reality. This partial index is what
-- makes that query cheap; the placement path reads it under a FOR UPDATE on the coupon row.
CREATE INDEX sales_order_coupon_idx ON sales_order (coupon_id) WHERE coupon_id IS NOT NULL;
