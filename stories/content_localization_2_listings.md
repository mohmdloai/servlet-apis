# Slice L2 — product_listing translation cutover

> **Status: SHIPPED (core) 2026-07-20** — local-only on branch `80_feat/content-localization-listings`
> (L1 merged as PR #79; this is its own branch off master). As built: admin writes accept `translations:[{language,title,marketing_copy}]` **or**
> the legacy single `title`/`marketing_copy` (synthesized into the default-locale row — backward-compat,
> so existing callers/tests are untouched); admin reads embed all languages; the public
> `?locale=ar|en` resolves per-field (unknown → 400); search matches the folded per-language
> `title_search` **OR** the legacy columns (the legacy arm is a dual-write-era crutch for listings whose
> translation rows predate them — dropped at L6); legacy `title`/`marketing_copy` are dual-written from
> the default-locale row. `marketing_copy` is substring-matched (not folded — no generated column for it)
> and there is no alphabetical title-sort endpoint, so the ICU collation is structural only. The
> order-line snapshot is **L2b**. Tests: `ProductListingTranslationIT` (api module, 7 green) + the seven
> existing catalog ITs regression-green (66 total).
>
> Cutover slice of [`content_localization.md`](content_localization.md). Flips `product_listing`'s
> localized fields (`title`, `marketing_copy`) onto `product_listing_translation` (L1): admin
> reads/writes **all** languages, the public storefront resolves to **one** by `?locale=`, and search
> becomes Arabic-aware **per language**. Legacy `title`/`marketing_copy` are **dual-written** one release
> so L6 can drop them with no reader behind. Code-only — gated only on L1's tables existing.

## Goal

A merchant edits a listing in both Arabic and English from one form; a shopper on the English storefront
sees the English title (falling back to the org default when a field is absent, never null); searching
`"احمد"` finds a listing whose Arabic title is `"أحمد"`; `ORDER BY title` within a locale is ICU order.

## Design

**Resolution contract (read):**
- **Public** (`GET /api/public/{orgSlug}/listings[/{slug}]`) resolves **per field**: `title =
  coalesce(requested-locale row.title, default-locale row.title)` (default row always present ⇒ never
  null); `marketing_copy` likewise. Locale from **`?locale=ar|en`** (unset → `org.default_locale`;
  unknown → 400, cause-naming). Response keeps its shape (`title`, `marketing_copy`) — now resolved.
  `Cache-Control: public, max-age=60` **keyed per (org, locale)**.
- **Admin** (`GET /api/orgs/{orgId}/product-listings[/{id}]`) embeds **all** translations:
  `translations: [{language, title, marketing_copy}]`, batch-loaded (one query per page — the
  listing-image precedent, no N+1). List rows carry them too.

**Write (admin):** `POST`/`PUT` accept `translations: [{language, title, marketing_copy}]`. Validate the
`org.default_locale` row is present and non-blank `title` (else **400**); each `title`/`marketing_copy`
length-capped; NFC via `Text.normalizeText` on every value (closes the gap that `ProductListingService`
never normalized listing text). `PUT` **replaces** the set. Persisted transactionally with the listing.
**Dual-write:** the resolved default-locale `title`/`marketing_copy` are also written to the legacy
columns this release (rollback safety); L6 drops them.

**Search (B3 + admin):** fold the term with `fold_search` and match the per-language `title_search`
(GIN) within the **resolved locale's rows plus the default-locale rows**, de-duped to one row per
listing — mirroring `ProductRepositoryImpl.searchCondition` (which folds the term and matches
`PRODUCT.NAME_SEARCH`). This replaces the current plain `ILIKE` on `title`/`marketing_copy` in
`ProductListingRepositoryImpl.filterConditions`. `ORDER BY` on `title` uses the ICU-collated column
within the locale.

**⚠️ Order-line item-name flag — VERIFIED, carved to [L2b](content_localization_2b_orderline_title.md).**
Confirmed 2026-07-20: the checkout persists the **internal `product.name`** as the order-line
`description` (`SalesOrderService.buildLines` ← `fetchProductSnapshots` = `PRODUCT.NAME`), not the listing
title — so a bilingual customer could get an internal-language item name on their invoice. The fix
(snapshot the resolved listing title at checkout) touches the checkout/placement contract, a different
subsystem than listing storage, so it ships as the small companion slice **L2b**. L2 itself leaves
`resolveForCheckout` returning the legacy default-locale title unchanged.

## Scope

**In:** `ProductListingService` write/validation + translation embed; `ProductListingRepositoryImpl`
translation read/write + per-language search + ICU sort; `TranslationsDTO` on the request/response;
public read `?locale=` resolution + per-(org,locale) cache; dual-write legacy; the order-line snapshot
check. **Out:** dropping legacy columns (L6); frontend editor (L5); per-language slug (deferred).

## Acceptance criteria

1. Create/update a listing without the org default-locale `title` → **400**; with it + an optional second
   language → both persist as two `product_listing_translation` rows.
2. Public read `?locale=en` returns the English title/copy; a missing English `marketing_copy` **falls
   back** to the default-locale value; a missing English row entirely falls back to the default title —
   never null. `?locale=fr` (unsupported) → 400.
3. Admin read embeds `translations:[…]` for every language, no N+1 (assert query count on a multi-row page).
4. Searching `"احمد"` on the storefront returns the listing whose `ar` title is `"أحمد"`; `sort=…` within
   a locale is ICU order; results de-dupe to one row per listing.
5. The legacy `title`/`marketing_copy` columns still carry the resolved default-locale value after a write
   (dual-write), keeping the pre-L6 rollback safe.
6. Adding a third language later is INSERTs only — no schema change (documents the N-language property).
7. Order-line item name renders the resolved listing title (or the flag is confirmed already-satisfied).

## Tests

`ProductListingTranslationIT`: default-required 400; two-language create + `?locale=` resolution + field
fallback; unsupported-locale 400; Arabic per-language search hit; ICU sort within locale; admin embed no
N+1; dual-write assertion; cache-key-includes-locale. Regression: existing `ProductListing`/`Storefront`
suites stay green (shape unchanged bar the added `translations`/resolved fields).

## What this unblocks

L5 (frontend listing editor tabs + storefront `?locale=` pass-through) and L6 (drop legacy columns).
