# Slice: Storefront search + filters (extends the listings read)

> The shopper-facing catalog is browsable but not **searchable**: [`public_storefront_read_api.md`](public_storefront_read_api.md)
> ships `GET /api/public/{orgSlug}/listings` with only a `?category=` narrow, `?page`/`?size` paging,
> and PUBLISHED-only visibility — it explicitly deferred *"storefront search and price-range filters."*
> This slice closes exactly that gap: a free-text query, price bounds, and sort order **on the same
> path**, still anonymous, still PUBLISHED-only, still cacheable.
>
> Canonical contract & decisions: [`frontst/docs/storefront-commerce-epic.md`](../../frontst/docs/storefront-commerce-epic.md) (B3, under "The public API contract").
> Sibling of [`storefront_org_profile.md`](storefront_org_profile.md) and [`storefront_availability_signal.md`](storefront_availability_signal.md)
> (same anonymous read-model surface). Feeds frontend story 26 (catalog / search).

---

## Goal

Extend `GET /api/public/{orgSlug}/listings` with `?q=`, `?min_price=`, `?max_price=`, and
`?sort=price_asc|price_desc|newest`, composed with the existing `?category=&page=&size=`. All filters
AND together over the org's PUBLISHED listings; `status` is **never** a parameter (PUBLISHED is
hard-coded, exactly as today). No new path, no new servlet — the existing GET-only, JWT-bypassed
`PublicStorefrontServlet` + `StorefrontService` gain the parameters.

Done means: an unauthenticated caller can type "gopro" and get only PUBLISHED listings whose `title`
or `marketing_copy` contains it (case-insensitive), clamp the result to an EGP price band, sort by
price or recency, page through it, and still never see a DRAFT/ARCHIVED row or any internal field —
while a bad `sort` or a non-numeric price is a clean `400`, not a 500 or a silent ignore.

---

## Why extend the read, not a separate search service

- **It is the same read model, one more `WHERE`.** Search here is `title`/`marketing_copy` `ILIKE`
  plus numeric bounds on `sales_price` plus an `ORDER BY` — all columns already on `product_listing`
  (V40), already served PUBLISHED-only by `StorefrontService`. A separate `/search` endpoint would
  duplicate the org-by-slug resolution, the PUBLISHED-only invariant, the whitelisted DTO, and the
  paging envelope — four things the epic pins **once** so no two stories drift.
- **The frontend catalog is one grid.** Story 26 renders a single listing grid whose category chip,
  search box, price slider, and sort dropdown are all just query-string state over one endpoint. A
  shopper who searches within a category, or sorts a price-filtered result, must not hit a different
  URL — the filters are orthogonal facets of the *same* list, so they belong on the same request.
- **Safety stays a property of the path, not the caller.** Because search runs inside the read model,
  there is no row from which a DRAFT title or an internal `base_price` *could* match — the query only
  ever touches `product_listing WHERE status='PUBLISHED'`. Putting it anywhere else would re-open the
  leak surface `public_storefront_read_api.md` deliberately closed.
- **No search infrastructure is warranted yet.** `ILIKE '%q%'` on a per-org published set (tens–low
  thousands of rows) is a bounded substring scan behind the org filter — correct and cheap at this
  scale. Relevance ranking, trigram, and full-text are real work with real index cost; deferring them
  (see §Scope Out) keeps this slice a one-query extension.

---

## Design

- **`StorefrontService.listListings(...)`** (the method behind the existing `?category=&page=&size=`
  read) gains optional `q`, `minPrice`, `maxPrice`, and a `sort` enum. It parses/validates the new
  params, then delegates to a **generalized repository read** (below) with PUBLISHED hard-coded. Same
  whitelisted `PublicListingResponse` rows, same `PageResponse`-style paged envelope as today.
- **`PublicStorefrontServlet`** reads the new query params off the existing `/{orgSlug}/listings`
  route — no new route, no new mount, no filter change. GET-only (non-GET stays 405). On success:
  the **same** `Cache-Control: public, max-age=60` as today (see §Cache).
