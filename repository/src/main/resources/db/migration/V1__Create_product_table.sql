CREATE TABLE product (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255)    NOT NULL,
    description TEXT,
    base_price  NUMERIC(12, 2)  NOT NULL CHECK (base_price >= 0),
    sku         VARCHAR(100)    NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT product_sku_unique UNIQUE (sku)
);
