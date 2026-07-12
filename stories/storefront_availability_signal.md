# Slice: Storefront availability signal (in-stock, no quantities)

> A PUBLISHED listing can be **out of stock** — there is no inventory FK on `product_listing`, and the
> public read model ([`public_storefront_read_api.md`](public_storefront_read_api.md)) never touches the
> `inventory` table. So today a shopper only discovers a sold-out item at checkout, when the reservation
> engine 409s ([`public_checkout.md`](public_checkout.md) §No-leak). This slice adds a **best-effort UX
> signal** — a boolean `in_stock` on every public listing and a batch re-validation endpoint for the
> cart — so out-of-stock is visible on the card, the detail page, and the cart, *before* checkout.
>
> Canonical contract & decisions: [`frontst/docs/storefront-commerce-epic.md`](../../frontst/docs/storefront-commerce-epic.md) (§B2).
> Sibling of the read API above (same anonymous, cacheable, whitelisted read-model pattern). Feeds
> frontend story 27 (listing detail) and story 28 (cart re-check).

---

## Goal

Tell an anonymous shopper whether an item is buyable — **as a boolean, never a quantity** — on two
surfaces:

1. `PublicListingResponse` (both the list and the detail read) gains **`in_stock: boolean`**
   (`available_qty > 0`). The listing list already batch-loads images + categories per page (two
   queries, no N+1); this slice adds **one more batch query** — inventory by the page's `product_id`s —
   in the same shape.
2. `GET /api/public/{orgSlug}/availability?slugs=a,b,c` — a batch endpoint that maps a cart's listing
   slugs to `[{slug, in_stock}]`, so a client can re-check the whole cart in one round-trip right before
   checkout.

Done means: a card for a sold-out PUBLISHED listing renders "out of stock" from the list read alone
(no per-card fetch), the detail page shows the same, the cart greys out lines that went out of stock
since add-to-cart, and **no response on any of these surfaces ever carries a stock number, a
threshold, or a `product_id`**. The authoritative check remains the reservation 409 at checkout — this
signal is advisory and, by cache design, may be seconds stale.

---

## Why a boolean, and why best-effort

- **A number is inventory intelligence; a boolean is a shopping cue.** Exposing `available_qty` on the
  anonymous surface would leak stock levels, restock cadence, and sell-through to competitors and
  scrapers. Souq-style stores show "In stock" / "Out of stock" (and at most a vague "Only a few left",
  which is **out of scope** here — see Out). The public contract exposes strictly `in_stock:
  available_qty > 0`; the quantity never crosses the boundary.
- **Authoritative availability is a checkout-time property, not a read-time one.** The single source of
  truth for "can this order be placed" is the `FOR UPDATE` reservation inside placement
  ([`reserve_stock_on_placement.md`](reserve_stock_on_placement.md), [`public_checkout.md`](public_checkout.md) §Concurrency)
  — it locks the row, sums the request, and 409s on a shortage. This signal is a **UX pre-filter** that
  spares the shopper an obvious dead end; it deliberately does not, and cannot, replace that check.
  Between the read and the POST the stock can change, and that is fine: the 409 is the backstop.
- **Untracked = not buyable.** There is no inventory FK on `product_listing`; a listing whose
  `product_id` has **no `inventory` row** is untracked. Consistent with placement — which treats a
  missing row as available 0 and rejects
  ([`reserve_stock_on_placement.md`](reserve_stock_on_placement.md) §Scope/Out) — this slice maps
  untracked → `available_qty 0` → `in_stock: false`. A LEFT JOIN (untracked → 0) makes this the natural
  result, not a special case.

---

## Design

- **Availability derivation.** For a `product_id`, `available_qty = stock_qty - reserved_qty`
  (`Inventory.getAvailableQty()`, `Inventory.java:79`), and `in_stock = available_qty > 0`. A negative
  or absent value clamps to not-in-stock. **No quantity is ever serialized** — the boolean is computed
  server-side and only the boolean leaves the service.
