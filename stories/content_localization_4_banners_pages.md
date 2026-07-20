# Slice L4 — banner + page translation cutover (paired → table)

> **Status: SHIPPED (core) 2026-07-20** — local-only on branch
> `83_feat/content-localization-banners-pages` (off master at merged L2b, PR #82). As built, keeping
> the **paired admin API** and adapting internally (the story's allowed "implementer's call"): admin
> writes still take the paired `BannerInput`/`PageInput` (`headline_ar/en`, `body_ar/en`) and the
> admin reads still embed both locales via the paired `BannerResponse`/`StorefrontPageResponse` — so
> AC4's "embed all translations" is satisfied by the unchanged paired shape. On every write the
> services **dual-write** per-language rows to `storefront_banner_translation` /
> `storefront_page_translation` (one row per non-empty side, reproducing the L1 backfill), legacy
> paired columns staying authoritative until L6.
>
> **The reversal (C1/C4):** the public reads now resolve to a **single** value by `?locale=`:
> `StorefrontService.banners(orgSlug, ?locale=)` collapses `PublicBannerView` to `headline`/
> `subheading` (per-field: requested row → default row → legacy default column); `StorefrontPageService.publicPage(orgSlug, kind, ?locale=)` returns a new `PublicPageView(kind, body,
> updatedAt)` resolved the same way. `PublicBannerResponse`/`PublicPageResponse` lost their `_ar`/
> `_en` keys (single `headline`/`subheading`/`body`). Unknown locale → 400; unset → org default. The
> servlet passes `?locale=` to both reads (cache naturally per-(org,locale) via the URL). Repos gained
> `replaceTranslations` + `findTranslationsForBanners` (banner) / `findTranslations` (page).
>
> **Render parity** (AC3) holds by construction: the dual-write rows equal the paired columns, and the
> per-field resolver reproduces the old client-side per-field fallback. Locale-less overloads kept for
> pre-L4 callers.
>
> Tests: `PublicBannersIT` (+unsupported-locale-400, single-value shape, per-locale resolution) and
> `StorefrontPagesIT` (3-arg `publicPage`, per-locale body, fallback, no paired keys) adapted;
> `StorefrontIT`/`StorefrontBannerAdminIT`/`PublicOgImageIT` regression-green. **L5 must ship the
> storefront's `?locale=` pass-through + single-value read in the same release** (coordinated public
> contract change).
>
> Cutover slice of [`content_localization.md`](content_localization.md). Converts the two paired-column
> entities — `storefront_banner` (`headline_ar/en`, `subheading_ar/en`) and `storefront_page`
> (`body_ar/en`) — onto their `*_translation` tables (L1). **This slice reverses the shipped C1/C4
> decision** (`storefront_banners.md`, `storefront_pages.md`): the public reads stop shipping *both*
> locales for the client to resolve, and instead return a **single value resolved server-side** by
> `?locale=`, uniform with every other content read. Legacy paired columns are **dual-written** one
> release; L6 drops them. Code-only, gated on L1.

## Goal

The banner/page editors become per-language (via L5) over `*_translation` rows; the storefront home
banners and about/policies pages render the shopper's-locale copy resolved on the server, with **byte-
identical output to today for existing data** (the migration is invisible to shoppers).

## Design

**Read resolution (the reversal):**
- `GET /api/public/{orgSlug}/banners` — each row resolves `headline`/`subheading` **per field**
  (`coalesce(requested-locale row, default-locale row)`), from **`?locale=ar|en`** (unset →
  `org.default_locale`; unknown → 400). Public row becomes `{ headline, subheading, image_url,
  target_type, target_slug }` — **single** values, no `_ar`/`_en`. The active/in-window/target-resolves
  filtering (C1) is unchanged. `Cache-Control: public, max-age=60` **keyed per (org, locale)**.
- `GET /api/public/{orgSlug}/pages[/{kind}]` — `body` resolved single value by `?locale=`; list summary
  (kind + updated_at) unchanged. Cache keyed per (org, locale).

**Write (admin):** `BannerRequest`/`PageRequest` accept `translations: [{language, headline?,
subheading?}]` / `[{language, body}]` (or keep accepting the paired shape and adapt — implementer's
call, but the persisted form is rows). Keep the existing rules: default-locale `headline`/`body` required
→ **400** (now checked against the default-locale translation row); banner `headline` optional on the
non-default side; caps (`MAX_BODY_CHARS`); NFC via `Text`. `PUT`/upsert **replaces** the language set.
Dual-write the resolved default-locale (and other-locale) values back to the legacy paired columns.

**Parity requirement:** for every pre-migration banner/page, the resolved public output for both `ar` and
`en` must equal what the old both-locales-client-resolve produced. The L1 backfill reproduces the paired
data exactly; this slice must resolve it identically (field-level fallback matches the old per-field
client fallback).

## Scope

**In:** `StorefrontBannerService`/`StorefrontPageService` write over translation rows + validation;
repository translation read/write; `StorefrontService.banners` + page public reads resolve by `?locale=`;
`PublicBannerResponse`/`PublicPageResponse` collapse to single-value fields; per-(org,locale) cache;
dual-write legacy paired columns; admin `BannerResponse`/`StorefrontPageResponse` embed all translations.
**Out:** L6 drop of paired columns; L5 frontend editor.

## Acceptance criteria

1. Public `GET …/banners?locale=en` returns single `headline`/`subheading` resolved to English with
   default-locale field fallback; `?locale=ar` mirrors; unset → default; `?locale=fr` → 400. Same for
   pages `body`.
2. The public banner/page row shape no longer contains `_ar`/`_en` keys (JSON scan) — single values only.
3. **Render parity:** for seeded pre-migration data, resolved `ar` and `en` output equals the old
   both-locales client-resolved output (assert against a fixture captured from the paired columns).
4. Admin create/update without the default-locale headline/body → 400; with an optional second language →
   both persist as translation rows; admin read embeds all translations.
5. C1 filtering intact: an inactive / out-of-window / unpublished-target banner still doesn't serve.
6. Legacy paired columns still carry the written values after a write (dual-write), keeping pre-L6
   rollback safe.
7. Public cache is keyed per (org, locale) (two locales → two cache entries).

## Tests

`BannerTranslationIT` + `PageTranslationIT`: `?locale=` resolution + fallback + unsupported 400; single-
value shape (no paired keys); **backfill→resolve render parity** against a paired-column fixture; default-
required 400; admin embed; C1 filter regression (existing `PublicBannersIT`/`StorefrontIT` adapted to the
new single-value shape); dual-write; per-locale cache key.

## What this unblocks

L5 (frontend banner/page per-language editors + storefront `?locale=` pass-through) and L6 (drop paired
columns). **Coordinate the public-contract change with L5** — the storefront must send `?locale=` and
read single-value fields in the same release the backend stops shipping paired keys.
