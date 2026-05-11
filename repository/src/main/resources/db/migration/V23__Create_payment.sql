CREATE TABLE payment (
    id                     UUID           PRIMARY KEY,
    org_id                 UUID           NOT NULL REFERENCES org(id),

    -- Nullable for cash sales to walk-ins without a CRM record.
    customer_id            UUID           REFERENCES customer(id),

    -- Which order this money is for; set on MATCHED reconciliation.
    -- Nullable to allow orphan/unmatched Payments (rare admin path).
    sales_order_id         UUID           REFERENCES sales_order(id),

    -- 1:1 with the underlying provider-level event.
    payment_transaction_id UUID           NOT NULL UNIQUE REFERENCES payment_transaction(id),

    amount                 NUMERIC(14,2)  NOT NULL CHECK (amount > 0),
    currency               CHAR(3)        NOT NULL DEFAULT 'EGP',

    -- Cached: amount - SUM(payment_allocation.amount). Updated transactionally
    -- when allocations change. For online prepayments this equals `amount`
    -- until the order's invoice is issued at delivery.
    unallocated_amount     NUMERIC(14,2)  NOT NULL CHECK (unallocated_amount >= 0),

    status                 payment_status NOT NULL,

    received_at            TIMESTAMPTZ    NOT NULL,
    notes                  TEXT,

    created_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- Hot path for FIFO allocation lookup at invoice issuance:
--   ... WHERE sales_order_id = :order AND unallocated_amount > 0
--   ORDER BY received_at ASC, id ASC FOR UPDATE
CREATE INDEX idx_payment_order_unallocated ON payment (sales_order_id)
    WHERE unallocated_amount > 0;

-- Admin queue: payments verified+received but not yet matched to any order.
CREATE INDEX idx_payment_unmatched ON payment (org_id, received_at)
    WHERE sales_order_id IS NULL
      AND status = 'RECEIVED';
