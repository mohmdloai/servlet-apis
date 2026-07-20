# Slice L6 — Contract: drop legacy / paired columns

> Final slice of [`content_localization.md`](content_localization.md). The **CONTRACT** step: once L2–L5
> all read and write translations and the dual-write has run one release, drop the now-dead legacy
> columns. Gated on L2–L5 shipping; independently the riskiest (destructive) step, so it is its own
> migration and its own release.

## Goal

Remove the duplicated storage so `*_translation` is the single source of truth: no reader of a legacy
localized column remains, and the superseded V62 single-language search/collation on the now-localized
entities is retired.

## Design

**Migration `V6x__Content_localization_contract.sql`** (number resolved at branch time), dropping:
- `product_listing.title`, `product_listing.marketing_copy`
- `category.name` **and** its V62 `text COLLATE "und-x-icu"` treatment (superseded by the per-language
  `category_translation.name` ICU columns; category has no generated `name_search` to drop — it never got
  one, per the L1/L3 map)
- `storefront_banner.headline_ar`/`headline_en`/`subheading_ar`/`subheading_en`
- `storefront_page.body_ar`/`body_en`

**Kept:** `product.name_search` + its ICU/collation + trigram GIN — `product` stays single-column (scope
decision), so its V62 search is still live and correct. No translation table or index is dropped.

**Pre-drop guard:** before merging, confirm zero readers/writers of each dropped column remain in the
codebase (grep the generated jOOQ field + the raw column name). The dual-write added in L2–L4 must be
**removed** in the same release (or just before) — a dropped column that code still writes is a 500.

## Scope

**In:** the drop migration; removal of the L2–L4 dual-write code paths; jOOQ codegen; regenerating any
repository read that still selected a dropped column (there should be none if L2–L4 are complete). **Out:**
new behavior — this slice changes nothing a client observes.

## Acceptance criteria

1. After migration, the dropped columns are gone (`information_schema` scan); the `*_translation` tables
   and their indexes are untouched; `product.name_search`/ICU remain.
2. The full backend build + all IT suites pass with the dual-write removed and the columns dropped — proof
   that nothing read or wrote them.
3. Public + admin reads for all four entities behave identically to their post-L2–L4 behavior (resolution
   unchanged; only the redundant source is gone).
4. Rollback: this migration is the point of no cheap return — document that a revert means restoring the
   columns + re-backfilling from `*_translation` (the inverse of L1's backfill), so it ships only after
   L2–L5 have been stable in production.

## Tests

`ContentLocalizationContractIT`: assert the columns are dropped and the translation tables/indexes and
`product.name_search` survive; the existing L2–L4 translation ITs continue to pass unchanged (they never
depended on the legacy columns). Full-suite green is the real acceptance.

## What this unblocks

Closes the epic — one storage pattern for all customer-facing content, N-language by INSERT.
