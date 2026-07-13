# Slice C2: Storefront SEO & social metadata (org meta columns + stable og-image)

> For micro-merchants the storefront's real distribution channel is a pasted link — WhatsApp,
> Instagram, TikTok. Today that link unfurls with whatever the crawler scrapes: no merchant-written
> title/description, and no safe preview image (every image URL we serve is a **presigned GET with a
> ~900s TTL** — a crawler that caches it re-fetches a dead URL fifteen minutes later). This slice
> adds three merchant-editable metadata fields to the org and — the part that actually makes
> previews work — **one stable, anonymous image route** that never expires.
>
> Canonical decisions: [`frontst/docs/storefront-customization-epic.md`](../../frontst/docs/storefront-customization-epic.md)
> (§6 og-image stability, §4 key prefixes, §9 authz). Feeds frontend story 31. Rides the same
> merge-PUT pattern as `storefront_org_profile.md` (B1/V52).

---

## Goal

An OWNER sets `meta_title`, `meta_description`, and an og image on their org; the public profile
carries the text fields; and `GET /api/public/{orgSlug}/og-image` serves the image bytes at a URL
that is **stable forever** — so a link pasted into WhatsApp renders a correct card today and still
renders it next month.

**Deliberately mono-lingual (v1).** og/meta scraping is locale-less — a crawler fetches one
canonical set per URL and social caches don't vary by reader. One `meta_title`/`meta_description`
per store (the merchant writes in whichever language their customers share links in) matches the
`payment_instructions` precedent. Per-locale metadata (e.g. per-path `/{ar|en}` descriptions) is a
frontend `generateMetadata` concern layered later if ever needed — not schema.

## Design

- **Migration (next `V##`)** — `org` gains three nullable columns:
  `meta_title TEXT`, `meta_description TEXT`, `og_image_object_key TEXT`. Re-run jOOQ codegen.
- **Merge-PUT (existing routes, new fields).** `PUT /api/orgs/{orgId}` (OWNER) and
  `PATCH /api/admin/orgs/{orgId}` (ADMIN) accept the three fields with V52 semantics: null =
  leave-unchanged; explicit empty string clears. Validation: `meta_title` ≤ 70 chars,
  `meta_description` ≤ 200 chars (400 cause-naming — generous bounds over the ~60/~160 display
  truncation, which is the *frontend's* guidance, not a server rule); `og_image_object_key` must
  carry the `{orgId}/og/` prefix (400 otherwise — the logo-key guard, epic §4).
- **Presign.** `POST /api/orgs/{orgId}/og-image/presign` (STAFF) — `{filename, content_type}` →
  `{upload_url, object_key, expires_in_seconds}`; the logo presign machinery with the `{orgId}/og/`
  prefix (`ObjectStorage.newOgImageKey`/`ogImageKeyPrefix`).
- **Public profile** (`GET /api/public/{orgSlug}`, B1) gains `meta_title` and `meta_description`
  (nulls omitted, unchanged `max-age=300`). It does **not** carry an og-image URL — the stable
  route below *is* the URL, derivable client-side.
- **The stable image route** — on `PublicStorefrontServlet`:
  ```
  GET /api/public/{orgSlug}/og-image
  ```
  Streams the image **bytes** (never a redirect — crawlers cache redirect *targets*, which here
  would be a presigned URL): resolve org by slug (active-only, else opaque 404) → object key =
  `og_image_object_key`, **falling back to `logo_object_key`** when unset → fetch from object
  storage with the tight-timeout pattern `DocumentRenderService`'s `PresignedLogoSource` already
  uses → respond with the object's content type and `Cache-Control: public, max-age=3600`. Neither
  key set → 404. Storage failure → 404 (a missing preview beats a hanging crawler). Covered by the
  `rl:pub-read` bucket like every public GET.

### Why stream, not presign or redirect

The whole point is a URL a third-party cache can hold indefinitely. A presigned URL expires (900s);
a 302 to one gets its *target* cached by some scrapers — same bug, one hop later. Streaming through
the app costs one storage fetch per crawler hit on a ≤ a-few-hundred-KB image, at scrape frequency
(rare), behind a 1-hour public cache header. That is cheap; broken WhatsApp previews are not.

## Scope

### In
Migration + codegen; merge-PUT fields + validation on both planes; og presign route; public profile
fields; the streaming `og-image` route with logo fallback; DTO updates (`StorefrontProfileResponse`,
org admin responses echo the fields).

### Out (deferred)
Per-locale metadata; per-*page* (listing/category) metadata — the frontend derives those from
catalog data in story 31; sitemap/robots; structured data (JSON-LD); image resizing/cropping to og
dimensions (merchant uploads a suitable image; the admin UI states the recommended 1200×630).

## Authorization

Metadata fields ride the OWNER-gated `PUT /api/orgs/{orgId}` (org identity, epic §9); presign is
STAFF (upload mechanics, like logo). The public routes are anonymous with the standard boundary.

## Acceptance criteria

1. `PUT /api/orgs/{orgId}` sets/merges/clears the three fields with V52 null-vs-empty semantics;
   over-length values and a non-`{orgId}/og/` key → 400 cause-naming. `PATCH /api/admin/orgs/{orgId}`
   has parity.
2. The presign route mints `{orgId}/og/…` keys; an attach outside the prefix → 400.
3. `GET /api/public/{orgSlug}` carries `meta_title`/`meta_description` when set, omits when null;
   cache header unchanged.
4. `GET /api/public/{orgSlug}/og-image` streams bytes with the stored content type and
   `Cache-Control: public, max-age=3600`; **the response body is identical on repeated calls across
   a presign-TTL boundary** (the stability property — assert two fetches > TTL apart in mocked
   time, or by asserting no presigned URL appears anywhere in the response).
5. Fallback chain: og key unset → logo bytes; neither → 404; unknown/inactive org → opaque 404;
   storage error → 404 within the timeout budget (no hang).
6. Non-GET on the public route → 405; the route is inside the `rl:pub-read` bucket.

## Tests

`OrgSeoMetadataIT` (merge-PUT matrix, prefix guard, both planes) and `PublicOgImageIT` (stream,
content-type, cache header, fallback chain, 404s, no-presigned-URL-leak scan). Regression:
`StorefrontProfileIT` extended for the two new profile fields; existing suites untouched.
Verified live through Tomcat: set fields via PUT, `curl -I …/og-image` twice 20 minutes apart —
same bytes, same headers.

## What this unblocks

| Next | Depends on this |
|---|---|
| **Frontend story 31** — Settings "Sharing & SEO" editor + `generateMetadata` across the storefront | all three fields + the stable route |
| Future sitemap / JSON-LD slices | the metadata columns |
