-- Catalog variants, slice VG1 (stories/catalog_variants_model.md; decisions in
-- docs/catalog-variants-architecture.md §2).
--
-- The decision this schema encodes: **a variant IS a child `product` row**, attached to its listing
-- through the `product_variant` bridge below. Nothing in the transactional core changes — inventory
-- (PK (org_id, product_id)), reservations, order/invoice lines, the movement ledger, restock
-- idempotency, and barcode resolve all keep keying on a bare `product_id`, and therefore per-variant
-- stock/SKU/barcode/scan-to-stock all fall out of existing product semantics for free. The
-- alternative — threading a nullable `variant_id` through those tables — would have to rebuild the
-- inventory composite PK (a nullable column cannot sit in a PK) and touch ~35–45 files.
--
-- The listing keeps pointing at its PARENT product (`product_listing.product_id` stays NOT NULL and
-- `UNIQUE(org_id, product_id)` is untouched), so a listing with no variants behaves exactly as it
-- does today — variants are pure opt-in.
--
-- The option-axis machinery (`attribute` / `attribute_value` + translations) is ORG-scoped, not
-- listing-scoped: "Size" and its "M" are shared across every listing, which is precisely what makes
-- the facets slice a single grouped COUNT query. Relational + `_translation` tables, the V63 house
-- pattern — no queried jsonb (none exists in this schema and we are not introducing it).
--
-- Re-run jOOQ codegen after this migration (mvn generate-sources -Pcodegen -pl repository).

-- 1. attribute (org-level option axis: "size", "color")
CREATE TABLE attribute (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     UUID        NOT NULL REFERENCES org(id),
    slug       VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (org_id, slug)
);

CREATE TABLE attribute_translation (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    attribute_id UUID        NOT NULL REFERENCES attribute(id) ON DELETE CASCADE,
    language     TEXT        NOT NULL CHECK (language IN ('ar', 'en')),  -- BCP-47; widen = CHECK edit
    name         TEXT        COLLATE "und-x-icu" NOT NULL,               -- ICU sort per language (V62)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (attribute_id, language)
);

-- 2. attribute_value (one selectable option on an axis: "m", "red")
CREATE TABLE attribute_value (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    attribute_id UUID        NOT NULL REFERENCES attribute(id) ON DELETE CASCADE,
    slug         VARCHAR(80) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (attribute_id, slug)
);

CREATE TABLE attribute_value_translation (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    attribute_value_id UUID        NOT NULL REFERENCES attribute_value(id) ON DELETE CASCADE,
    language           TEXT        NOT NULL CHECK (language IN ('ar', 'en')),
    name               TEXT        COLLATE "und-x-icu" NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (attribute_value_id, language)
);

-- 3. product_variant (the bridge: listing ⇄ child product)
-- `variant_key` is the PUBLIC handle ("red-m") — the slug discipline applied one level down. No
-- product_id, SKU, or barcode ever crosses the public boundary (architecture §3); the key is all the
-- storefront ever names a variant by, so it is unique per listing.
--
-- `UNIQUE (product_id)` is what makes a child product back at most one variant, and (with the
-- listing-create guard in ProductListingService) what stops a child from acquiring its own listing.
--
-- `active` exists because removing a variant from the set must NEVER delete the row: its child
-- product may be referenced by order lines and inventory, and that history has to stand. A
-- set-replace deactivates; the child product remains an ordinary product.
CREATE TABLE product_variant (
    id                 UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id             UUID           NOT NULL REFERENCES org(id),
    product_listing_id UUID           NOT NULL REFERENCES product_listing(id) ON DELETE CASCADE,
    product_id         UUID           NOT NULL REFERENCES product(id),
    variant_key        VARCHAR(80)    NOT NULL,
    sales_price        NUMERIC(12, 2) NOT NULL CHECK (sales_price >= 0),
    sort_order         INTEGER        NOT NULL DEFAULT 0,
    active             BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT product_variant_listing_key_unique UNIQUE (product_listing_id, variant_key),
    CONSTRAINT product_variant_product_unique     UNIQUE (product_id)
);

-- The storefront read is always "this listing's variants, in curated order".
CREATE INDEX product_variant_listing_idx
    ON product_variant (product_listing_id, sort_order, variant_key);
CREATE INDEX product_variant_org_idx ON product_variant (org_id);

-- 4. variant_attribute_value (a variant's option combination)
CREATE TABLE variant_attribute_value (
    variant_id         UUID NOT NULL REFERENCES product_variant(id) ON DELETE CASCADE,
    attribute_value_id UUID NOT NULL REFERENCES attribute_value(id),
    PRIMARY KEY (variant_id, attribute_value_id)
);

-- The facet-count join runs value → variants (the PK's leading column is the variant, so it cannot
-- serve that direction). Ships here rather than in the facets slice so that slice needs no migration.
CREATE INDEX variant_attribute_value_value_idx
    ON variant_attribute_value (attribute_value_id, variant_id);
