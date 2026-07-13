# Slice C1: Merchant-managed home banners (storefront_banner)

> The storefront home renders a deal-banner carousel, but its content is a **hard-coded config map**
> in the frontend (`apps/storefront/src/shared/config/home-banners.ts`, keyed by org slug — only the
> e2e `acme` org has slides). A real merchant cannot create a banner. This slice moves banners into
> the database behind an org-scoped editor API and a cached public read, and in doing so sets three
> precedents the rest of the customization epic reuses: the **bilingual paired-column pattern**, the
> **slug-keyed target validation**, and the **server-side stale-target degradation**.
>
> Canonical decisions: [`frontst/docs/storefront-customization-epic.md`](../../frontst/docs/storefront-customization-epic.md)
> (§Locked decisions 1–5, 7, 9–10). Feeds frontend story 30. Sibling of `storefront_org_profile.md`
> (B1 — same admin-edit / public-read shape).

---

## Goal

A STAFF member manages an ordered set of home banners for their org — bilingual headline/subheading,
an optional image (presigned upload, org-scoped key), a target that is *structurally* a category or
listing slug of their own org, an active flag, and an optional display window. The anonymous
storefront reads only the banners that are active, in-window, and whose target still resolves —
never a dead link, never a DRAFT leak, never another org's asset.

Done means: the frontend deletes `home-banners.ts` entirely; `acme`'s slides come from seeded rows;
a merchant who unpublishes the listing a banner points at sees that banner silently vanish from the
public read (and reappear on re-publish) with no edit required.

## Design

- **Migration (next `V##`)** — `storefront_banner`:
  ```
  id BIGSERIAL PK · org_id BIGINT NOT NULL REFERENCES org(id)
  headline_ar TEXT NULL · headline_en TEXT NULL          -- default-locale one required (service-enforced)
  subheading_ar TEXT NULL · subheading_en TEXT NULL
  image_object_key TEXT NULL                             -- {orgId}/banner/… (absent → branded gradient slide)
  target_type TEXT NOT NULL CHECK (target_type IN ('category','listing'))
  target_slug TEXT NOT NULL
  sort_order INT NOT NULL DEFAULT 0
  active BOOLEAN NOT NULL DEFAULT true
  starts_at TIMESTAMPTZ NULL · ends_at TIMESTAMPTZ NULL  -- CHECK (starts_at IS NULL OR ends_at IS NULL OR starts_at < ends_at)
  created_at / updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
  ```
  Index `(org_id, active, sort_order)`. **No price/discount/countdown columns — ever** (epic §7).
  Re-run jOOQ codegen after the migration.
