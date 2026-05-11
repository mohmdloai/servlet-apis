CREATE TYPE payment_provider AS ENUM (
    'instapay_manual',
    'instapay_in_store',
    'cash'
);

-- CREDIT = money in (sale); DEBIT = money out (refund). Not an accounting flag.
CREATE TYPE payment_direction AS ENUM (
    'CREDIT',
    'DEBIT'
);

-- State machine D
CREATE TYPE payment_verification_status AS ENUM (
    'UNVERIFIED',
    'VERIFIED',
    'NOT_FOUND',
    'ABANDONED'
);

-- State machine E (only meaningful after VERIFIED)
CREATE TYPE payment_reconciliation_status AS ENUM (
    'PENDING',
    'MATCHED',
    'ORPHAN',
    'UNDERPAID',
    'OVERPAID'
);

CREATE TABLE payment_transaction (
    id                    UUID                          PRIMARY KEY,
    org_id                UUID                          NOT NULL REFERENCES org(id),

    provider              payment_provider              NOT NULL,
    provider_ref          TEXT                          NOT NULL,

    direction             payment_direction             NOT NULL,

    amount                NUMERIC(14,2)                 NOT NULL CHECK (amount > 0),
    currency              CHAR(3)                       NOT NULL DEFAULT 'EGP',

    verification_status   payment_verification_status   NOT NULL,
    verified_by           UUID                          REFERENCES app_user(id),
    verified_at           TIMESTAMPTZ,
    verification_proof    TEXT,

    reconciliation_status payment_reconciliation_status,

    -- Times
    occurred_at           TIMESTAMPTZ    NOT NULL,
    recorded_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    -- Customer-supplied claim (manual flow only)
    claimed_by_customer_id UUID          REFERENCES customer(id),
    customer_note          TEXT,

    -- Raw provider payload for audit (PSP webhooks,SCREEN SHOT: manual entry blob)
    raw_payload           JSONB,

    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    -- Natural idempotency key: a real-world money event is recorded at most once.
    UNIQUE (provider, provider_ref)
);

-- Admin queue: manual InstaPay claims awaiting verification.
CREATE INDEX idx_txn_unverified ON payment_transaction (org_id, recorded_at)
    WHERE verification_status = 'UNVERIFIED';

-- Admin queue: verified arrivals not yet matched to an order.
CREATE INDEX idx_txn_orphan ON payment_transaction (org_id, recorded_at)
    WHERE reconciliation_status = 'ORPHAN';
