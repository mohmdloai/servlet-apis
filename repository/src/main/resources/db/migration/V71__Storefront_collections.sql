-- Named collections (roadmap item 8, stories/storefront_collections.md).
--
-- Generalizes the single implicit "Featured" list into merchant-defined collections ("Best of Best",
-- "Ramadan picks") with their own slugs and landing pages. Additive BESIDE product_listing
-- .featured_sort, not a migration of it: featured stays exactly as shipped (its public wire
-- ?featured=true is deployed, its admin surface is built), and collections are the N-named-lists
-- feature next to it. Consolidating the two would cost a data migration and a public-wire break for
-- zero merchant-visible gain.
--
-- The bilingual name follows the V63 translation-table pattern (one row per (entity, language)), so
-- widening past ar/en is an INSERT, never a migration. Both join FKs CASCADE, so the membership
-- table self-heals: deleting a listing drops it out of every collection, deleting a collection drops
-- its whole membership — and neither ever touches the listings themselves.
--
-- Re-run jOOQ codegen after this migration (mvn generate-sources -Pcodegen -pl repository).

-- ── collection ───────────────────────────────────────────────────────────────────────────────────
-- slug is the PUBLIC handle (/col/{slug} landing pages) and unique per org; no internal id ever
-- crosses the public boundary. sort_order is the merchant's rail order, tie-broken by slug so the
-- rail is deterministic when several collections share a sort_order (the default 0 case).
CREATE TABLE collection (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     UUID        NOT NULL REFERENCES org(id),
    slug       VARCHAR(80) NOT NULL,
    sort_order INT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (org_id, slug)
);

-- The rail read is always "this org's collections in curated order" — the index carries the ordering.
CREATE INDEX collection_org_order_idx ON collection (org_id, sort_order, slug);

-- ── collection_translation (V63 pattern; not searched — a collection name is nav, not a haystack) ─
CREATE TABLE collection_translation (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    collection_id UUID        NOT NULL REFERENCES collection(id) ON DELETE CASCADE,
    language      TEXT        NOT NULL CHECK (language IN ('ar', 'en')),  -- BCP-47; widen = a CHECK edit
    name          TEXT        COLLATE "und-x-icu" NOT NULL,               -- ICU sort per language (V62)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (collection_id, language)
);

-- ── collection_listing (the curated membership) ───────────────────────────────────────────────────
-- The composite PK is what makes the set-replace idempotent and a listing un-duplicatable inside one
-- collection; `sort` is the curated position (0..n-1), assigned by the array order of the PUT.
CREATE TABLE collection_listing (
    collection_id      UUID NOT NULL REFERENCES collection(id) ON DELETE CASCADE,
    product_listing_id UUID NOT NULL REFERENCES product_listing(id) ON DELETE CASCADE,
    sort               INT  NOT NULL,
    PRIMARY KEY (collection_id, product_listing_id)
);

-- The reverse lookup ("which collections hold this listing?") and the cascade path on listing delete
-- — the PK's leading column only serves the forward direction.
CREATE INDEX collection_listing_listing_idx ON collection_listing (product_listing_id);

-- The landing-page read joins membership and orders by the curated position; carrying `sort` in the
-- index keeps ?collection={slug} an index-only walk instead of a sort.
CREATE INDEX collection_listing_curated_idx ON collection_listing (collection_id, sort);
