# Catalog architecture — Product variants (extends `catalog-architecture.md`)

> Tier 2, roadmap item 6 (`docs/storefront-growth-roadmap.md`). The single biggest gap for a
> *believable* catalog: one product sold in selectable options — a shirt in 4 sizes, a phone in 3
> storages — each option with its own price, SKU, barcode, and stock. The roadmap's instruction:
> an epic with its own architecture doc, not a thin slice. This is that doc — the canonical
> decisions the slices ([`catalog_variants_model.md`](../stories/catalog_variants_model.md),
> [`catalog_variants_commerce.md`](../stories/catalog_variants_commerce.md), frontend stories
> 65/66) and the facets slice ([`storefront_attribute_facets.md`](../stories/storefront_attribute_facets.md),
> story 67) implement. Grounded in a full-code investigation 2026-07-25 (all file:line refs
> verified on master @ V69).

---

## 1. The decision: variant-as-child-product, bridged — not a threaded `variant_id`

**A variant IS a `product` row** (a "child product"), attached to its listing through a new
`product_variant` bridge table. The listing keeps pointing at its **parent** product; children are
ordinary org products with their own SKU, barcode, `base_price`, and inventory row.

**Why this and not a `variant_id` threaded through the stock/money tables:** the platform's entire
transactional core keys on bare `product_id` and nothing else —

- the reservation engine (`ReservationService.reserveForOrder` builds its demand map, lock
  ordering, and shortages per `line.getProductId()`; `inventory_reservation` FKs `product`);
- `inventory` (`PK (org_id, product_id)`, `CHECK stock_qty >= reserved_qty`, optimistic
  `version`), the movement ledger, restock idempotency (V50), the product-driven stock overview
  (`FROM product LEFT JOIN inventory`);
- `sales_order_line` / `sales_invoice_line` (frozen `description` + `unit_price` snapshots +
  `product_id`), fulfillment lines, TTL expiry, cancellation release, failed-fulfillment
  return/replace, the in-store sale, barcode resolve (`GET /products?barcode=` on the V16 partial
  unique), and `topProducts` reporting.

