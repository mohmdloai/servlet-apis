CREATE TABLE payment_allocation (
    id                UUID           PRIMARY KEY,
    org_id            UUID           NOT NULL REFERENCES org(id),
    payment_id        UUID           NOT NULL REFERENCES payment(id),
    sales_invoice_id  UUID           NOT NULL REFERENCES sales_invoice(id),
    amount            NUMERIC(14,2)  NOT NULL CHECK (amount > 0),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    -- A given payment is allocated against a given invoice at most once.
    -- Combine multiple line allocations into one row by summing.
    UNIQUE (payment_id, sales_invoice_id)
);

CREATE INDEX idx_alloc_invoice ON payment_allocation (sales_invoice_id);
CREATE INDEX idx_alloc_payment ON payment_allocation (payment_id);
