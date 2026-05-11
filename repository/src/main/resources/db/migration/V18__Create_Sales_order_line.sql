CREATE TABLE sales_order_line (
    id              UUID           PRIMARY KEY,
    sales_order_id  UUID           NOT NULL REFERENCES sales_order(id) ON DELETE CASCADE,
    product_id      UUID           NOT NULL REFERENCES product(id),
    description     TEXT           NOT NULL,             -- snapshotted for stable display
    quantity        integer        NOT NULL CHECK (quantity > 0),
    unit_price      NUMERIC(14,2)  NOT NULL,             -- price-at-order-time
    tax_rate        NUMERIC(6,4)   NOT NULL DEFAULT 0,
    line_subtotal   NUMERIC(14,2)  NOT NULL,
    line_tax        NUMERIC(14,2)  NOT NULL DEFAULT 0,
    line_total      NUMERIC(14,2)  NOT NULL
);