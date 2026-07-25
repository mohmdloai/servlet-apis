# Slice: Named collections (`collection` + `?collection={slug}`)

> Roadmap item 8 (Tier 3) — [`docs/storefront-growth-roadmap.md`](../docs/storefront-growth-roadmap.md).
> Generalize the single implicit "Featured" list into merchant-defined **collections** ("Best of
> Best", "Ramadan picks") with their own slugs and landing pages — exactly Souq's category-circle
> merchandising, and honest by construction (curation, never fake deals). The featured slice
> (`storefront_featured_listings.md`) predicted this: *"generalizes this column into a table when a
> second list is needed."* Feeds frontend story 70.

---

## Goal

STAFF creates named, ordered lists of listings; the storefront shows a collections rail and serves
each collection as a landing page at its slug — curated order, PUBLISHED-only, whitelisted rows,
the standard B3 read doing the work via one new predicate.

## Decision: additive beside `featured_sort`, not a migration of it

Featured stays exactly as shipped (the zero-config home strip: one column, one set-replace, admin
UI, frontend story 32, `?featured=true` on the public wire). Collections are the *N-named-lists*
feature beside it. Migrating featured into a seeded collection would buy conceptual purity at the
cost of a data migration, a public-wire break (`?featured=true` is deployed), and rework of a
shipped admin surface — for zero merchant-visible gain. If consolidation is ever wanted, it's a
follow-up slice, not this one.

## Migration — **V71** (verify V70 is still highwater)

```
collection (
    id UUID PK, org_id UUID NOT NULL REFERENCES org,
    slug VARCHAR(80) NOT NULL, sort_order INT NOT NULL DEFAULT 0,
    created_at / updated_at,
    UNIQUE (org_id, slug)
)
collection_translation (collection_id FK CASCADE, language CHECK ar|en, name, UNIQUE(collection_id, language))
collection_listing (
    collection_id UUID FK CASCADE, product_listing_id UUID FK CASCADE,
    sort INT NOT NULL, PK (collection_id, product_listing_id)
)
-- index (product_listing_id) on the join for the reverse lookup / cascade path
```

The V63 translation-table pattern for the bilingual name; both join FKs cascade so the table
self-heals on listing/collection deletion. jOOQ codegen after.

## Admin API (new `CollectionHandler` on the `OrgServlet` dispatch; the categories/featured precedents)

- `GET /api/orgs/{orgId}/collections` (VIEWER) — all collections, `sort_order ASC, slug ASC`, each
  with both names + `listing_count` (batch, no N+1).
- `POST /api/orgs/{orgId}/collections` (STAFF) — `{slug, name_ar, name_en, sort_order?}`; slug =
  the house slug rules; duplicate → 409; **cap ≤ 30 collections/org** → 400.
- `GET|PUT|DELETE /{id}` — read / edit names+slug+sort (slug edit allowed — collections carry no
  money history) / delete (join cascades; deleting a collection never touches listings).
- `PUT /{id}/listings` (STAFF) — **atomic set-replace**, the `PUT /product-listings/featured`
  semantics verbatim: `{listing_ids:[…]}`, array order = `sort` 0..n-1, every id org-owned (400,
  atomic), duplicates → 400, **cap ≤ 100 per collection** (landing pages paginate; featured's 12
  was a strip-sized cap). Any status storable (stage a DRAFT for launch); PUBLISHED-only ever
  serves.

## Public surface (whitelist discipline)

- `GET /api/public/{orgSlug}/collections` — the rail/nav read: collections that have **≥ 1
  PUBLISHED listing** (an empty shelf is never advertised), `sort_order ASC, slug ASC`, rows
  exactly `{slug, name}` (locale-resolved via `?locale=`, the standard machinery).
  `Cache-Control: public, max-age=300` (profile-read tier). No ids cross.
- **`?collection={slug}`** — one more predicate on `GET /api/public/{orgSlug}/listings`, the
  `?featured=true` playbook: AND-composes with the whole B3 grammar (q/price/category/sold/
  `attr_*`/page/size); when present, **default order = the curated `sort ASC`** (an explicit
  `?sort=` overrides — grammar stays uniform); PUBLISHED hard-coded; unknown slug → empty result
  (the category convention — stale URLs never error); blank → 400. Envelope/DTO/`max-age=60`
  unchanged.

## Explicitly NOT in this slice

Touching `featured_sort` or its routes. Cover/hero images per collection (the future seam: a
nullable `image_object_key` + the banner presign pattern). Collection-level SEO metadata. Auto
(computed) collections. Frontend (story 70).

## Tests (`CollectionsIT` — models: `FeaturedListingsIT` for curation, `StorefrontSearchIT` for the grammar; auth matrix per `ProductListingHandlerAuthTest`)

1. **Admin roundtrip**: create (ar+en names) → set-replace 3 listings → GET echoes order + count;
   re-PUT reordered → new order; caps (31st collection, 101st listing, duplicate ids, foreign id)
   → 400/409 each, atomic.
2. **Public read**: `?collection=x` serves the curated order, PUBLISHED-only (unpublish → drops,
   republish → returns at its curated position); DRAFT staged listing invisible; explicit
   `?sort=price_asc` overrides; composes with `q`+price+`attr_*`; unknown slug → empty 200.
3. **Rail honesty**: the public collections list omits a collection whose listings are all
   DRAFT/unpublished, and includes it the moment one publishes; locale names resolve (ar default,
   `?locale=en`).
4. **Lifecycle**: deleting a listing cascades it out of collections; deleting a collection leaves
   listings intact; slug rename serves at the new slug, old slug → empty.
5. **No-leak + isolation**: rows stay the whitelisted shape; cross-org invisible; VIEWER/STAFF
   matrix on the admin routes.
6. **Regression**: featured routes + `?featured=true` byte-identical (existing
   `FeaturedListingsIT` unchanged and green).

## Definition of done

V71 + codegen; handler + service + repo + the predicate; all six IT groups green; existing
storefront read suites untouched and green; spotless clean; full `-Dtest='*IT'` sweep green.
