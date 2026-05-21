CREATE TYPE reservation_status AS ENUM (
    'ACTIVE',
    'CONSUMED',
    'RELEASED'
);

CREATE TABLE inventory_reservation (
    id                   uuid               PRIMARY KEY,
    org_id               uuid               NOT NULL REFERENCES org(id),
    product_id           uuid               NOT NULL REFERENCES product(id),
    sales_order_line_id  uuid               NOT NULL REFERENCES sales_order_line(id),
    quantity             integer            NOT NULL CHECK (quantity > 0),
    status               reservation_status NOT NULL,
    expires_at           TIMESTAMPTZ,        -- mirrors SalesOrder.expires_at while ACTIVE
    created_at           TIMESTAMPTZ        NOT NULL DEFAULT now(),
    consumed_at          TIMESTAMPTZ,
    released_at          TIMESTAMPTZ,
    released_reason      TEXT,               -- 'CANCELLED' | 'EXPIRED' | 'ADMIN'
    CHECK ((status='ACTIVE'   AND consumed_at IS NULL AND released_at IS NULL)
        OR (status='CONSUMED' AND consumed_at IS NOT NULL)
        OR (status='RELEASED' AND released_at IS NOT NULL))
);

CREATE INDEX idx_reservation_active ON inventory_reservation (org_id, product_id) WHERE status='ACTIVE';