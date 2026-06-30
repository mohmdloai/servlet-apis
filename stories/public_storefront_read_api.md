# Slice: Public storefront read API (anonymous)

> The CQRS **query side** of the Catalog context — the public, anonymous surface a storefront
> renders from. Builds directly on [`build_product_catalog.md`](build_product_catalog.md) and
> [`docs/catalog-architecture.md`](../docs/catalog-architecture.md). It is **not** a new
> aggregate: it owns no state, no lifecycle, no invariants — it is a read model over
> `product_listing` + `category`.

---

## Goal

`GET /api/public/{orgSlug}/...` — an **anonymous**, CDN-cacheable read surface that serves a
shopper exactly the published catalog and nothing else. Enforce the safety guarantees at the
**source of truth**, not in a frontend proxy.

Done means: an unauthenticated caller can browse an org's PUBLISHED listings by slug, read one
listing with its images and category breadcrumbs, and read the category nav — while a DRAFT or
ARCHIVED listing is invisible even by its exact slug, and no internal field (`product_id`, status,
ids, timestamps, object keys) ever appears in a response.

---

## Why a backend API and not just a BFF proxy

The admin endpoints (`/api/orgs/{orgId}/product-listings`) are wrong for a shopfront: they
require an org role, key off org **UUID**, can return any status, and serialize internal fields.
A Next.js BFF *could* paper over that, but then "never leak a draft / never leak margin" lives in
proxy code. Putting it in the backend makes it a property of the system: the read path only ever
touches `product_listing`, so there is no row from which cost/supplier/`base_price` *could* leak,
and `status = PUBLISHED` is hard-coded (never a parameter). This is the slice we deferred when the
catalog was built "authenticated-only".

---

## Design

- **`StorefrontService`** (read model): resolves the org by slug (404 if missing or `active =
  false`); serves only `ListingStatus.PUBLISHED`; presigns image GET URLs; returns whitelisted
  view records. No transactions, no writes.
- **`PublicStorefrontServlet`** at `/api/public/*`, mounted in `EmbeddedTomcatLauncher` and
  **bypassed by `JwtAuthFilter`** (prefix `/api/public/`). GET-only (else 405). Sets
  `Cache-Control: public, max-age=60` on success — safely below the presigned image-URL TTL
  (900s), so cached pages never carry dead URLs.
- **Whitelisted DTOs** (`PublicListingResponse`, `PublicListingImageResponse`,
  `PublicCategoryRefResponse`, `PublicCategoryResponse`): slug/title/marketing_copy/sales_price/
  images, plus category breadcrumbs on the detail view. No id, `product_id`, status, timestamps,
  or object keys.
- New repository reads: `ProductListingRepository.findBySlugAndStatus`,
  `findByCategoryAndStatus` (+count); `CategoryRepository.findBySlug`, `findByIds`.

---

## API contract

| Method | Path | Notes |
|---|---|---|
| GET | `/api/public/{orgSlug}/listings` | published only; `?category=<slug>`, `?page` (0), `?size` (20) |
| GET | `/api/public/{orgSlug}/listings/{listingSlug}` | one published listing + images + category breadcrumbs |
| GET | `/api/public/{orgSlug}/categories` | nav: `{name, slug, parent_slug}` |

`PublicListingResponse`: `{slug, title, marketing_copy, sales_price, images:[{url, alt_text,
sort_order}], categories?:[{name, slug}]}` (categories only on the detail view).

### Errors
| Status | Cause |
|---|---|
| `404` | unknown/inactive org slug, unknown listing slug (incl. a non-PUBLISHED one), unknown `?category` slug |
| `405` | any non-GET method |
| `400` | non-integer `page`/`size`, or `size` out of 1–100 |

No authentication: there is no `401` path — the JWT filter is bypassed for `/api/public/*`.

---

## Scope

### In
- `StorefrontService`, `PublicStorefrontServlet`, the public DTOs, the four repository read
  methods, and the wiring (`AppConfig` + launcher mount + filter bypass).

### Out (deferred)
- **Checkout stays off the public surface**: placing an order needs `product_id` (internal), so
  it goes through the authenticated SalesOrder path via a service role — the public API never
  exposes the listing→product mapping.
- Public rate-limiting on `/api/public/*` (the `RateLimitFilter` currently guards only auth).
- Stable public-bucket/CDN image URLs for longer cache TTLs (this slice presigns).
- Storefront search and price-range filters.

---

## Authorization
None — anonymous by design. The single security boundary is the read model itself: org-by-slug
(active only), PUBLISHED-only, whitelisted fields, `product_listing`-only reads.

---

## Tests

`api/src/test/java/.../catalog/StorefrontIT.java` (TestContainers; presigning is offline so no
MinIO):
- only PUBLISHED listings are visible; a DRAFT slug 404s even by direct slug;
- the published detail carries category breadcrumbs and presigned image URLs;
- the `?category` filter returns only that category; an unknown category slug 404s;
- an unknown org slug and an **inactive** org both 404;
- the category nav resolves each child's `parent_slug`.

Verified live through Tomcat: anonymous `GET` returns 200 with `Cache-Control: public,
max-age=60` and a body free of `product_id`/`org_id`/`status`/timestamps; after `unpublish` the
listing's detail 404s and it drops out of the list; POST → 405.