- **Repository — generalize into one filtered read.** Replace the pair
  `ProductListingRepository.findByCategoryAndStatus(orgId, categorySlug, status, page, size)` (+count)
  with:
  ```
  findByFilters(orgId, status /* =PUBLISHED */, categorySlug?, q?, minPrice?, maxPrice?, sort, page, size)
  countByFilters(orgId, status,                 categorySlug?, q?, minPrice?, maxPrice?)
  ```
  A jOOQ query built from optional predicates: `org_id = ?`, `status = 'PUBLISHED'`, and — each only
  when present — `category` join, `(title ILIKE ? OR marketing_copy ILIKE ?)`, `sales_price >= ?`,
  `sales_price <= ?`. `findByCategoryAndStatus` is **subsumed** — `category` becomes just one more
  optional predicate, and the old call site is `findByFilters(..., category, null, null, null, NEWEST,
  page, size)`. (If keeping the old method thin-wraps the new one is preferred to minimize churn, it
  may delegate; the count method mirrors the same predicate set so `total` matches the page.)
- **`q` handling.** Trim the raw param; a null/empty/blank `q` is **ignored** (no predicate — the
  unfiltered/category list is unchanged). Otherwise the predicate is
  `title ILIKE '%'||q||'%' OR marketing_copy ILIKE '%'||q||'%'`, bound as a parameter (never string
  interpolation — jOOQ binds it; `%`/`_` in the query are treated as literal user text at this scale,
  no escaping needed for v1). Case-insensitivity comes from `ILIKE`.
