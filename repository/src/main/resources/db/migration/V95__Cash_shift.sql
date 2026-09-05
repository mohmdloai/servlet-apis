-- stories/cash_shift.md — the cash drawer's day. One OPEN shift per org (partial unique index),
-- a stamp on every drawer transaction, movements with a reason, and the per-org gate knob.
-- No backfill: rows before V95 belong to no shift; the first shift starts clean.

CREATE TABLE cash_shift (
    id             UUID PRIMARY KEY,
    org_id         UUID NOT NULL REFERENCES org(id),
    opened_by      UUID NOT NULL REFERENCES app_user(id),
    opened_at      TIMESTAMPTZ NOT NULL,
    -- Opened by the first counter sale of the day (no one tapped Open); the client offers
    -- "fix the float" once for such a shift.
    auto_opened    BOOLEAN NOT NULL DEFAULT false,
    starting_cash  NUMERIC(14,2) NOT NULL CHECK (starting_cash >= 0),
    closed_by      UUID REFERENCES app_user(id),
    closed_at      TIMESTAMPTZ,
    counted_cash   NUMERIC(14,2) CHECK (counted_cash >= 0),
    -- Derived from the stamped ledger + movements at the moment of close, then frozen.
    expected_cash  NUMERIC(14,2),
    note           TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_cash_shift_close CHECK (
        (closed_at IS NULL AND closed_by IS NULL AND counted_cash IS NULL AND expected_cash IS NULL)
        OR (closed_at IS NOT NULL AND closed_by IS NOT NULL AND counted_cash IS NOT NULL
            AND expected_cash IS NOT NULL))
);

COMMENT ON TABLE cash_shift IS
    'One drawer-day (stories/cash_shift.md): float in, counted cash out, expected cash derived from the stamped payment_transaction rows + cash_movement. Closes once; corrected forward, never edited.';

-- v1 = one counter per org. A second open shift is impossible by construction; a `register`
-- column is the seam for a second till and is deliberately not here.
CREATE UNIQUE INDEX ux_cash_shift_open ON cash_shift (org_id) WHERE closed_at IS NULL;
CREATE INDEX ix_cash_shift_org_opened ON cash_shift (org_id, opened_at DESC);

CREATE TYPE cash_movement_kind AS ENUM ('PAY_IN', 'PAY_OUT');

CREATE TABLE cash_movement (
    id            UUID PRIMARY KEY,
    org_id        UUID NOT NULL REFERENCES org(id),
    shift_id      UUID NOT NULL REFERENCES cash_shift(id),
    kind          cash_movement_kind NOT NULL,
    amount        NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    reason        TEXT NOT NULL CHECK (length(reason) BETWEEN 1 AND 200),
    recorded_by   UUID NOT NULL REFERENCES app_user(id),
    recorded_at   TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE cash_movement IS
    'Cash that entered or left the drawer outside a sale: a pay-in (change brought, a float top-up) or a pay-out (bank drop, a supplier paid from the till). Reason required — a pay-out without one is the drawer leaking.';

CREATE INDEX ix_cash_movement_shift ON cash_movement (shift_id, recorded_at);

-- The stamp: set at write time inside the sale's / return's transaction on every row that
-- touches the drawer (CASH either direction) plus the counter's InstaPay tenders, so the shift
-- slip totals the counter's receipts. Online transfers reconciled at the desk carry NULL.
ALTER TABLE payment_transaction ADD COLUMN cash_shift_id UUID REFERENCES cash_shift(id);
CREATE INDEX ix_payment_transaction_shift ON payment_transaction (cash_shift_id)
    WHERE cash_shift_id IS NOT NULL;

COMMENT ON COLUMN payment_transaction.cash_shift_id IS
    'The cash_shift this drawer event belongs to (stories/cash_shift.md); NULL for rows that never touched the counter and for everything before V95.';

-- Off by default: a shop of one has nobody to gate. On: a counter sale with no open shift is
-- refused (409 SHIFT_REQUIRED) instead of auto-opening one.
ALTER TABLE org ADD COLUMN shift_required BOOLEAN NOT NULL DEFAULT false;
