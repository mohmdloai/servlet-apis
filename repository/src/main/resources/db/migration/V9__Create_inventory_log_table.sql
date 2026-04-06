CREATE TABLE inventory_log (
    id               BIGSERIAL     PRIMARY KEY,
    product_id       UUID          NOT NULL,
    stock_delta      INT           NOT NULL DEFAULT 0,
    reserved_delta   INT           NOT NULL DEFAULT 0,
    stock_after      INT           NOT NULL,
    reserved_after   INT           NOT NULL,
    reason           stock_reason  NOT NULL,
    order_id         UUID,
    actor_id         VARCHAR(100),
    actor_type       actor_type,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT inventory_log_product_fk
        FOREIGN KEY (product_id) REFERENCES product(id)
);
