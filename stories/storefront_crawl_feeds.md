# Slice — Storefront crawl feeds (store index, per-store crawl feed, stable listing image)

> A merchant creates a store, publishes products, and it never appears in Google. Not because we
> block crawling — we ship no `robots.txt` at all, which means allow-all — but because **no URL of
> that store exists anywhere a crawler can reach**. Every storefront lives at one shared origin
> (`store.yabta3.com/{locale}/{orgSlug}/…`), the origin's root page is a `notFound()`, and nothing
> public can enumerate orgs: `GET /api/orgs` is membership-scoped, `GET /api/admin/orgs` is
> ADMIN-only, and `/api/public/*` has no zero-segment route (`parts.length == 0` is a hard 400).
>
> This slice ships the three anonymous reads the frontend's sitemap chain needs. Canonical
> decisions: [`frontst/docs/storefront-crawl-surface-epic.md`](../../frontst/docs/storefront-crawl-surface-epic.md).
> Feeds frontend stories 108 and 111.

## Goal

1. A crawler can discover **which stores exist** (`GET /api/public/storefronts`).
2. For each store, it can enumerate **every indexable URL with an honest `lastmod`**
   (`GET /api/public/{orgSlug}/crawl-feed`).
3. A product's own photo has a **stable, non-expiring URL** so it can be named in structured data
   and in a product link's share card (`GET /api/public/{orgSlug}/listings/{listingSlug}/image`).

**No migration.** Every column these reads need already exists — `product_listing.updated_at` (V40),
`category.updated_at` (V39), `collection.updated_at` (V71), `storefront_page.updated_at` (V56),
`product_listing_image.object_key` (V40). Index work, if any, is measured on `perfdb` per V73's rule
and captured in `tools/seed/results/` — never assumed.

## Design

### 1 · `GET /api/public/storefronts?page=&size=` — the store index

`PageResponse<PublicStorefrontRefResponse>`; rows exactly `{slug, catalog_updated_at}`. Ordered
`slug ASC` — a set, not a queue, so the queue-vs-ledger convention has nothing to say here and a
stable order is what lets a diff of two fetches mean something.

**Membership rule:** `org.active = true` **AND** at least one PUBLISHED `product_listing`. That is
the `GET /api/public/{orgSlug}/collections` rule verbatim — a collection holding no published
listing is never advertised, because an empty shelf in a rail is a lie. An empty store in a search
index is the same lie with a worse audience, and it spends the crawler's budget to reach a page that
tells a shopper nothing. Both predicates live in the SQL, so the rail cannot drift from the index.

`catalog_updated_at` = `MAX(product_listing.updated_at)` over that org's PUBLISHED listings — one
grouped query, not a fan-out. It exists so the **sitemap index** can carry a per-store `<lastmod>`,
which is what tells Google which store sitemaps to re-fetch; without it the frontend would have to
pull every store's whole feed just to date the index. Deliberately the *listing* maximum and not a
`GREATEST` across four tables: it is the dominant signal, it is one index-servable aggregate, and
naming the field `catalog_updated_at` says exactly what it measures.

**Routing — and a reserved slug.** `PublicStorefrontServlet` reads `parts[0]` as the org slug, so
this route is matched **ahead of org resolution** in the same servlet — one branch, no second
servlet mapping, since it is a single GET with no subresources (the separate `/api/public/orders/*`
and `/api/public/unsubscribe/*` mappings exist because those are whole resources with their own
services). Either way the consequence is identical: `storefronts` becomes a **third reserved
slug**.

> **Latent defect this exposes, fixed here.** `OrgService` has **no reserved-slug guard** today, so
> an org can already register the slug `orders` or `unsubscribe` and its storefront is silently
> shadowed by those two mappings — an unreachable store with no error anywhere. Adding a third
> reserved word makes it worth closing: `OrgService.create`/`update` reject
> `{orders, unsubscribe, storefronts}` with a cause-naming 400. One constant, one check, asserted.

