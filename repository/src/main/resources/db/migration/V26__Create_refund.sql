CREATE TYPE refund_status AS ENUM (
    'PENDING',
    'EXECUTED',
    'CANCELLED'
);

CREATE TABLE refund (
    id                     UUID             PRIMARY KEY,
    org_id                 UUID             NOT NULL REFERENCES org(id),
    customer_id            UUID             REFERENCES customer(id),

    -- Authorization source: exactly ONE of these must be set.
    --   credit_note_id → returns / cancellations / pricing errors / goodwill / dispute resolution
    --   payment_id     → overpayments / unallocated payments (no invoice was issued for the excess)
    credit_note_id         UUID             REFERENCES credit_note(id),
    payment_id             UUID             REFERENCES payment(id),
    CHECK ((credit_note_id IS NOT NULL) <> (payment_id IS NOT NULL)),

    amount                 NUMERIC(14,2)    NOT NULL CHECK (amount > 0),
    currency               CHAR(3)          NOT NULL DEFAULT 'EGP',

    status                 refund_status    NOT NULL,

    -- The DEBIT Transaction created when the refund executes. NULL while PENDING.
    payment_transaction_id UUID             UNIQUE REFERENCES payment_transaction(id),

    -- Refund channel reuses the payment_provider enum (same value set, since a
    -- refund is just an outbound transfer over one of the same rails).
    method                 payment_provider NOT NULL,

    executed_at            TIMESTAMPTZ,
    cancelled_at           TIMESTAMPTZ,
    cancelled_reason       TEXT,
    notes                  TEXT,

    created_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE TABLE refund_allocation (
    id                    UUID           PRIMARY KEY,
    refund_id             UUID           NOT NULL REFERENCES refund(id) ON DELETE CASCADE,

    -- A refund "undoes" a specific PaymentAllocation (or part of one). Used ONLY
    -- for CreditNote-backed refunds; direct-from-Payment refunds (overpayments)
    -- have no underlying allocation to unwind, so they have zero rows here.
    payment_allocation_id UUID           NOT NULL REFERENCES payment_allocation(id),
    amount                NUMERIC(14,2)  NOT NULL CHECK (amount > 0),

    UNIQUE (refund_id, payment_allocation_id)
);
