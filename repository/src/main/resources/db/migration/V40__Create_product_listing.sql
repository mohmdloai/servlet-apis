-- Catalog: the public, storefront-facing version of a Product. Decoupled from `product` so
-- internal data (cost, supplier, base_price, barcode) never leaks to customers, and so the
-- storefront hot path scans a slim table. One listing per product.
CREATE TYPE listing_status AS ENUM ('DRAFT', 'PUBLISHED', 'ARCHIVED');

CREATE TABLE product_listing (
    id             UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         UUID           NOT NULL REFERENCES org(id),
    product_id     UUID           NOT NULL REFERENCES product(id),
    title          VARCHAR(255)   NOT NULL,
    marketing_copy TEXT,
    slug           VARCHAR(255)   NOT NULL,
    -- Public price, decoupled from the internal product.base_price.
    sales_price    NUMERIC(12, 2) NOT NULL CHECK (sales_price >= 0),
    status         listing_status NOT NULL DEFAULT 'DRAFT',
    published_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT product_listing_org_product_unique UNIQUE (org_id, product_id),
    CONSTRAINT product_listing_org_slug_unique    UNIQUE (org_id, slug)
);

CREATE INDEX product_listing_org_idx ON product_listing (org_id);
-- Storefront hot path: published listings for an org.
CREATE INDEX product_listing_published_idx
    ON product_listing (org_id, status) WHERE status = 'PUBLISHED';

-- Many-to-many: a listing can appear under several categories.
CREATE TABLE product_listing_category (
    listing_id  UUID NOT NULL REFERENCES product_listing(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES category(id)        ON DELETE CASCADE,
    PRIMARY KEY (listing_id, category_id)
);

CREATE INDEX plc_category_idx ON product_listing_category (category_id);

-- Images live in object storage; we persist only the key + display metadata.
CREATE TABLE product_listing_image (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID         NOT NULL REFERENCES org(id),
    listing_id  UUID         NOT NULL REFERENCES product_listing(id) ON DELETE CASCADE,
    object_key  TEXT         NOT NULL,
    alt_text    VARCHAR(512),
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT product_listing_image_object_key_unique UNIQUE (org_id, object_key)
);

CREATE INDEX product_listing_image_listing_idx ON product_listing_image (listing_id, sort_order);
