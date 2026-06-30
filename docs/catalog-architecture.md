# Catalog Architecture: Product vs ProductListing

> Design doc for the Catalog bounded context. Read before touching `category`,
> `product_listing`, or storefront read paths. Grounded in `sys-analysis/system/glossary.md`
> and the existing layered-module conventions (`domain → repository → service → api`).

## Why this exists

The storefront frontend (`docs/frontend-architecture.md`) needs a Catalog backend. Until now
only the **internal `Product`** aggregate existed. The glossary names two more Catalog entities
that had no schema or code — **`Category`** and **`ProductListing`** — and this slice adds them.

## The core principle: two standalone slices (no "fat product")

The internal **Product** and the public **ProductListing** are **separate aggregates with
separate tables**, linked only by a foreign key. They are never one row.

| | **Product** (internal / ERP) | **ProductListing** (storefront) |
|---|---|---|
| Stakeholder | shop owner, procurement, inventory auditor | the anonymous shopper |
| Read/write | low read, low write | **massive read**, low write |
| Sensitive data | cost, supplier links, `base_price`, barcode, SKU | none — everything is intentionally public |
| Shape | structured: SKU, barcode, measurements | rich: title, marketing copy, `sales_price`, images |
| Lifecycle | static record | `DRAFT → PUBLISHED → ARCHIVED` |

Benefits of the split:

1. **No margin leaks.** Public read paths touch `product_listing` *only* — there is
   mathematically no way for a query bug to expose `product.base_price`/cost to a shopper.
2. **Draft & preview.** An owner edits internal product data, or drafts a listing, without
   changing what's live on the storefront until they hit **Publish**.
3. **Fast storefront reads.** The hot path scans a slim table with no supplier/financial joins.
4. **Independent prices.** `product_listing.sales_price` is the public price, decoupled from the
   internal `product.base_price`.

> **Layout note.** This repo organizes by *layer* (domain/repository/service/api), not by
> feature folder. The "vertical slice" here is the complete per-aggregate file set across those
> layers, kept independent — Category and ProductListing never share a model, repo, or service.

## Aggregates

### Category
Self-referencing hierarchy ("Notebooks > Spiral"), org-scoped.

- `id, org_id, parent_category_id (nullable, self-FK), name, slug, created_at, updated_at`
- `UNIQUE (org_id, slug)`.
- Same-org parent and acyclicity are **enforced in `CategoryService`** (a self-FK can't express
  "same org" or "no cycles"). Delete is blocked while the category has children.

### ProductListing
The public face of one Product. One listing per product (`UNIQUE (org_id, product_id)`).

- `id, org_id, product_id (FK), title, marketing_copy, slug, sales_price, status,
  published_at, created_at, updated_at`
- `UNIQUE (org_id, slug)`; partial index on `(org_id, status) WHERE status = 'PUBLISHED'`.
- **Lifecycle FSM** (`listing_status` Postgres enum):

```
            publish                     archive
  DRAFT ───────────────▶ PUBLISHED ───────────────▶ ARCHIVED
    ▲                        │                          ▲
    └────────────────────────┘   (unpublish)            │
              archive ──────────────────────────────────┘
```

  - `publish`   : `DRAFT → PUBLISHED`, sets `published_at`.
  - `unpublish` : `PUBLISHED → DRAFT`, clears `published_at`.
  - `archive`   : `DRAFT|PUBLISHED → ARCHIVED`.
  - Only `PUBLISHED` is storefront-visible. Illegal transitions → `409 Conflict`.

### Listing ⇄ Category (many-to-many)
`product_listing_category (listing_id, category_id)` — a listing can appear under several
categories. `PUT /{id}/categories` replaces the whole set.

### Images (presigned object storage)
`product_listing_image (id, org_id, listing_id, object_key, alt_text, sort_order, created_at)`.
Binaries never flow through the API:

