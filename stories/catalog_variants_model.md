# Slice VG1: Variant model + admin variant management

> Variants epic, slice 1 of 2 — canonical decisions:
> [`docs/catalog-variants-architecture.md`](../docs/catalog-variants-architecture.md). This slice
> lands the **schema and the admin write/read surface** with the child-product lifecycle behind
> it; nothing public changes yet (VG2 threads commerce). Feeds frontend story 65.

---

## Goal

STAFF defines a listing's option axes and variants — each variant with its own price, SKU, and
optional barcode — via one atomic set-replace. Behind the API, each variant is a **child `product`
row** the merchant then stocks through the existing inventory screens with zero new inventory
code. Public reads are untouched by this slice.

## Migration — **V70** (verify V69 is still the highwater before numbering)

Exactly the §2 schema of the architecture doc: `product_variant` (bridge; `UNIQUE(product_listing_id,
variant_key)`, `UNIQUE(product_id)`, `active` flag, `sales_price CHECK >= 0`), `attribute` /
`attribute_value` + their `_translation` tables (ar/en, the V63 pattern), `variant_attribute_value`
(+ the `(attribute_value_id, variant_id)` index for facets). jOOQ codegen after.

## API (mounts on `ProductListingHandler` — the `PUT /{id}/categories` set-replace precedent)

- `GET /api/orgs/{orgId}/product-listings/{id}/variants` (VIEWER) — the full admin view:
  `{attributes:[{slug, name_ar, name_en, values:[…]}], variants:[{key, options, sales_price, sku,
  barcode, active, product_id}]}` (admin plane — internal ids are fine here).
- `PUT /api/orgs/{orgId}/product-listings/{id}/variants` (STAFF) — atomic set-replace:
  - **attributes**: upsert org-level `attribute`/`attribute_value` rows by slug (org-scoped —
    Size/Brand are shared across listings; values are additive here, never deleted).
  - **variants**: match existing rows by `variant_key` (server-generates the key from option
    slugs, e.g. `red-m`, when absent). New → mint a child `product` (name = composed
    `"{listing default-locale title} — {label}"`, `sku` from the payload — required, org-unique
    409 on conflict; `barcode` optional — V16 partial-unique 409 on conflict; `base_price` =
    `sales_price`). Existing → update price/options/sort/active. Absent from the payload →
    `active = false` (**never** delete — the child may be referenced by order lines/inventory;
    history stands).
  - **Validation (cause-naming 400s)**: ≤ 3 attributes, ≤ 100 variants, ≤ 40 values/attribute;
    every variant carries exactly one value per declared attribute; duplicate option combinations
    → 400; `sales_price >= 0`; the whole PUT is one transaction — any failure applies nothing.

## Guards (architecture §5 items 11–12)

- **Listing-create**: a product that backs a variant (`EXISTS product_variant.product_id`) cannot
  get its own listing — extend the existing 409 in `ProductListingService.create` (~:266).
- **Product delete**: deleting a parent while it has **active** variants → 409 naming the remedy
  ("deactivate its variants first"); child products keep today's referenced-delete 409 behavior.

## Explicitly NOT in this slice

Any public/storefront change (list rows, detail, checkout, availability — all VG2). Any inventory
code (child products flow through the existing product-driven overview/actions/ledger untouched —
that's the point of the architecture). Per-variant images. Facets.

## Tests (`CatalogVariantsIT` — model: `ProductListingIT` + `CategoryCrudIT` harnesses; auth matrix like `ProductListingHandlerAuthTest`)

1. **Set-replace roundtrip**: PUT axes (Size: S/M) + 2 variants → GET echoes; child `product` rows
   exist with the composed name, payload SKU, barcode; re-PUT with one variant absent → it flips
   `active:false`, child product row survives.
2. **Idempotent re-PUT**: identical payload → no duplicate attributes/values/variants/products.
3. **Child products are ordinary stock rows**: initialise + restock a child via the existing
   `POST /inventory/{productId}/…` actions → stock overview lists it; `GET /products?barcode=`
   resolves the child (scan seam free-of-charge).
4. **Validation**: >3 attributes / >100 variants / duplicate combination / missing option /
   negative price → 400 each, transaction untouched; duplicate SKU and duplicate barcode → 409.
5. **Guards**: creating a listing for a child product → 409; deleting a parent with an active
   variant → 409; after deactivating all variants the parent delete falls back to today's rules.
6. **Org isolation**: attributes/values/variants invisible cross-org; auth matrix (VIEWER read,
   STAFF write) enforced.
7. **Public untouched**: `GET /api/public/{slug}/listings/{slug}` response is byte-identical for a
   listing with variants defined (this slice leaks nothing — VG2 exposes them).

## Definition of done

V70 + codegen; the two routes + service + child-product lifecycle + both guards; all seven IT
groups green; existing catalog + inventory + barcode ITs untouched and green; spotless clean.
