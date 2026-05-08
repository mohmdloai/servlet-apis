CREATE TABLE sales_order (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES org(id),
    customer_id UUID REFERENCES customer(id),
    order_number TEXT NOT NULL,
    channel order_channel NOT NULL,
    status order_status NOT NULL,
    -- Cached totals
    subtotal NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax_total NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount_total NUMERIC(14,2) NOT NULL DEFAULT 0,
    grand_total NUMERIC(14,2) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL DEFAULT 'EGP',

    prepaid_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (prepaid_amount >= 0),

    -- Idempotency key for safe retries
    idempotency_key   text,
    UNIQUE (org_id, idempotency_key),

    -- Timestamps
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    placed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    fulfilled_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,

    notes TEXT,

    UNIQUE (org_id, order_number)



);