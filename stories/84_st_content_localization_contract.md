# Story 84 — Content localization L6: contract (drop legacy / paired columns)

**Branch:** `84_feat/content-localization-contract` (off master at merged L4 PR #83).
**Slice:** the final slice of the [content-localization epic](content_localization.md);
implements [`content_localization_6_contract.md`](content_localization_6_contract.md).

## Context

L1 expanded (translation tables + backfill), L2–L4 cut every read/write over to the
`*_translation` tables while **dual-writing** the legacy single-column / paired `_ar`/`_en`
columns for one release, and L5 shipped the frontend (storefront `?locale=` cutover + admin
per-language editors, frontst PR #51, now merged + deployed). The dual-write columns are now
dead. This slice drops them so `*_translation` is the single source of truth.

## What shipped

### Migration `V64__Content_localization_contract.sql`

Drops `product_listing.title`, `product_listing.marketing_copy`, `category.name` (and with it the
V62 `text COLLATE "und-x-icu"` treatment — the per-language ICU columns live on
`category_translation.name` now), `storefront_banner.headline_ar/headline_en/subheading_ar/
subheading_en`, and `storefront_page.body_ar/body_en`. Untouched: the four `*_translation` tables,
their generated `*_search` columns + trigram GIN indexes, and `product.name_search` + ICU (product
stays single-column, out of the epic's scope).

### Reader/writer cutover (the drop is safe only if nothing touches the columns)

The story's premise ("there should be no readers if L2–L4 are complete") was optimistic — three
kinds of surviving readers were repointed at the translation table:

- **The domain scalar** (`ProductListing.title/marketingCopy`, `Category.name`). L2/L3 populated
  these from the dual-written legacy column via the record mapper. Now the base repo reads
  (`ProductListingRepositoryImpl.selectListing()` / `CategoryRepositoryImpl.selectCategory()`)
  **join the org's default-locale translation row** (`org.default_locale` is NOT NULL since V52; a
  default-locale row is guaranteed by the write rule + L1 backfill) and map the scalar from it. Every
  read path — admin list/detail/lifecycle responses, the `COMMENT_REPLIED` notification payload —
  is correct for free. The insert/update/updateStatus `RETURNING` records don't carry the join, so
  the services set the scalar on the returned object from the default-locale row they already
  computed.
- **Banner / page paired admin view** (`headlineAr/En`, `bodyAr/En`). L4 kept the paired admin API.
  The repo reads now **pivot the paired domain fields from the `*_translation` rows** (ar → `*Ar`,
  en → `*En`) via `loadPairedTranslations`/`loadPairedBodies`; the write paths still build the paired
  fields from the paired input and derive the translation rows from them (the services carry the
  paired content onto the `RETURNING`-mapped object, which lost the columns).
- **Review / comment worklist titles** (`ListingReview`/`ListingCommentRepositoryImpl` `findMine` +
  `findAdminPage`). These selected `product_listing.title` directly for the merchant worklist; now
  they join the org's default-locale translation and select that.

The dual-write code paths and the legacy fallback arms (the `COALESCE(..., legacy)` last arg in the
public listing / category / banner / page resolvers, and the legacy `OR title LIKE …` search arm)
were removed in the same release.

## Acceptance criteria

1. **Columns dropped, translation infra intact** — `ContentLocalizationContractIT` scans
   `information_schema`: the seven dropped columns are absent; the `*_translation` tables + their
   `title_search`/`name_search` generated columns + `*_search_idx` GIN indexes survive; and
   `product.name_search` + `product_name_search_trgm_idx` survive.
2. **Full build + IT suite green with the dual-write removed** — proof nothing read or wrote the
   dropped columns. `mvn -pl api -am verify` (unit + failsafe ITs).
3. **Reads behave identically** — the existing L2–L4 translation ITs
   (`ProductListingTranslationIT`, `CategoryTranslationIT`, `PublicBannersIT`, `StorefrontPagesIT`,
   `OrderLineTitleSnapshotIT`) pass unchanged against the dropped schema.
4. **Rollback** — this migration is the point of no cheap return: a revert means restoring the
   columns and re-backfilling from `*_translation` (the inverse of V63's backfill). It ships only
   after L2–L5 have been stable in production.

## Test fixtures

IT fixtures that raw-inserted a listing/category via the now-dropped column were updated: those
whose title/name is asserted (`PublicCheckoutIT`, `PortalCheckoutIT`, `PortalReorderIT`,
`StorefrontIT`) now insert a `*_translation` row (both locales, same value — the read resolves
whichever is the org default); the rest (banner-target listings/categories, availability, FK-409
delete) just drop the `.set`. `OrderLineTitleSnapshotIT` already seeded per-language rows via its
`translate` helper, so its `seedListing` simply dropped the legacy arg.

## Unblocks

Closes the epic — one storage pattern for every customer-facing localized field, N-language by a
single INSERT.
