# Slice L3 — category translation cutover

> Cutover slice of [`content_localization.md`](content_localization.md). Same shape as L2, smaller
> surface: `category.name` moves onto `category_translation` (L1). Admin embeds all languages; the
> storefront nav + category reads resolve to one by `?locale=`; category search finally becomes
> Arabic-aware (per-language `name_search` — the base `category` had ICU from V62 but no search at all).
> Legacy `category.name` is **dual-written** one release; L6 drops it. Code-only, gated on L1.

## Goal

A merchant names a category in Arabic and English; the storefront nav shows the shopper's-locale name
(falling back to the org default, never null); a category search folds Arabic variants.

## Design

**Read resolution:** public category reads (`GET /api/public/{orgSlug}/categories`, the nav node + the
category chips embedded on listing rows) resolve `name = coalesce(requested-locale row, default-locale
row)` from **`?locale=ar|en`** (unset → `org.default_locale`; unknown → 400). Admin
(`GET /api/orgs/{orgId}/categories[/{id}]`) embeds `translations: [{language, name}]`, batch-loaded.
Public cache key includes the locale.

**Write (admin):** `POST`/`PUT` accept `translations: [{language, name}]`; default-locale `name` required
→ **400**; each length-capped; NFC via `Text.normalizeText` (already done for the legacy `name` in
`CategoryService` — extend to every translation). `PUT` replaces the set. Dual-write the resolved
default-locale `name` to legacy `category.name`.

**Search/sort:** where categories are searched (admin console; any storefront category filter), fold the
term and match `category_translation.name_search` (GIN) within the resolved-locale + default-locale rows,
de-duped per category; `ORDER BY name` uses the ICU-collated translation column within the locale. (The
nav tree read stays unfiltered — it just resolves names.)

## Scope

**In:** `CategoryService` write/validation + translation embed; `CategoryRepositoryImpl` translation
read/write + per-language search/sort; public category + nav + listing-chip resolution by `?locale=`;
per-(org,locale) cache; dual-write legacy `name`. **Out:** L6 drop; L5 frontend.

## Acceptance criteria

1. Create/update a category without the default-locale `name` → **400**; with an optional second language
   → two `category_translation` rows.
2. Public nav/category read `?locale=en` returns the English name; missing English row falls back to the
   default name (never null). `?locale=fr` → 400.
3. Listing-row category chips resolve to the same `?locale=` as the listing read (one consistent locale
   per request).
4. Admin read embeds `translations:[…]`, no N+1 on a multi-category page.
5. A folded Arabic category search matches the `ar` name via `name_search`; `ORDER BY name` is ICU order
   within the locale.
6. Legacy `category.name` still carries the resolved default-locale name after a write (dual-write).

## Tests

`CategoryTranslationIT`: default-required 400; two-language create + `?locale=` resolution + fallback;
unsupported-locale 400; nav/chip locale consistency; Arabic `name_search` hit + ICU sort; admin embed no
N+1; dual-write. Regression: existing category + storefront-nav suites green.

## What this unblocks

L5 (frontend category editor + nav locale pass-through) and L6 (drop `category.name` + its V62 ICU column).
