# Slice L5 — Frontend: per-language editors + storefront locale pass-through (frontst)

> Frontend slice of [`content_localization.md`](content_localization.md). **Authored and implemented in
> the `frontst` repo** (this file is the backend-side pointer); the paired story lives under
> `frontst/docs/` alongside the storefront-customization epic. Consumes L2–L4's APIs.

## Goal

Merchants edit every localized field (listing title/copy, category name, banner headline/subheading, page
body) per language from one admin form; the public storefront requests and renders content in its route
locale.

## Design (frontst)

- **Admin editor** — each localized field grows a per-language tab/section (the paired-column banner/page
  forms already render two inputs — this generalizes them to the `translations: [{language, …}]` shape).
  Submit sends the translation set; the default-locale field stays required (mirrors the backend 400).
  This replaces the two-input `_ar`/`_en` widgets with a language-tabbed control driven off the org's
  supported locales.
- **Storefront pass-through** — the storefront already routes as `/{locale}/{orgSlug}/…`. Pass that
  `locale` to every public content read as **`?locale=`** (listings, categories, banners, pages). Because
  the backend now returns single resolved values (L2–L4), the client-side `_ar`/`_en` fallback logic for
  banners/pages is **deleted**. Cache/ISR keys must include the locale (the backend cache does too).
- UI-chrome i18n (`apps/admin/messages`, next-intl) is unrelated and unchanged.

## Scope

**In (frontst):** admin per-language field editors for the four entities; storefront `?locale=`
pass-through on all public content reads; delete the banner/page client-side locale-fallback; typed
client updates for the `translations[]` request/response and the collapsed single-value public shapes.
**Out:** anything backend (L2–L4, L6); per-language slug/SEO (deferred).

## Acceptance criteria

1. Admin listing/category/banner/page forms edit all supported languages; saving without the default-
   locale value surfaces the backend 400 inline.
2. The `en` storefront renders English content; switching the route locale to `ar` re-fetches with
   `?locale=ar` and renders Arabic, falling back to default where a field is absent.
3. No client-side `_ar`/`_en` resolution remains for banners/pages (the backend resolves).
4. e2e (ltr + rtl) covers: edit-both-languages round-trip; storefront locale switch changes rendered copy.

## Coordination

Ship the **L4** public-contract change (banners/pages drop paired keys) together with this slice's
storefront read change — the storefront must send `?locale=` and read single-value fields in the same
release the backend stops emitting `_ar`/`_en`. L2/L3 add fields without removing any, so they can precede
this slice safely.