- **Batch load for the listing reads.** `product_listing.product_id` is `UUID NOT NULL` (migration
  V40), so every listing row on a page yields a `product_id`. `StorefrontService` collects the page's
  `product_id`s and issues **one** batch inventory read keyed by `(org_id, product_ids)` (or a LEFT
  JOIN so untracked → 0), exactly mirroring the existing per-page image + category batching (two
  queries, no N+1 — see `CLAUDE.md` product-listings note). The detail read resolves a single
  `product_id` the same way. This adds **one** query per page/detail, not one per row.
- **The `availability` endpoint.** `PublicStorefrontServlet` (GET-only, `/api/public/*`,
  JWT-bypassed — `JwtAuthFilter.java:65-74`) gains the `/{orgSlug}/availability` route. It parses the
  `slugs` CSV, resolves the org by slug (active-only, same boundary as the read model), and calls a
  **slug→available resolver** that returns availability for the **PUBLISHED** listings among the
  requested slugs. Any requested slug that is unknown or not PUBLISHED is **absent** from the resolver
  result and emitted as `{slug, in_stock: false}` — a filter, never a 404, and never an oracle that
  distinguishes "no such slug" from "exists but DRAFT/ARCHIVED" (same opacity rule as the read API and
  [`public_checkout.md`](public_checkout.md) §No-leak).
- **Caching — two TTLs, chosen for volatility.**
  - The listing list/detail keeps its existing `Cache-Control: public, max-age=60`
    ([`public_storefront_read_api.md`](public_storefront_read_api.md)). `in_stock` therefore rides that
    60s TTL and is **up to ~60s stale by design** — acceptable because the authoritative check is the
    reservation at checkout. Not lowering the catalog TTL keeps the CDN-cacheable browse pages fast; the
    fresh signal lives on the dedicated endpoint below.
  - `GET .../availability` sets `Cache-Control: public, max-age=15` — stock is volatile and this is the
    pre-checkout re-check, so it wants a much shorter window than the catalog page while still absorbing
    a burst of identical cart re-checks.
- **Whitelisted output, unchanged discipline.** `in_stock` is the only new field; no `product_id`,
  `stock_qty`, `reserved_qty`, `available_qty`, threshold, `org_id`, or timestamp appears anywhere.

---

## API contract

### Listing reads (existing endpoints — one new field)
```
GET /api/public/{orgSlug}/listings[?category=&page=&size=]   → paged PublicListingResponse   (Cache-Control: public, max-age=60)
GET /api/public/{orgSlug}/listings/{listingSlug}             → detail PublicListingResponse   (Cache-Control: public, max-age=60)
```
`PublicListingResponse` now: `{slug, title, marketing_copy, sales_price, in_stock,
images:[{url, alt_text, sort_order}], categories?:[{name, slug}]}`. `in_stock` is present on **both**
the list rows and the detail; `categories` stays detail-only. **No quantity** on either.

### Availability batch (new endpoint)
```
GET /api/public/{orgSlug}/availability?slugs=a,b,c           → 200 [{ "slug": "a", "in_stock": true }, … ]
                                                               (Cache-Control: public, max-age=15)
```
```json
[
  { "slug": "gopro-hero3",        "in_stock": true  },
  { "slug": "black-edition-case", "in_stock": false },
  { "slug": "was-archived",       "in_stock": false }
]
```
- Order of the response follows the request `slugs` order (a client zips it back onto its cart lines).
- An unknown or non-PUBLISHED slug is `{slug, in_stock: false}` — **not** a 404, and indistinguishable
  from a PUBLISHED-but-sold-out slug (no internal-state oracle).
- Duplicate slugs in the query are de-duplicated for the read; the response need not repeat them.

### Errors
| Status | Cause |
|---|---|
| `404` | unknown/inactive `orgSlug` (opaque — same as the read API) |
| `400` | `slugs` missing/empty; more than the cap (**≤ 100** slugs) → "too many slugs"; malformed query |
| `405` | any non-GET method on either route |

No `401`/`403` — `/api/public/*` is anonymous (JWT-bypassed). Note the endpoint is **not** an existence
check: a `false` never confirms a slug exists, so it cannot be used to enumerate the catalog.

---

## The no-leak / no-oracle invariant

Two disclosure rules, both enforced at the service boundary:

1. **No quantities, ever.** The service computes `in_stock` from `stock_qty - reserved_qty` and returns
   only the boolean. There is no DTO field, on any of the three surfaces, that carries a count or a
   low-stock threshold.
