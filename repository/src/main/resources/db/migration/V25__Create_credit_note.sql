CREATE TYPE credit_note_reason AS ENUM (
    'RETURN',
    'CANCELLATION',
    'PRICING_ERROR',
    'GOODWILL',
    'DISPUTE_RESOLUTION'
);

CREATE TYPE credit_note_status AS ENUM (
    'DRAFT',
    'ISSUED',
    'SETTLED',
    'VOID'
);

CREATE TABLE credit_note (
    id                  UUID               PRIMARY KEY,
    org_id              UUID               NOT NULL REFERENCES org(id),
    customer_id         UUID               REFERENCES customer(id),

    -- Every CreditNote credits something previously billed. Overpayments /
    -- unallocated payments have no invoice and are refunded directly from the
    -- Payment instead — they do not flow through this table.
    sales_invoice_id    UUID               NOT NULL REFERENCES sales_invoice(id),

    reason              credit_note_reason NOT NULL,
    reason_note         TEXT,

    -- Frozen totals (snapshot at issuance)
    subtotal            NUMERIC(14,2)      NOT NULL,
    tax_total           NUMERIC(14,2)      NOT NULL DEFAULT 0,
    total               NUMERIC(14,2)      NOT NULL,

    currency            CHAR(3)            NOT NULL DEFAULT 'EGP',

    -- Gapless per-org per-year (Egyptian tax compliance for sales-side documents).
    credit_note_number  TEXT               NOT NULL,
    issued_at           TIMESTAMPTZ,                       -- NULL while DRAFT

    status              credit_note_status NOT NULL,

    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    UNIQUE (org_id, credit_note_number)
);

CREATE TABLE credit_note_line (
    id              UUID           PRIMARY KEY,
    credit_note_id  UUID           NOT NULL REFERENCES credit_note(id) ON DELETE CASCADE,
    product_id      UUID           REFERENCES product(id),    -- nullable for non-product credits
    description     TEXT           NOT NULL,                  -- snapshotted; survives product rename/delete
    quantity        INTEGER        NOT NULL CHECK (quantity > 0),
    unit_price      NUMERIC(14,2)  NOT NULL,
    tax_rate        NUMERIC(6,4)   NOT NULL DEFAULT 0,
    line_subtotal   NUMERIC(14,2)  NOT NULL,
    line_tax        NUMERIC(14,2)  NOT NULL DEFAULT 0,
    line_total      NUMERIC(14,2)  NOT NULL
);
