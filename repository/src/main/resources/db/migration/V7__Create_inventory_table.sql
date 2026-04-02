CREATE TABLE inventory (
    product_id   UUID             NOT NULL,
    stock_qty    non_negative_int NOT NULL DEFAULT 0,
    reserved_qty non_negative_int NOT NULL DEFAULT 0,
    -- Optimistic locking: service reads version, updates WHERE version = :read_version.
    -- 0 rows updated = stale read = retry.
    version      BIGINT           NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ      NOT NULL DEFAULT NOW(),

    CONSTRAINT inventory_pk                     PRIMARY KEY (product_id),
    CONSTRAINT inventory_product_fk             FOREIGN KEY (product_id) REFERENCES product(id),
    -- available = stock_qty - reserved_qty must never go negative
    CONSTRAINT inventory_available_non_negative CHECK (stock_qty >= reserved_qty)
);