With child products, **every one of those flows runs byte-for-byte untouched** — per-variant SKU,
barcode, stock, restock, scan-to-stock, scan-to-sell all fall out of existing `product` semantics.
The alternative requires rebuilding the `inventory` composite PK around a nullable-or-sentinel
`variant_id` (nullable columns can't sit in a PK), touching ~35–45 files across the reservation/
inventory/order/fulfillment stack plus every IT that seeds stock — and *still* needs all the
catalog-layer work below. Strictly dominated. Cost of the chosen model: ~14–18 files + one
migration, concentrated in the catalog read/resolve layer.

**Invariants preserved:**
- `product_listing.product_id` stays NOT NULL → the **parent** product; `UNIQUE(org_id,
  product_id)` untouched. A no-variant listing behaves exactly as today (the parent is the
  sellable identity) — variants are pure opt-in.
- A child product can never get its own listing: the create guard extends the existing "This
  product already has a listing" 409 with an `EXISTS(product_variant WHERE product_id = ?)` check.

## 2. Schema (migration **V70** — one migration, no ALTER on any money/stock table)

```
product_variant (
    id                  UUID PK,
    org_id              UUID NOT NULL REFERENCES org,
    product_listing_id  UUID NOT NULL REFERENCES product_listing ON DELETE CASCADE,
    product_id          UUID NOT NULL REFERENCES product,          -- the child; UNIQUE (a product backs ≤1 variant)
    variant_key         VARCHAR(80) NOT NULL,                      -- the PUBLIC handle (slug discipline)
    sales_price         NUMERIC(12,2) NOT NULL CHECK (>= 0),       -- the variant's public price
    sort_order          INT NOT NULL DEFAULT 0,
    active              BOOLEAN NOT NULL DEFAULT TRUE,             -- set-replace deactivates, never deletes
    created_at / updated_at,
    UNIQUE (product_listing_id, variant_key),
    UNIQUE (product_id)
)

-- The option-axis machinery (org-scoped so Size/Brand are shared across listings — this is what
-- facets aggregate over). Relational + translation tables, the V62/V63 house pattern; NO queried
-- jsonb (none exists in this schema and we are not introducing it).
attribute            (id, org_id, slug, UNIQUE(org_id, slug))
attribute_translation(attribute_id, language CHECK ar|en, name, UNIQUE(attribute_id, language))
attribute_value      (id, attribute_id FK CASCADE, slug, UNIQUE(attribute_id, slug))
attribute_value_translation (attribute_value_id, language, name, UNIQUE(...))
variant_attribute_value (variant_id FK CASCADE, attribute_value_id FK, PK(variant_id, attribute_value_id))
-- + index (attribute_value_id, variant_id) — the facet-count join
```

**Caps** (server-enforced 400s): ≤ 3 attributes per listing's variant set, ≤ 100 variants per
listing, ≤ 40 values per attribute. **Lifecycle**: removing a variant from the set flips
`active = false` (its child product may be referenced by order lines/inventory — history must
stand); the child product remains an ordinary product. Publish/unpublish stays **listing-level**
— the variant set is content of the listing, not separately published.

## 3. Identity & no-leak

The public handle for a variant is its **`variant_key`** (`"red-m"`), per-listing unique — the
slug discipline applied one level down. `product_id` (parent or child) **never crosses the public
boundary**, exactly as today. The whitelisted listing detail gains one block:

```
variants: [{ key, label, options: {size: "M", color: "Red"}, price, in_stock }]   // active only
has_variants: true          // and list rows: price becomes the min variant price ("from" price)
```

No quantity, no SKU, no barcode, no ids cross. The public-field whitelist test extends to pin this.

## 4. Pricing & stock semantics

- **Price**: with variants, `product_variant.sales_price` is authoritative per variant;
  `listing.sales_price` remains the no-variant price and the admin-facing default for new
  variants. Public **list rows** show `MIN(active variant price)` + `has_variants` (the honest
  "from" price); the **detail** shows the selected variant's price. Order lines snapshot the
  variant's price + a composed description (`"{listing title} — {variant label}"` — the
  `OrderLineTitleSnapshotIT` precedent).
- **`in_stock`**: listing-level reads (list rows, wishlist, the `?slugs=` batch) mean **any active
  variant in stock** (EXISTS over children ∪ parent). The detail's `variants[].in_stock` is
  per-variant. The availability batch read accepts items as `slug` (listing-level answer, today's
  behavior) **or** `slug::variantKey` (variant-level answer, same row shape keyed by the requested
  token) — one endpoint, backward compatible, cache shape unchanged (`max-age=15`).

## 5. The twelve 1:1 assumptions and where each is fixed

The investigation found every place assuming listing ↔ one sellable product. VG2 fixes them:

| # | Seam (file:line on master) | Fix |
|---|---|---|
| 1 | `UNIQUE(org_id, product_id)` V40 | Untouched — parent stays the listing's product |
| 2 | `resolveForCheckout` (`ProductListingRepositoryImpl:116`) | Accepts `(slug, variantKey?)`; variant → child product_id + variant price + composed title; no-variant line → parent (today's path). A variant-less request against a has-variants listing → 400 "variant required"; unknown/inactive key → opaque 404 |
| 3–4 | `resolveAvailability` (`:161`), `enrich`/`getListing` in_stock (`StorefrontService:639,743`) | any-active-variant EXISTS; detail adds per-variant |
| 5 | `findSlugsByProductIds` → portal order detail (`CustomerPortalService:175`) | UNION through `product_variant` (child → its listing's slug) — "rate this item" keeps working |
| 6 | Review gate `hasDeliveredProduct(listing.product_id)` (`ListingReviewService:97`) | Gate on parent ∪ children (one EXISTS over the variant bridge) |
| 7 | `resolveForReorder` (`:196`) | Child → listing + variant; re-adds the **same variant** (key recovered from child product_id); inactive variant → reported in `unavailable` |
| 8 | Best-sellers `soldUnits` join (`:399`) | Aggregate children into the parent's listing (join through the bridge; `SUM` over parent ∪ children) — same for `sold=true` |
| 9 | Shortage re-key (`StorefrontService:434`, `CustomerPortalService:542`) | Rows gain `variant` key + label (child ids are already distinct map keys) |
| 10 | `findPrimaryImageObjectKeys` (`:834`) | Same child→listing mapping when fed order-line product ids |
| 11 | Listing-create 409 (`ProductListingService:266`) | + child-product guard (§1) |
| 12 | Product delete 409 (`ProductService:144`) | Parent delete additionally 409s while active variants exist |

Wishlist and review/comment **storage** are listing-id keyed — untouched. Ratings stay
**listing-level** (variants share the listing's reviews).

## 6. Admin surface

Variant CRUD mounts on the existing listing handler as an atomic **set-replace** (the
`PUT /{id}/categories` + `PUT /featured` precedent):

```
GET /api/orgs/{orgId}/product-listings/{id}/variants           (VIEWER — full admin rows)
PUT /api/orgs/{orgId}/product-listings/{id}/variants           (STAFF — the whole set)
   { attributes: [{slug, name_ar, name_en, values:[{slug, name_ar, name_en}]}],
     variants:   [{key?, options:{size:"m"}, sales_price, sku, barcode?, active}] }
```

The service owns child-product lifecycle behind it: a new variant **mints a child `product` row**
(SKU required — the org-unique; barcode optional — the V16 partial unique gives per-variant scan
resolve for free); an existing one updates price/options; a removed one deactivates. Stock is NOT
managed here — child products appear as ordinary rows in the existing inventory screens
(initialise/restock/adjust/ledger all work today). Attributes are org-level and reused across
listings (created inline on first use).

## 7. Facets (#7) ride this substrate

Facets aggregate `variant_attribute_value` joined through `product_variant` to the B3-filtered
listing set — `COUNT(DISTINCT listing_id)` per `(attribute, value)` so a listing with two red
variants counts once. Normalized tables (not jsonb) are what make this a single grouped query.
Filter grammar: `attr_{slug}=v1,v2` (OR within an attribute, AND across attributes), standard
multi-select count semantics (each attribute's counts computed with the *other* selections
applied). Details in the facets slice. **Rating facet is out of v1** — `rating_avg` is a computed
aggregate, not a listing column; materializing it is a separate denormalization decision.

## 8. Slicing (in order; each lands green before the next)

| Slice | Repo | Content |
|---|---|---|
| **VG1** `catalog_variants_model.md` | backend | V70 + admin GET/PUT variants + child-product lifecycle + guards #11/#12 |
| **65** `65_st_admin_variants.md` | frontend | ListingEditor variant rail (axes + variant rows + per-variant SKU/barcode/price) |
| **VG2** `catalog_variants_commerce.md` | backend | Public variants block + checkout/availability/shortage/reorder/review-gate/best-sellers threading (§5) |
| **66** `66_st_storefront_variant_picker.md` | frontend | Picker + cart composite keys (persist migration) + checkout/reorder threading |
| **Facets** `storefront_attribute_facets.md` | backend | `attr_*` grammar + facet counts in the envelope |
| **67** `67_st_catalog_facets.md` | frontend | Facet groups (desktop rail + mobile BottomSheet) |

## 9. Out of scope (named, deliberate)

Per-variant images (v1 uses the listing gallery; a nullable `image_object_key` on
`product_variant` is the future seam). Variant-level wishlist/reviews. Rating facet (v1).
Matrix auto-generation UX (v1 adds variants row-by-row). Cross-listing variant dedup. A rollup of
per-variant report rows into the parent (reports showing child SKUs individually is correct
behavior, optional polish later).
