# Slice L1 — Expand: per-language translation tables + idempotent backfill

> First of six slices of [`content_localization.md`](content_localization.md) (§Story slate). The
> reversible **EXPAND** step of expand·cutover·contract: create the four `*_translation` tables and
> backfill them from today's storage, with the legacy columns still present and authoritative and **no
> reader or writer change**. Cutover (L2–L4) flips services onto the tables; contract (L6) drops the
> legacy columns.

## Goal

Every customer-facing content surface gains a per-entity translation table keyed by BCP-47 `language`,
seeded from existing data, so the cutover slices have somewhere to read/write and the storefront's
Arabic-aware search has a per-language folded index. Nothing observable changes after this slice ships —
it is pure additive schema + data.

## Design

**Migration `V63__Content_localization_translation_tables.sql`** (written) creates four tables, all with
`UUID` PKs (matching the real base tables, not the epic's `BIGSERIAL` sketch), `language TEXT CHECK IN
('ar','en')`, `UNIQUE(entity_id, language)`, and `ON DELETE CASCADE` to the parent:

- `product_listing_translation(listing_id, language, title, marketing_copy, title_search, …)` — `title`
  is `NOT NULL COLLATE "und-x-icu"`; `title_search TEXT GENERATED ALWAYS AS (fold_search(title)) STORED`
  + `gin (title_search gin_trgm_ops)`.
- `category_translation(category_id, language, name, name_search, …)` — same shape (`name` NOT NULL ICU;
  generated `name_search` + GIN). Note the base `category` already had ICU + a name backfill from V62,
  but **no** `name_search` — this is where category search is finally wired.
- `storefront_banner_translation(banner_id, language, headline, subheading, …)` — no search. `headline`
  is **nullable** (a non-default-locale side may carry only a subheading, exactly as the paired columns
  allow) with `CHECK (headline IS NOT NULL OR subheading IS NOT NULL)`.
- `storefront_page_translation(page_id, language, body, …)` — no search; `body` NOT NULL.

**Backfill (idempotent, in the same migration):**
- Single-column entities (`product_listing`, `category`) → **one** row at the org's `default_locale`
  (`JOIN org`; `default_locale` is `NOT NULL DEFAULT 'ar'` per V52). `title`/`name` are NOT NULL on the
  base table, so every seeded row is valid.
- Paired entities (`storefront_banner`, `storefront_page`) → one row per **non-empty** side (`ar` from
  `*_ar`, `en` from `*_en`), reproducing existing data exactly for render parity.
- Every INSERT is guarded by `NOT EXISTS` on `(entity_id, language)` → a re-run is a no-op.

Re-run jOOQ codegen after the migration so the generated types exist for L2–L4 (this slice adds no Java
that references them).

## Scope

**In:** the V63 migration (tables + generated search columns + GIN + idempotent backfill); jOOQ codegen;
an integration test asserting table creation, backfill parity, generated-search population, and
idempotency.

**Out:** any service/repository/DTO/servlet change (L2–L4); dropping legacy columns (L6); the frontend
(L5). No `_translation` table is read or written by application code in this slice.

## Acceptance criteria

1. After `flyway migrate`, all four `*_translation` tables exist with `UNIQUE(entity_id, language)`, the
   `language` CHECK, `ON DELETE CASCADE`, and (listing/category only) a `*_search` generated column +
   trigram GIN index (assert via `information_schema` / `pg_indexes`).
2. Backfill parity: each existing listing/category has exactly one translation row at its org's
   `default_locale` carrying the same `title`/`marketing_copy`/`name`; each banner/page has one row per
   non-empty `_ar`/`_en` side with identical text.
3. The generated `title_search`/`name_search` equals `fold_search(title|name)` for every backfilled row
   (searchable immediately — e.g. an `أحمد` title backfills a row whose `title_search` matches folded
   `احمد`).
4. Re-running the migration body (the guarded INSERTs) a second time changes **no** rows (idempotent).
5. The legacy columns (`product_listing.title`/`marketing_copy`, `category.name`, the banner/page paired
   columns) are unchanged and still authoritative — no reader/writer references a translation table yet.
6. Deleting a parent row cascades its translation rows (FK `ON DELETE CASCADE`).

## Tests

`ContentLocalizationExpandIT` (repository module, TestContainers): seed listings/categories/banners/pages
across an `ar`-default and an `en`-default org (including a banner with a subheading-only `en` side and a
page with only one body), run Flyway, assert 1–6. Idempotency: invoke the guarded backfill SQL again,
assert row counts unchanged. Search: assert an Arabic-diacritic title backfills a `title_search` that a
`fold_search`-folded query matches.

## What this unblocks

| Next | Depends on this |
|---|---|
| **L2/L3/L4** cutovers (any order) | the tables + backfilled data + per-language search index |
| **L6** contract | nothing directly, but the whole chain rests on this expand being reversible |
