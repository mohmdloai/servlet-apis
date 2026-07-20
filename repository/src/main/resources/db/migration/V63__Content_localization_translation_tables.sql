-- Slice L1 (content localization) — EXPAND step (stories/content_localization_1_expand.md).
--
-- Collapse all four customer-facing content surfaces onto ONE pattern: a per-entity *_translation
-- table keyed by BCP-47 `language`, so any number of languages is an INSERT (never a migration),
-- integrity is DB-enforced (UNIQUE(entity, language)), and the V62 folded-search index is one
-- generated column per searched table that works for every language at once.
--
-- This is the reversible EXPAND step only: it CREATES the tables and BACKFILLS them from the existing
-- single-column (product_listing/category) and paired _ar/_en (storefront_banner/storefront_page)
-- sources. The legacy columns stay present and authoritative — NO reader or writer changes here.
-- Cutover (L2–L4) writes/reads translations; the contract migration (L6) drops the legacy columns.
--
-- Real schema note: every base table uses UUID PKs (not the BIGSERIAL in the epic sketch). `fold_search`
-- (V62) is the surviving IMMUTABLE fold function behind every generated *_search column; pg_trgm and the
-- und-x-icu collation were both established by V62. The two searched tables (listing/category) get a
-- generated *_search + trigram GIN; the two non-searched tables (banner/page) omit them.
--
-- Idempotent: every backfill INSERT is guarded by NOT EXISTS on (entity, language) so a re-run is a
-- no-op. Re-run jOOQ codegen after this migration (mvn generate-sources -Pcodegen -pl repository).

CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- established by V62; defensive so this migration stands alone.

-- ── 1. product_listing_translation (searched: title) ─────────────────────────────────────────────
CREATE TABLE product_listing_translation (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id     UUID        NOT NULL REFERENCES product_listing(id) ON DELETE CASCADE,
    language       TEXT        NOT NULL CHECK (language IN ('ar', 'en')),   -- BCP-47; widen = one CHECK edit
    title          TEXT        COLLATE "und-x-icu" NOT NULL,                -- ICU sort per language (V62)
    marketing_copy TEXT,
    -- V62 fold, per language: one generated key + one GIN serves any N languages, can never drift.
    title_search   TEXT        GENERATED ALWAYS AS (fold_search(title)) STORED,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (listing_id, language)
);
CREATE INDEX product_listing_translation_search_idx
    ON product_listing_translation USING gin (title_search gin_trgm_ops);

-- ── 2. category_translation (searched: name) ─────────────────────────────────────────────────────
CREATE TABLE category_translation (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id UUID        NOT NULL REFERENCES category(id) ON DELETE CASCADE,
    language    TEXT        NOT NULL CHECK (language IN ('ar', 'en')),
    name        TEXT        COLLATE "und-x-icu" NOT NULL,
    name_search TEXT        GENERATED ALWAYS AS (fold_search(name)) STORED,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (category_id, language)
);
CREATE INDEX category_translation_search_idx
    ON category_translation USING gin (name_search gin_trgm_ops);

-- ── 3. storefront_banner_translation (not searched) ──────────────────────────────────────────────
-- headline is nullable at the DB (a non-default-locale side may carry only a subheading, mirroring the
-- paired columns); the default-locale-headline-required rule stays service-enforced. A row must carry
-- at least one of the two — otherwise it is meaningless.
CREATE TABLE storefront_banner_translation (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    banner_id  UUID        NOT NULL REFERENCES storefront_banner(id) ON DELETE CASCADE,
    language   TEXT        NOT NULL CHECK (language IN ('ar', 'en')),
    headline   TEXT,
    subheading TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (banner_id, language),
    CONSTRAINT storefront_banner_translation_nonempty_chk
        CHECK (headline IS NOT NULL OR subheading IS NOT NULL)
);

-- ── 4. storefront_page_translation (not searched) ────────────────────────────────────────────────
CREATE TABLE storefront_page_translation (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    page_id    UUID        NOT NULL REFERENCES storefront_page(id) ON DELETE CASCADE,
    language   TEXT        NOT NULL CHECK (language IN ('ar', 'en')),
    body       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (page_id, language)
);

-- ── 5. Idempotent backfill ───────────────────────────────────────────────────────────────────────
-- Single-column entities → ONE row at the org's default_locale. default_locale is NOT NULL (V52), so
-- no COALESCE is needed. title/name are NOT NULL on the base table, so every backfilled row is valid.
INSERT INTO product_listing_translation (listing_id, language, title, marketing_copy)
SELECT pl.id, o.default_locale, pl.title, pl.marketing_copy
FROM product_listing pl
JOIN org o ON o.id = pl.org_id
WHERE NOT EXISTS (
    SELECT 1 FROM product_listing_translation t
    WHERE t.listing_id = pl.id AND t.language = o.default_locale);

INSERT INTO category_translation (category_id, language, name)
SELECT c.id, o.default_locale, c.name
FROM category c
JOIN org o ON o.id = c.org_id
WHERE NOT EXISTS (
    SELECT 1 FROM category_translation t
    WHERE t.category_id = c.id AND t.language = o.default_locale);

-- Paired entities → one row per non-empty side, reproducing the existing data exactly (render parity).
INSERT INTO storefront_banner_translation (banner_id, language, headline, subheading)
SELECT b.id, 'ar', b.headline_ar, b.subheading_ar
FROM storefront_banner b
WHERE (b.headline_ar IS NOT NULL OR b.subheading_ar IS NOT NULL)
  AND NOT EXISTS (SELECT 1 FROM storefront_banner_translation t
                  WHERE t.banner_id = b.id AND t.language = 'ar');
INSERT INTO storefront_banner_translation (banner_id, language, headline, subheading)
SELECT b.id, 'en', b.headline_en, b.subheading_en
FROM storefront_banner b
WHERE (b.headline_en IS NOT NULL OR b.subheading_en IS NOT NULL)
  AND NOT EXISTS (SELECT 1 FROM storefront_banner_translation t
                  WHERE t.banner_id = b.id AND t.language = 'en');

INSERT INTO storefront_page_translation (page_id, language, body)
SELECT p.id, 'ar', p.body_ar
FROM storefront_page p
WHERE p.body_ar IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM storefront_page_translation t
                  WHERE t.page_id = p.id AND t.language = 'ar');
INSERT INTO storefront_page_translation (page_id, language, body)
SELECT p.id, 'en', p.body_en
FROM storefront_page p
WHERE p.body_en IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM storefront_page_translation t
                  WHERE t.page_id = p.id AND t.language = 'en');