- **`sort` handling.** An enum `ListingSort ∈ {NEWEST, PRICE_ASC, PRICE_DESC}`, default `NEWEST`.
  - `NEWEST` → `ORDER BY published_at DESC` (the field the epic pins for recency), tie-broken by a
    stable key (`slug ASC`, or the internal id ASC — never serialized) so paging is deterministic and
    two listings published in the same instant never straddle a page boundary.
  - `PRICE_ASC` / `PRICE_DESC` → `ORDER BY sales_price ASC|DESC`, same stable tie-break.
  An unknown `sort` value is a `400` (never silently coerced to default — a typo'd sort must surface,
  matching the epic's "Bad `sort` … → `400`").
- **Price bounds.** `min_price`/`max_price` parse as non-negative EGP decimals against
  `sales_price NUMERIC(12,2)`, **inclusive** (`>=` / `<=`). Non-numeric or negative → `400`. When both
  are present and `min_price > max_price` → **`400`** (see rationale below).
- **Index implications.** The partial index `product_listing_published_idx ON (org_id, status) WHERE
  status='PUBLISHED'` (V40) already covers the org + PUBLISHED filter that fronts every query, so the
  filtered scan starts from the published set, not the whole table. Price bounds and the sort are
  evaluated over that already-narrowed set. `q` is a **substring scan** (`ILIKE '%…%'` is not
  index-usable by a b-tree) — acceptable at per-org published scale; a `pg_trgm` GIN index or
  `tsvector` full-text is the documented upgrade path (§Scope Out) if a merchant's published catalog
  ever grows enough to feel it. No new migration in this slice.

### Why `min > max` → 400 (not an empty page)

Both are defensible; this slice picks **`400`**. `min_price > max_price` is not a valid band a UI
control can produce — a two-thumb price slider constrains `min ≤ max` structurally, so an inverted
pair reaching the server is a **malformed request** (hand-built URL, buggy client), and the same
"bad price input → `400`" contract that rejects a non-numeric or negative bound should reject an
impossible range rather than masquerade success with an always-empty page (which reads to the client
as "this store has nothing in that band" — a misleading, un-actionable signal). The error body names
the cause (`min_price must not exceed max_price`) so the client can fix the control, matching the
epic's explicit "Bad … price → `400`."

---

## API contract

Same path, same method, same cache as the shipped read — new optional query params only.

```
GET /api/public/{orgSlug}/listings?q=&min_price=&max_price=&sort=price_asc|price_desc|newest&category=&page=&size=
```

### Parameters

| Param | Type | Default | Meaning |
|---|---|---|---|
| `q` | string | *(none)* | Case-insensitive substring (`ILIKE '%q%'`) over `title` **and** `marketing_copy`, PUBLISHED-only. Trimmed; empty/blank → ignored (no filter). |
| `min_price` | decimal (EGP) | *(none)* | Inclusive lower bound on `sales_price` (`sales_price >= min_price`). |
| `max_price` | decimal (EGP) | *(none)* | Inclusive upper bound on `sales_price` (`sales_price <= max_price`). |
| `sort` | enum | `newest` | `newest` = `published_at DESC`; `price_asc`/`price_desc` = `sales_price` asc/desc. All stable-tie-broken. |
| `category` | slug | *(none)* | Existing — narrow to one category (unchanged; now one predicate among many). |
| `page` | int ≥ 0 | `0` | Existing — page index. |
| `size` | int 1–100 | `20` | Existing — page size. |

All present filters **AND** together. `status` is not a parameter — PUBLISHED is always enforced.
The response body and paging envelope are **unchanged** from `public_storefront_read_api.md`
(`PublicListingResponse` rows: `{slug, title, marketing_copy, sales_price, images:[…]}`; no `id`,
`product_id`, `status`, timestamps, or object keys).

### Errors

| Status | Cause |
|---|---|
| `400` | non-integer `page`/`size`, or `size` out of 1–100 (existing); **unknown `sort`** value; `min_price`/`max_price` **non-numeric or negative**; `min_price > max_price` |
| `404` | unknown/inactive org slug (existing); unknown `?category` slug (existing) |
| `405` | any non-GET method (existing) |

No `401`/`403` — the JWT filter bypasses `/api/public/*`. A `q` that matches nothing, or a valid price
band with no listings, is a **`200` with an empty page** (a well-formed query legitimately returning
zero rows) — distinct from the `400`s above, which are malformed inputs.

---

## Scope

### In
- New optional params `q`, `min_price`, `max_price`, `sort` on the existing `/{orgSlug}/listings`
  route; parse + validate in `PublicStorefrontServlet`/`StorefrontService`.
- `ListingSort` enum (`NEWEST`/`PRICE_ASC`/`PRICE_DESC`) with parse-or-400.
- `ProductListingRepository.findByFilters(...)` + `countByFilters(...)` (+ `…Impl`), generalizing —
  and subsuming — `findByCategoryAndStatus` (+count) into optional predicates.
- PUBLISHED-only stays hard-coded; whitelisted DTO + paged envelope unchanged.
- `Cache-Control: public, max-age=60` unchanged (§Cache).

### Out (deferred)
- **Relevance ranking** — results are ordered by the chosen `sort` (recency/price), not by match
  quality; no scoring, no "best match" default.
- **Full-text search** (`tsvector`/`websearch_to_tsquery`) and **trigram** (`pg_trgm` GIN) — the
  documented upgrade path once substring scan stops being cheap; not built here.
- **Faceted filters / facet counts** (brand, attribute, "N results in category X") — no aggregation.
- **Typeahead / autocomplete / search suggestions** — no prefix endpoint.
- **"Did you mean" / fuzzy / spelling correction** — exact substring only.
- **Per-locale search** — v1 searches the single stored `title`/`marketing_copy`; no AR/EN-specific
  analyzers or per-locale columns.
- **Stock filter** (`in_stock`) — that is B2's availability signal
  ([`storefront_availability_signal.md`](storefront_availability_signal.md)), not a search param here.
- **New migration / index** — none; the existing partial published index fronts the query.

---

## Authorization

None — anonymous by design, identical to the shipped read. The single security boundary is the read
model itself: org-by-slug (active-only), **PUBLISHED-only** (never a parameter), whitelisted fields,
`product_listing`-only reads. Search adds filters *inside* that boundary; it cannot widen it — no
input can make a DRAFT/ARCHIVED row match, since `status='PUBLISHED'` is ANDed before any user
predicate.

---

## Acceptance criteria

1. **`q` matches title AND copy, case-insensitively.** Seed one PUBLISHED listing whose term is only
   in `title` and one whose term is only in `marketing_copy`; `?q=<term>` returns **both**. A
   different-cased query (`?q=GOPRO` vs stored "GoPro") still matches.
2. **`q` is trimmed and empty-ignored.** `?q=` and `?q=%20%20` (blank) return the same set as no `q`
   at all (the full PUBLISHED/category list) — not an empty page.
3. **Price range is inclusive.** With listings at 100.00 / 150.00 / 200.00, `?min_price=100&max_price=200`
   returns all three; `?min_price=150` returns 150 and 200; `?max_price=150` returns 100 and 150.
4. **Each sort order.** `?sort=price_asc` → ascending `sales_price`; `?sort=price_desc` → descending;
   `?sort=newest` (and the default, no `sort`) → `published_at DESC`; ties are stable across pages.
5. **Combined filters AND.** `?category=<c>&q=<term>&min_price=<a>&max_price=<b>&sort=price_asc&page=0&size=10`
   returns only rows satisfying **all** of category ∧ substring ∧ band ∧ PUBLISHED, price-ascending,
   correctly paged (`total` from `countByFilters` matches the same predicate set).
6. **Unknown `sort` → 400.** `?sort=cheapest` (or any non-enum value) → `400`, not a silent default.
7. **Bad price → 400.** `?min_price=abc`, `?max_price=-1`, and `?min_price=200&max_price=100`
   (min > max) each → `400` with a cause-naming message.
8. **No DRAFT/ARCHIVED leaks under any filter.** A DRAFT listing whose `title`/`marketing_copy`
   contains the query term, and whose `sales_price` sits inside the band, is **absent** from every
   search/sort/price result — PUBLISHED-only holds regardless of the parameters.
9. **Whitelisted fields unchanged.** A search result row carries exactly the shipped
   `PublicListingResponse` fields; no `id`, `product_id`, `status`, `published_at`/`created_at`, or
   object key appears in the body (assert by scanning the JSON).
10. **Cache header unchanged.** A successful search response still sets
    `Cache-Control: public, max-age=60`.

---

## Cache

Reads stay `Cache-Control: public, max-age=60`, unchanged from `public_storefront_read_api.md` — still
safely below the presigned image-URL TTL (900s) so a cached search page never carries a dead image
URL. Search result pages remain **cacheable**: the CDN/browser cache key is the full URL including the
query string, so each distinct `(q, min_price, max_price, sort, category, page, size)` combination is
its own 60s-cacheable entry. A search is a GET with no side effects and no per-caller variance (no
auth, no cookies), so it caches exactly like the category-filtered list does today.

---

## Tests

`api/src/test/java/.../catalog/StorefrontSearchIT.java` (TestContainers; extends the `StorefrontIT`
seed):
- `q` matches a term in `title`-only and in `marketing_copy`-only (both returned); case-insensitive
  (`?q=GOPRO` matches "GoPro");
- blank/empty `q` is ignored (same set as no `q`);
- inclusive price band (boundary values 100/150/200 in and out of `min`/`max`);
- each sort order: `price_asc`, `price_desc`, `newest`/default — including a stable tie-break across
  two pages;
- combined `category + q + price + sort + page/size` ANDs correctly, with `total` matching the page;
- unknown `sort` → 400; `min_price=abc` → 400; `max_price=-1` → 400; `min_price>max_price` → 400;
- a DRAFT (and an ARCHIVED) listing that matches the term **and** the band never appears in any
  filtered/sorted result (no-leak of non-PUBLISHED);
- JSON no-leak scan (no `product_id`/`status`/`published_at`/internal `id` in a search row);
- successful search sets `Cache-Control: public, max-age=60`.

Regression: `StorefrontIT` (the un-parameterized list, single-listing detail, category nav, inactive-
org 404) stays green — `findByFilters` with all-null new predicates must reproduce the shipped
`findByCategoryAndStatus` behavior exactly (same rows, same order, same `total`).

Verified live through Tomcat: anonymous `GET …/listings?q=gopro&min_price=100&sort=price_asc` → 200,
price-ascending, PUBLISHED-only, `Cache-Control: public, max-age=60`, no `product_id` in the body;
`?sort=nope` → 400; `?min_price=-5` → 400; after `unpublish`, a formerly-matching row drops out of
every search result.

---

## What this unblocks

| Next | Depends on this |
|---|---|
| **Frontend story 26 (storefront catalog / search)** | the search box, price-range slider, and sort dropdown all drive this one endpoint |
| **Faceted search / relevance** (later) | full-text/trigram + facet counts branch off this same filtered read |