2. **A `false` is opaque.** For a listing surface and for the `availability` endpoint alike, "not
   buyable" collapses three internal states — *out of stock*, *untracked (no inventory row)*, and
   *not PUBLISHED / unknown slug* — into the single value `in_stock: false`. Nothing in the response
   distinguishes them. This mirrors the read API's opaque 404 and checkout's opaque
   [`public_checkout.md`](public_checkout.md) §No-leak.

---

## Scope

### In
- `PublicListingResponse` gains `in_stock` on the list **and** detail reads.
- `StorefrontService`: batch-load inventory availability for a listing page's `product_id`s (one extra
  query, mirroring the image/category batching) and for the single detail `product_id`; derive
  `in_stock` per listing.
- `PublicStorefrontServlet`: new `/{orgSlug}/availability` route (GET-only), `slugs` CSV parse + cap,
  `Cache-Control: public, max-age=15`.
- Repository: a **batch inventory read** by `(org_id, product_ids)` returning available per product (or
  a LEFT JOIN from the listing page so untracked → 0); and a **slug→available resolver** for the
  availability endpoint (PUBLISHED-only, reusing the checkout resolver's slug-keyed shape but returning
  availability instead of `product_id`/price).
- Public DTO for the availability rows (`PublicAvailabilityResponse` `{slug, in_stock}`).
- Keep the listing reads' existing `max-age=60`; document the ≤60s staleness of `in_stock` there.

### Out (deferred)
- **Exact quantities on the public surface** — never; `available_qty` is internal.
- **Low-stock thresholds / "Only N left"** — needs a per-listing or per-org threshold and a second
  boolean; a later merchandising slice, not v1.
- **Backorder / "notify me when back in stock"** — needs a customer contact capture + a restock hook; a
  separate feature (no customer auth in this epic — see epic §8, `docs/customer-portal-future.md`).
- **Real-time availability push (WebSocket/SSE)** — the signal is polled/cached; live push is out.
- **Reserving stock from the storefront read** — availability is a *read*; the only reservation is at
  checkout ([`public_checkout.md`](public_checkout.md)). A `true` here is not a hold.
- **Filtering the catalog list to in-stock only** — the list still returns sold-out PUBLISHED listings
  (with `in_stock: false`); an "in-stock only" filter is search/filter work
  ([`storefront_search_and_filters.md`](storefront_search_and_filters.md), B3).

---

## Authorization

None — anonymous by design, `/api/public/*` is JWT-bypassed. The boundaries are the same as the read
model: org-by-slug **active-only**, PUBLISHED-only resolution for the availability endpoint, whitelisted
output (**boolean only**), and the `product_id` never crossing the boundary (request and response are
keyed by slug; inventory is joined server-side).

---

## File layout

| Module | New / changed |
|---|---|
| `domain` | **Changed**: `InventoryRepository` — add a batch availability read `findAvailableByProductIds(orgId, Collection<productId>)` → `Map<productId, availableQty>` (or `List<(productId, available)>`). **Changed**: `ProductListingRepository` — add `resolveAvailability(orgId, slugs, PUBLISHED)` → `List<ListingAvailability(slug, available)>` (a slug→available resolver; a LEFT JOIN to `inventory` so untracked → 0). New records for the two return shapes. |
| `repository` | **Changed**: `InventoryRepositoryImpl` — implement the batch read (`WHERE org_id=? AND product_id IN (…)`). **Changed**: `ProductListingRepositoryImpl` — implement `resolveAvailability` (`… status='PUBLISHED' AND slug IN (…)` LEFT JOIN `inventory`, computing `stock_qty - reserved_qty`). |
| `service` | **Changed**: `StorefrontService` — batch-load availability for the listing page's/detail's `product_id`s, derive `in_stock`; new `availability(orgSlug, List<slug>)` (resolve org active-only → resolve availability → map absent slugs to `false`, preserving request order). |
| `api` | **Changed**: `PublicListingResponse` — `in_stock` field + mapper. **Changed**: `PublicStorefrontServlet` — `/{orgSlug}/availability` route, `slugs` parse + ≤100 cap (400), `max-age=15`. **New**: `PublicAvailabilityResponse` `{slug, in_stock}`. |