`Cache-Control: public, max-age=3600`. `PUBLIC_READ_LIMIT` bounds apply via D9's bucket below.
`Pagination` defaults stand (`page=0`, `size=20`, hard cap 100).

### 2 · `GET /api/public/{orgSlug}/crawl-feed` — one store's indexable URL set

One call, one lean DTO — **not** the catalog read. Reusing `GET /listings?size=100` costs
`ceil(N/100)` requests of fully-enriched rows (batch-loaded images, variants, categories, rating
aggregates) to extract two fields, against a bucket shared with real shoppers.

```
{
  "listings":    [{"slug": "kettle",   "updated_at": "…"}],
  "categories":  [{"slug": "kitchen",  "updated_at": "…"}],
  "collections": [{"slug": "ramadan",  "updated_at": "…"}],
  "pages":       [{"kind":  "about",   "updated_at": "…"}],
  "has_featured": true,
  "total_listings": 412,
  "truncated": false
}
```

- **PUBLISHED-only** listings; categories and collections filtered to those holding ≥ 1 published
  listing (the D2 rule, third application — an empty category page is thin content).
- `pages` reuses the C4 read's shape and its rule: only the kinds that **exist**
  (`about`/`policies`, closed set of two, `CHECK` in V56).
- `has_featured` is a boolean, not a count: the frontend needs it only to decide whether
  `/featured` belongs in the sitemap at all (an empty featured page is a URL that says nothing).
