# Slice C3: Featured listings (merchant curation for the storefront home)

> The storefront home's product strips are honest but blunt: "Newest" (`published_at DESC`) plus one
> strip per leading category — self-assembling, zero merchant control. There is no merchandising
> signal anywhere in the schema: nothing marks *"put this one on the front page."* This slice adds
> the smallest true curation primitive: an **ordered, org-scoped pinned list** of listings, edited
> as one atomic set, served through the existing public listings read as one more optional
> predicate.
>
> Canonical decisions: [`frontst/docs/storefront-customization-epic.md`](../../frontst/docs/storefront-customization-epic.md)
> (§2 slugs-only, §9 authz, §10 caps). Extends the B3 filtered read
> ([`storefront_search_and_filters.md`](storefront_search_and_filters.md)) — same repository, one
> more predicate, no new public path. Feeds frontend story 32.

---

## Goal

STAFF curates up to **12** featured listings in an explicit order; the storefront asks
`GET /api/public/{orgSlug}/listings?featured=true` and gets exactly those, in that order,
PUBLISHED-only as always. Unpublishing a featured listing silently drops it from the public result
(and returns on re-publish) — curation can never resurrect a DRAFT.

## Why a column + set-replace, not a collection table

A `featured_sort` column on `product_listing` is one migration, zero joins, and composes with the
entire B3 predicate/sort machinery for free. A `collection` table (many named, orderable lists) is
the general future — and premature: no surface needs a second list yet. The set-replace write (the
`PUT …/categories` precedent) makes ordering atomic and idempotent, and keeps "the featured list"
a single fact rather than N racy per-row toggles.

## Design

- **Migration (next `V##`)** — `product_listing` gains `featured_sort INT NULL`
  (`NULL` = not featured; ascending display order). Partial index
  `ON product_listing (org_id, featured_sort) WHERE featured_sort IS NOT NULL`. jOOQ codegen.
- **Admin API** — on the existing `ProductListingHandler` mount:
  ```
  GET /api/orgs/{orgId}/product-listings/featured          (VIEWER — the ordered list, full admin rows)
  PUT /api/orgs/{orgId}/product-listings/featured          (STAFF — {listing_ids:[…]} set-replace)
  ```
  PUT semantics: array order = `featured_sort` 0..n-1; every id must belong to the org (400
  otherwise, atomic — no partial application); > 12 ids → 400 (`"at most 12 featured listings"`);
  duplicate ids → 400; ids absent from the array are cleared to NULL. Any status is *storable*
  (a merchant may stage a DRAFT for launch); only PUBLISHED ever serves publicly.
- **Public read** — `?featured=true` joins the B3 parameter set on the existing
  `GET /api/public/{orgSlug}/listings`:
  - `findByFilters`/`countByFilters` gain the optional `featuredOnly` predicate
    (`featured_sort IS NOT NULL`); when present, default order becomes `featured_sort ASC`
    (an explicit `?sort=` still overrides — the parameter grammar stays uniform);
  - composes with everything else by the same AND rule (`?featured=true&category=…` is legal);
  - values other than `true`/absent → 400 (the unknown-`sort` convention: no silent coercion);
  - PUBLISHED stays hard-coded — a featured DRAFT is invisible publicly, by construction;
  - envelope, whitelisted DTO, `Cache-Control: public, max-age=60` all unchanged.

## Scope

### In
Migration + codegen; the two admin routes + validation; the repository predicate + featured default
order; `?featured=true` parse-or-400 in `StorefrontService`/`PublicStorefrontServlet`.

### Out (deferred)
Named collections / multiple lists; per-category curation; scheduling; popularity or sales-derived
ranking (no signal exists — the honesty rule from the commerce epic); a public "featured" flag on
non-featured reads (rows do **not** grow a `featured` field — the *list* is the feature).

## Authorization

VIEWER read / STAFF write (catalog convention, epic §9). Public read unchanged: anonymous,
org-by-slug, PUBLISHED-only, whitelisted, `rl:pub-read`.

## Acceptance criteria

1. `PUT …/featured {listing_ids}` set-replaces atomically: order persisted as 0..n-1; omitted ids
   cleared; replay idempotent. Foreign/unknown/duplicate ids or >12 → 400, nothing applied.
2. `GET …/featured` (admin) returns the ordered rows in every status.
3. `?featured=true` returns exactly the org's featured **PUBLISHED** listings in `featured_sort`
   order; unpublish drops one (re-publish restores it, order intact); an org with none → empty page
   (200, not 404).
4. Composition: `?featured=true&sort=price_asc` re-sorts; `?featured=true&category=x` intersects;
   `?featured=nope` → 400.
5. The public row shape is unchanged — no `featured_sort`/`featured` field leaks (JSON scan);
   envelope + cache header unchanged; B3 regression (`StorefrontSearchIT`) untouched by the new
   predicate defaulting.

## Tests

`FeaturedListingsIT`: the set-replace matrix (1–2), public composition + drop/restore (3–4),
no-leak scan + regression rider (5). `StorefrontIT`/`StorefrontSearchIT` stay green with all-null
`featuredOnly`. Verified live through Tomcat: curate via PUT, curl `?featured=true`, unpublish one,
re-curl.

## What this unblocks

| Next | Depends on this |
|---|---|
| **Frontend story 32** — the admin curation picker + the home "Featured" strip | the list + the read |
| Named collections (later) | generalizes this column into a table when a second list is needed |