No migration — `inventory` (V7/V15) and `product_listing` (V40) already carry everything needed.

---

## Acceptance criteria

- [ ] `GET /api/public/{orgSlug}/listings` on a page of PUBLISHED listings returns each row with
      `in_stock`; a listing whose product has `stock_qty - reserved_qty > 0` → `in_stock: true`; one at
      `available = 0` → `false`. The page issues **one** inventory query for the whole page (no N+1) —
      alongside the existing images + categories batches.
- [ ] `GET .../listings/{listingSlug}` (detail) carries the same `in_stock` for that listing.
- [ ] A PUBLISHED listing whose `product_id` has **no `inventory` row** (untracked) → `in_stock: false`
      on both list and detail (LEFT JOIN → available 0).
- [ ] **No quantity anywhere:** no `stock_qty`, `reserved_qty`, `available_qty`, threshold, or
      `product_id` in any list, detail, or availability response body. (Assert by scanning the JSON.)
- [ ] `GET .../availability?slugs=a,b,c` returns `[{slug, in_stock}]` for each requested slug, in
      request order; a PUBLISHED in-stock slug → `true`, a PUBLISHED sold-out slug → `false`.
- [ ] An unknown slug and a DRAFT/ARCHIVED slug in the `slugs` list each return `{slug, in_stock:
      false}` — **not** a 404, and no field distinguishes them from a sold-out PUBLISHED slug.
- [ ] `slugs` missing/empty → `400`; more than 100 slugs → `400` ("too many slugs"). Non-GET on either
      route → `405`.
- [ ] Unknown or inactive `orgSlug` → `404` (opaque) on both the listing reads and `availability`.
- [ ] The listing reads keep `Cache-Control: public, max-age=60`; `availability` sets `max-age=15`.
- [ ] Advisory-not-authoritative: a listing showing `in_stock: true` that sells out before checkout
      still 409s at `POST .../checkout` — the signal being stale does not corrupt the order path.
      (Covered by `public_checkout.md`'s concurrency test; noted here as the contract.)

---

## Tests

`api/src/test/java/.../catalog/StorefrontAvailabilityIT.java` (TestContainers; presign offline):
- **in-stock true** — PUBLISHED listing, `stock_qty - reserved_qty > 0` → `in_stock: true` on list and
  detail;
- **zero-available false** — a listing reserved down to `available = 0` → `in_stock: false`;
- **untracked false** — PUBLISHED listing whose product has no `inventory` row → `in_stock: false`;
- **batch, no N+1** — a page of N listings issues one inventory query (jOOQ SQL-log / query-count
  assertion), matching the existing image/category batch pattern;
- **availability mix** — `?slugs=` with in-stock, sold-out, DRAFT, and unknown slugs → the two invalid
  ones come back `in_stock: false`, indistinguishable from the sold-out one; order preserved;
- **caps + method** — >100 slugs → 400; empty `slugs` → 400; POST → 405; unknown/inactive org → 404;
- **no-quantity scan** — regex assert that no list/detail/availability body contains `stock_qty`,
  `reserved_qty`, `available`, or `product_id`;
- **cache headers** — listing reads `max-age=60`, `availability` `max-age=15`.

Regression: `StorefrontIT` (the read API) stays green with `in_stock` added to the whitelisted shape;
`ReservationIT` unchanged (availability derivation reads the same `stock_qty - reserved_qty` it locks).

Verified live through Tomcat: anonymous `GET .../listings` returns `in_stock` per row with one extra
inventory query; `GET .../availability?slugs=a,b,unknown` returns the third as `false`; `max-age=15` on
availability and `max-age=60` on the catalog page; POST → 405.

---

## What this unblocks

| Next | Depends on this |
|---|---|
| **Frontend story 27 (listing detail)** | `in_stock` on the detail read — disables "Add to cart" and shows an out-of-stock state without exposing a quantity |
| **Frontend story 28 (cart)** | `GET .../availability` batch re-check — greys out cart lines that went out of stock since add-to-cart, before sending the shopper to checkout |
| **"In-stock only" catalog filter** (later) | the availability join already computed here, folded into `storefront_search_and_filters.md` (B3) |