- **`truncated` + `total_listings` instead of a silent cap.** `listings` is capped at 5 000 (with
  2 locales that is 10 000 URLs, comfortably inside the sitemap protocol's 50 000/file limit).
  Beyond the cap the response says so rather than presenting a partial catalog as complete —
  sharding is deferred, and a deferred cap that announces itself is the difference between a
  known limit and a bug. No store is near it.
- No id, no title, no price, no image, no status crosses. The DTO is a URL set with dates.

**`updated_at` is the entity row's, and translation tables have none.** `product_listing_translation`
and `collection_translation` (V63/V71) carry no `updated_at` column, so an edit that changes *only*
a localized name does not move the parent's timestamp and the URL's `lastmod` will not advance.
Stated rather than papered over: the alternative is stamping `now()`, which teaches Google the field
is noise. If translation-cadence `lastmod` ever matters it is a column on those tables, not a lie here.

`Cache-Control: public, max-age=1800`. Unknown or inactive slug → the same opaque 404 every public
read gives (`StorefrontService.resolveOrg`).

### 3 · `GET /api/public/{orgSlug}/listings/{listingSlug}/image` — the stable product image

The `og-image` route's pattern, applied per listing: resolve the org (active-only) → resolve the
listing (**PUBLISHED-only**, opaque 404 otherwise, the same resolution the listing detail uses) →
take the **primary** image (lowest `sort_order`, the tie-break the grid already uses) → fetch the
object with `DocumentRenderService`'s tight-timeout pattern → stream the **bytes** with the object's
content type and `Cache-Control: public, max-age=3600`. No image → 404. Storage failure → 404.

**Why bytes, never a redirect or a presigned URL** — the C2 argument, unchanged: every catalog image
URL is a ~900s presigned GET, and a third-party cache (a social scraper, Google's image index) holds
a preview far longer than that. A 302 gets its *target* cached by some scrapers, which is the same
bug one hop later. This is the route that lets frontend story 111 name a real product photo in
`Product.image`, and it retroactively closes the gap story 31 documented when it fell back to the
**store** og image on product pages — a product link pasted into WhatsApp can now unfurl with the
product's own photo.

v1 serves the primary image only. A `?i={n}` for secondary images is a trivial extension when
structured data or a gallery unfurl wants it; it is not needed to ship either consumer.

### 4 · Rate limiting — `rl:pub-sitemap`

Both crawl reads are anonymous `/api/public/` GETs and would inherit `rl:pub-read` (120/min per IP).
Sitemap regeneration arrives from **one** IP — the storefront's Next container over the compose
network — so a burst of store-sitemap builds would consume a bucket shared with real shoppers behind
the same proxy. New bucket `rl:pub-sitemap` (`PUBLIC_SITEMAP_LIMIT`, default **600**/min), matched
in `RateLimitFilter.doFilter` **before** the `/api/public/` catch-all. That ordering is the whole
mechanism — the `rl:pub-coupon` branch is the worked example and its comment says so. The listing
image route stays on `rl:pub-read`: it is fetched by shoppers and scrapers alike, at shopper cadence.

## Scope

### In
The three endpoints + their DTOs; `PublicStorefrontRefResponse` / `PublicCrawlFeedResponse` and the
repository reads behind them; the `rl:pub-sitemap` bucket + `PUBLIC_SITEMAP_LIMIT`; the reserved-slug
guard in `OrgService`; `PublicStorefrontServlet` javadoc route table + `CLAUDE.md` public section
updated.

### Out
Any migration. Widening `PublicListingResponse` (its whitelist is pinned by a structural test and
its javadoc states timestamps are deliberately omitted — a separate DTO keeps that guarantee rather
than spending it on a crawler). Sitemap XML generation (that is the frontend's job — this slice
ships JSON). A merchant opt-out column (epic D2: one nullable column, one toggle, one `AND` here,
if it is ever wanted). `?i={n}` secondary images. `IndexNow` push.

## Acceptance criteria

1. `GET /api/public/storefronts` lists exactly the active orgs holding ≥ 1 PUBLISHED listing,
   `slug ASC`, paged; a store with only DRAFT listings is **absent**; a suspended or pending org is
   **absent**; each row's `catalog_updated_at` equals that org's newest published listing timestamp.
2. `GET /api/public/{orgSlug}/crawl-feed` returns only published/non-empty entities; `pages` names
   only kinds that exist; `has_featured` is `false` for a store with no featured listings;
   `truncated` is `false` below the cap and `true` (with an honest `total_listings`) above it.
3. `GET /api/public/{orgSlug}/listings/{slug}/image` streams the primary image's bytes with its own
   content type and `max-age=3600`; a DRAFT/ARCHIVED/unknown listing and an imageless listing are
   both an opaque **404**; the response body is **never** a redirect and no presigned URL appears in
   any header.
4. Unknown or inactive `orgSlug` → 404 on both `{orgSlug}` routes, identical to every other public
   read. Non-GET on any of the three → 405.
5. Creating or updating an org with slug `orders`, `unsubscribe`, or `storefronts` → 400 naming the
   reserved word.
6. The crawl reads bucket on `rl:pub-sitemap`, not `rl:pub-read`; exceeding it → 429.
7. `mvn test` green, including the existing `StorefrontIT` whitelist assertions (untouched — this
   slice adds DTOs, it does not widen one).

## Tests

**IT (`api`, TestContainers)** — a new `StorefrontCrawlFeedIT` alongside `StorefrontIT`:
the membership matrix for `/storefronts` (published / draft-only / suspended / pending), ordering
and `catalog_updated_at` correctness; the feed's four collections and their empty-entity filters,
`has_featured` both ways, the truncation flag; the image route's stream-not-redirect assertion
(status 200, a real content type, **no `Location` header and no `X-Amz-` anywhere**) and its three
404 shapes. **Structural:** re-assert from this branch that `PublicListingResponse` still carries no
timestamp — the guarantee this slice deliberately did not spend. **Unit:** the reserved-slug guard;
the feed cap arithmetic. **`OrgSeoMetadataIT`** unchanged.

## New dependencies

None.

## Definition of done

The discovery chain has a source: something public can say which stores exist and what each one's
URLs are, without widening a single existing whitelisted DTO and without a migration. `lastmod` is
real or absent, never `now()`. The image route makes a product's own photo nameable by a crawler and
a share card, closing story 31's noted gap. Three reserved slugs are reserved *in code* rather than
by luck. `PublicStorefrontServlet`'s javadoc route table and `CLAUDE.md`'s public section both name
the new routes.