1. `POST /{id}/images/presign {filename, contentType}` → backend returns a **presigned PUT URL**
   + the `objectKey` (`{orgId}/listings/{listingId}/{uuid}-{filename}`). No DB row yet.
2. Client `PUT`s the bytes straight to object storage (Content-Type must match the signed value).
3. `POST /{id}/images {objectKey, altText, sortOrder}` → backend verifies the key's
   `{orgId}/listings/{listingId}/` prefix (blocks cross-tenant attach) and inserts the row.
4. Reads return short-lived **presigned GET URLs**; raw object keys are never exposed.

Local dev uses **MinIO** (S3-compatible) via docker-compose; prod points at S3. The presigner
(`common/.../storage/ObjectStorage`) is built in `AppConfig` like `DataSourceFactory`.
Connection config is in `common/.../storage.properties` (committed dev defaults); credentials
come from `S3_ACCESS_KEY` / `S3_SECRET_KEY` env vars.

## API surface (authenticated, org-scoped)

All under `/api/orgs/{orgId}`. VIEWER read · STAFF write/lifecycle · MANAGER delete · ADMIN
bypass. This is the **admin / back-office** surface. The public storefront read surface is
separate (see below).

| Method | Path | Role |
|---|---|---|
| GET/POST | `/categories` | VIEWER / STAFF |
| GET/PUT/DELETE | `/categories/{id}` | VIEWER / STAFF / MANAGER |
| GET/POST | `/product-listings` (`?status=` filter on GET) | VIEWER / STAFF |
| GET/PUT/DELETE | `/product-listings/{id}` | VIEWER / STAFF / MANAGER |
| POST | `/product-listings/{id}/publish`·`/unpublish`·`/archive` | STAFF |
| GET/PUT | `/product-listings/{id}/categories` | VIEWER / STAFF |
| GET | `/product-listings/{id}/images` | VIEWER |
| POST | `/product-listings/{id}/images/presign` | STAFF |
| POST | `/product-listings/{id}/images` | STAFF |
| DELETE | `/product-listings/{id}/images/{imageId}` | STAFF |

## Public storefront read API (anonymous)

A separate, **anonymous** read surface at `/api/public/*` — the CQRS query side, not an
aggregate. It is mounted outside the JWT filter (`JwtAuthFilter` bypasses the `/api/public/`
prefix) and enforces the safety guarantees **at the source of truth**, not in a proxy:

- resolves the org by **public slug** (404 if missing or inactive);
- serves **only PUBLISHED** listings — the status is hard-coded in `StorefrontService`, never a
  caller-supplied parameter, so a draft can never be returned even by its exact slug;
- returns a **whitelisted** shape (slug, title, marketing copy, `sales_price`, images,
  category breadcrumbs) — never the internal id, `product_id`, status, timestamps or object
  keys. The read path only ever touches `product_listing`, so there is no row from which cost or
  margin *could* leak;
- sets `Cache-Control: public, max-age=60` (safely below the presigned image-URL TTL), so it is
  CDN-cacheable.

| Method | Path |
|---|---|
| GET | `/api/public/{orgSlug}/listings` (paged; `?category=<slug>`, `?page`, `?size`) |
| GET | `/api/public/{orgSlug}/listings/{listingSlug}` (one published listing + breadcrumbs) |
| GET | `/api/public/{orgSlug}/categories` (nav: name, slug, parent slug) |

Checkout intentionally does **not** live here: placing an order needs `product_id`, which is
internal — that goes through the authenticated SalesOrder path via a service role, so the public
surface never exposes the listing→product mapping.

## Out of scope (later slices)
- Public rate-limiting on `/api/public/*` (the `RateLimitFilter` currently guards only `/api/auth/*`).
- Stable public-bucket/CDN image URLs (this slice serves short-lived presigned GET URLs; a
  public-read bucket would allow longer cache TTLs).
- Storefront search and price-range filters.
- Exposing `product.barcode` on the Product API (column exists since V16, still unwired).