- **Service** — `StorefrontBannerService` (constructed in `AppConfig`): CRUD + validation:
  - the headline in the org's `default_locale` is required (400 otherwise); the other locale optional;
  - `target_slug` must resolve **within the org** at write time — an existing category slug, or a
    listing that exists in any status (a listing target that is currently unpublished is *storable*
    but won't serve — see the read); unknown slug → 400 cause-naming;
  - `image_object_key`, when set, must carry the `{orgId}/banner/` prefix (the logo-attach guard) → 400;
  - creating/activating beyond **10 active banners** → 400 (`"an org may have at most 10 active banners"`);
  - window integrity is the DB CHECK, re-validated in service for a clean 400.
- **Admin API** — a `BannerHandler` under `OrgServlet` (the `{orgId}` sub-resource dispatch pattern):
  ```
  GET    /api/orgs/{orgId}/storefront/banners                (VIEWER — all rows, sort_order ASC)
  POST   /api/orgs/{orgId}/storefront/banners                (STAFF)
  PUT    /api/orgs/{orgId}/storefront/banners/{id}           (STAFF — merge semantics like PUT /orgs)
  DELETE /api/orgs/{orgId}/storefront/banners/{id}           (STAFF)
  PUT    /api/orgs/{orgId}/storefront/banners/order          (STAFF — {ids:[…]} set-replaces sort_order,
                                                              the PUT-categories set-replace precedent)
  POST   /api/orgs/{orgId}/storefront/banners/presign        (STAFF — {filename, content_type} →
                                                              {upload_url, object_key, expires_in_seconds},
                                                              reusing the logo presign machinery, prefix {orgId}/banner/)
  ```
  Admin rows serialize both locales, the raw `image_object_key`, and a fresh presigned preview `image_url`.
- **Public read** — on `PublicStorefrontServlet` (GET-only, JWT-bypassed, `rl:pub-read` bucket):
  ```
  GET /api/public/{orgSlug}/banners
  ```
  Returns, `sort_order ASC`, **only** rows that are `active`, inside their window (`now()` between
  bounds, null = open), **and whose target resolves**: `target_type='category'` joins an existing
  category; `target_type='listing'` joins a **PUBLISHED** listing (epic §3 — degradation is here, not
  in the client). Row shape (whitelisted, both locales — the client resolves the fallback so the
  cache stays one entry per org, not per locale):
  ```json
  { "headline_ar": …, "headline_en": …, "subheading_ar": …, "subheading_en": …,
    "image_url": "…presigned GET or absent…", "target_type": "category|listing", "target_slug": "…" }
  ```
  **No** `id`, `org_id`, object keys, window bounds, or timestamps. `Cache-Control: public, max-age=60`
  (≤ the 900s presigned-image TTL). Unknown/inactive org → opaque 404. Non-GET → 405.

### Why the window/target filtering lives in the read

A banner pointing at a listing the merchant unpublished yesterday must not require the merchant to
remember the banner exists. Filtering in the read makes correctness automatic and reversible
(re-publish → banner reappears), keeps the public payload free of state the client would have to
re-check, and costs one JOIN on a ≤10-row set. The 60s cache means window edges are ±60s fuzzy —
acceptable for marketing content, documented here so nobody "fixes" it.

## Scope

### In
Migration + jOOQ codegen; `StorefrontBannerService`; `BannerHandler` CRUD + order + presign;
`PublicStorefrontServlet` banners read; seed migration or test fixture for `acme` parity with the
current config slides; DTOs (`BannerResponse` admin / `PublicBannerResponse` public).

### Out (deferred)
Click/impression analytics; scheduling beyond one window; per-banner locale *visibility* toggles;
rich text; any discount/price semantics (unrepresentable, epic §7); banner-level A/B or ordering
rules beyond `sort_order`.

## Authorization

Admin routes: `requireOrgAccess` — VIEWER read, STAFF write (catalog convention, epic §9). Public
read: none — the boundary is org-by-slug (active-only), the resolving-target JOIN, and the
whitelisted DTO.

## Acceptance criteria

1. STAFF can create a banner with AR-only copy on an AR-default org; creating with **neither**
   headline, or missing the default-locale headline, → 400.
2. `target_slug` validation: an org's own category or listing slug is accepted; another org's slug,
   or an unknown slug, → 400. `target_type` outside the enum → 400.
3. An attach with an `image_object_key` outside `{orgId}/banner/` → 400 (cross-tenant guard); the
   presign route mints keys under that prefix.
4. The 11th **active** banner → 400; deactivating one lets the next activate.
5. `PUT …/order {ids}` set-replaces `sort_order`; ids not belonging to the org → 400, order is atomic.
6. The public read returns only active, in-window, target-resolving banners in `sort_order`:
   a banner whose listing target is unpublished **disappears** from the read and **reappears** on
   re-publish; a deleted category target likewise drops its banner; `starts_at` in the future /
   `ends_at` in the past exclude the row.
7. The public row is whitelisted — no `id`/`org_id`/object key/window/timestamps (JSON scan) — and
   carries both locale columns verbatim; `Cache-Control: public, max-age=60`; unknown org slug →
   opaque 404; POST/PUT/DELETE on the public path → 405.
8. The schema has no price/discount/countdown column (assert by information_schema scan in the IT —
   the honesty rule is structural).

## Tests

`StorefrontBannerAdminIT` (CRUD, validation matrix 1–5, prefix guard, order set-replace) and
`PublicBannersIT` (6–8, plus rate-limit bucket coverage rides `RateLimitFilterTest` unchanged).
Regression: `StorefrontIT` untouched — no existing route changes shape.
Verified live through Tomcat: create → appears on `GET /api/public/{slug}/banners`; unpublish the
target → gone; republish → back; curl the 400 matrix.

## What this unblocks

| Next | Depends on this |
|---|---|
| **Frontend story 30** (admin banner editor + data-driven `DealBanner`; deletes `home-banners.ts`) | the whole surface |
| **C2–C4** | the bilingual column pattern, target validation, org-prefixed presign, cached public read — all established here |
