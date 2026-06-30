# Slice: Build the product catalog (Category + ProductListing)

> The **Catalog** bounded context per
> [`sys-analysis/system/glossary.md`](../sys-analysis/system/glossary.md#catalog--inventory) and
> [`docs/catalog-architecture.md`](../docs/catalog-architecture.md). Until now only the internal
> `Product` aggregate existed; this slice adds the two Catalog entities the glossary names but had
> no schema or code for — **`Category`** and **`ProductListing`** — so a storefront frontend has a
> catalog to render.

---

## Goal

Give an org a real product catalog behind the authenticated org-scoped API:

- **Category** — a hierarchical, org-scoped taxonomy ("Notebooks > Spiral").
- **ProductListing** — the **public, storefront-facing** version of a `Product` (own
  `sales_price`, marketing copy, images, publish lifecycle), **decoupled from `product`** so
  internal data (cost, supplier, barcode, `base_price`) can never leak to customers.

Done means: an operator can build a category tree, publish a product as a listing, organise
listings under categories, and attach images via presigned object-storage uploads — all under
`/api/orgs/{orgId}/...`, gated by org role.

---

## The mental model: two standalone slices, never a "fat product"

The internal **Product** and the storefront **ProductListing** are **separate aggregates with
separate tables**, linked only by a FK. They are never one row.

| | Product (internal / ERP) | ProductListing (storefront) |
|---|---|---|
| Stakeholder | owner, procurement, auditor | the shopper |
| Read/write | low read, low write | massive read, low write |
| Sensitive data | cost, supplier, `base_price`, barcode | none — intentionally public |
| Lifecycle | static record | `DRAFT → PUBLISHED → ARCHIVED` |

This split is the headline design decision (`docs/catalog-architecture.md`): a public storefront
read path can later touch `product_listing` **only**, so a query bug cannot expose margins, and
the storefront hot path scans a slim table.

> **Layout note.** This repo separates by *layer-module* (`domain → repository → service → api`),
> not by feature folder. Each aggregate is therefore a coordinated file-set across those modules,
> kept independent — Category and ProductListing share no model, repo or service.

---

## Aggregates

### Category (`V39__Create_category_table.sql`)
`id, org_id, parent_category_id (nullable self-FK), name, slug, created_at, updated_at`;
`UNIQUE (org_id, slug)`. A self-FK can't express "parent in same org" or "no cycles", so
`CategoryService` enforces: parent exists in the org, not self-parent, no cycle (walks the parent
chain), and delete is blocked while children exist.

### ProductListing (`V40__Create_product_listing.sql`)
`id, org_id, product_id, title, marketing_copy, slug, sales_price, status, published_at,
created_at, updated_at`; `UNIQUE (org_id, product_id)` (one listing per product),
`UNIQUE (org_id, slug)`, partial index on `(org_id, status) WHERE status = 'PUBLISHED'`.

`status` is the Postgres enum `listing_status` (mirrors `fulfillment_status` et al.; jOOQ
round-trips it via `generated.enums.ListingStatus.valueOf(name())`). Lifecycle:

```
            publish                     archive
  DRAFT ───────────────▶ PUBLISHED ───────────────▶ ARCHIVED
    ▲                        │                          ▲
    └──────── unpublish ─────┘   archive ───────────────┘
```

- `publish`   : DRAFT → PUBLISHED, stamps `published_at`.
- `unpublish` : PUBLISHED → DRAFT, clears `published_at`.
- `archive`   : DRAFT|PUBLISHED → ARCHIVED.
- Only PUBLISHED is storefront-visible. Illegal transitions → **409**.

### Listing ⇄ Category (many-to-many)
`product_listing_category (listing_id, category_id)` — a listing can appear under several
categories. `PUT /{id}/categories` replaces the whole set (validated against the org's categories).

### Images via presigned object storage
`product_listing_image (id, org_id, listing_id, object_key, alt_text, sort_order, created_at)`.
Bytes never flow through the API:

1. `POST /{id}/images/presign {filename, contentType}` → backend returns a **presigned PUT URL**
   + the `object_key` (`{orgId}/listings/{listingId}/{uuid}-{filename}`). No DB row yet.
2. Client `PUT`s the bytes straight to object storage (Content-Type must match the signed value).
3. `POST /{id}/images {object_key, alt_text, sort_order}` → backend verifies the key's
   `{orgId}/listings/{listingId}/` prefix (blocks cross-tenant attach) and inserts the row.
4. Reads return short-lived **presigned GET URLs**; raw object keys are never exposed.

Local dev uses **MinIO** (S3-compatible) via docker-compose; prod points at S3. The presigner
(`common/.../storage/ObjectStorage`) is built in `AppConfig` like `DataSourceFactory`; presigning
is offline (an HMAC), so the backend never calls the object store for it.

---

## Scope

### In
- Two `V39`/`V40` migrations (category; `listing_status` enum + `product_listing` +
  `product_listing_category` + `product_listing_image`).
- Domain models + repository interfaces/factories; jOOQ repository impls; `CategoryService` and
  `ProductListingService` (validation, transactions, lifecycle guards, category set, image
  presign/attach/list/remove with the key-prefix guard).
- `common`: `ObjectStorage` + `ObjectStorageFactory` + `storage.properties`; AWS SDK v2 (`s3`,
  presigner only) via the BOM in the parent POM; MinIO + a `minio-init` bucket sidecar in
  docker-compose.
- API handlers + DTOs; `AppConfig` + `OrgServlet` wiring.
- Served through the **existing authenticated org-scoped API** (`/api/orgs/{orgId}/...`).

### Out (deferred)
- **Anonymous public storefront read endpoint** (`/api/public/{orgSlug}/...`) — this slice is
  authenticated-only, matching documented v1 (storefront via an org-scoped service-role proxy).
  The two-table split makes that future slice safe and trivial.
- Production file hosting/CDN in front of object storage (this slice presigns; hosting is the
  deployment's S3).
- Search / price-range / availability filters and category-tree browse projections.
- Exposing `product.barcode` on the Product API (column exists since V16, still unwired).

---

## API contract

All under `/api/orgs/{orgId}`. VIEWER read · STAFF write/lifecycle · MANAGER delete · ADMIN bypass.

### Categories
| Method | Path | Role |
|---|---|---|
| GET/POST | `/categories` | VIEWER / STAFF |
| GET/PUT/DELETE | `/categories/{id}` | VIEWER / STAFF / MANAGER |

`POST` body `{name, slug, parent_category_id?}`.

### Product listings
| Method | Path | Role |
|---|---|---|
| GET (`?status=`) / POST | `/product-listings` | VIEWER / STAFF |
| GET/PUT/DELETE | `/product-listings/{id}` | VIEWER / STAFF / MANAGER |
| POST | `/product-listings/{id}/publish`·`/unpublish`·`/archive` | STAFF |
| GET/PUT | `/product-listings/{id}/categories` (`{category_ids:[]}` replace) | VIEWER / STAFF |
| GET | `/product-listings/{id}/images` (presigned GET URLs) | VIEWER |
| POST | `/product-listings/{id}/images/presign` (presigned PUT URL) | STAFF |
| POST | `/product-listings/{id}/images` (`{object_key, alt_text?, sort_order?}`) | STAFF |
| DELETE | `/product-listings/{id}/images/{imageId}` | STAFF |

`POST /product-listings` body `{product_id, title, marketing_copy?, slug, sales_price}` →
**201** DRAFT. The detail `GET` adds `category_ids[]` and `images[]` (with presigned `url`s); the
list `GET` returns the core fields only.

### Errors
| Status | Cause |
|---|---|
| `400` | blank title/slug/name, missing/negative `sales_price`, missing `product_id`/`filename`, unknown category id in the set, object_key not owned by the listing, bad `status` filter |
| `403` | caller lacks the required role in `:orgId` |
| `404` | category / listing / image not found in the org |
| `409` | duplicate slug, product already has a listing, illegal lifecycle transition, deleting a category with children |

---

## Object storage / environment

- `common/src/main/resources/storage.properties` — committed dev defaults (endpoint, bucket,
  region, path-style, presign TTL). Points at the docker-compose MinIO.
- Credentials from env `S3_ACCESS_KEY` / `S3_SECRET_KEY` (dev fallback `minioadmin`).
- `docker-compose.yaml` adds `minio` + a `minio-init` (`minio/mc`) sidecar that creates the
  `catalog-images` bucket. **Host ports remapped to 9100 (API) / 9101 (console)** — 9000 is taken
  on this machine — with `storage.endpoint` pointed at `http://localhost:9100`.

---

## Authorization
`AuthzHelper.requireOrgAccess(orgId, role)` on every route — VIEWER read, STAFF write + lifecycle
+ images, MANAGER delete; system ADMIN bypasses (same rule as the rest of the org-scoped API).

---

## Tests

`api/src/test/java/.../catalog/`:

- `CategoryHandlerAuthTest`, `ProductListingHandlerAuthTest` (Mockito) — the full role matrix incl.
  lifecycle + category + image sub-routes (lower roles get 403, service never called; 401 when
  unauthenticated; correct verbs).
- `CategoryCrudIT` (TestContainers) — create/read, slug conflict, hierarchy parent link,
  parent-not-in-org, cyclic/self parent, delete-with-children blocked, cross-org isolation.
- `ProductListingIT` (TestContainers) — full `DRAFT→PUBLISHED→DRAFT→ARCHIVED` lifecycle and
  `published_at` stamping/clearing; illegal transitions → 409; `?status=` filter counts;
  one-listing-per-product + slug uniqueness; create for missing product → 400; category set
  replace + unknown-id rejection; delete cascades categories + images; presign→attach→list→remove;
  foreign-key-prefix attach rejected; images ordered by `sort_order`. (Presigning is offline, so
  the IT needs no MinIO.)
