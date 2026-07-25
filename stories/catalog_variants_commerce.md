# Slice VG2: Variants through commerce — public surface, checkout, and the twelve 1:1 seams

> Variants epic, slice 2 of 2 — canonical decisions:
> [`docs/catalog-variants-architecture.md`](../docs/catalog-variants-architecture.md) (§3–§5).
> VG1 landed the model + admin surface; this slice makes variants **sellable**: the whitelisted
> public block, the variant-aware checkout/availability/shortage wire, and the fix for every
> listing↔product 1:1 assumption the investigation catalogued (architecture §5). Requires VG1.
> Feeds frontend story 66. No migration.

---

## Goal

A shopper opens a listing with variants, sees the options with per-variant price + stock, and
checks out a specific variant; every downstream surface (shortage 409s, order lines, portal order
detail, review eligibility, reorder, best-sellers) keeps working with variant identity intact —
and `product_id` still never crosses the public boundary.

## Public surface (whitelist discipline — extend the pinned whitelist test)

- **Listing detail** gains `variants: [{key, label, options:{attrSlug: valueLabel}, price,
  in_stock}]` (active only, `sort_order`; labels locale-resolved via the translation tables) and
  `has_variants`. Detail `sales_price` stays (the no-variant/fallback price).
- **List rows** gain `has_variants`; `sales_price` becomes `MIN(active variant price)` when
  variants exist (the honest "from" price — computed in the same read, no N+1).
- **Availability batch** (`?slugs=`): items are `slug` (listing-level = any-active-variant
  in-stock, today's behavior preserved) or `slug::variantKey` (variant-level); response rows keyed
  by the requested token, same shape, same `max-age=15`, same >100 → 400.

## Checkout wire (all four line sites, architecture §5 #2/#9)

- `PublicCheckoutRequest` / `PortalCheckoutRequest` lines gain optional `variant` (the key);
  `StorefrontService.CheckoutLine` + `CustomerPortalService.CheckoutLine` carry it through.
- `resolveForCheckout(orgId, [(slug, variantKey?)], …)`: a variant line resolves to the **child**
  `product_id` + the **variant's** `sales_price` + composed title
  (`"{title} — {variant label}"` — snapshots into the order line description, the
  `OrderLineTitleSnapshotIT` precedent). Rules, cause-naming:
  - listing has variants + line has no `variant` → **400** `"variant is required for {slug}"`;
  - listing has no variants + line has a `variant` → 400;
  - unknown/inactive key → opaque **404** (the unknown-slug convention);
  - same listing, two different variants = two independent lines (distinct child product ids —
    reservation demand aggregation already handles it).
- **Shortage 409** rows gain `variant` (key) + the composed `title`, both re-keyed from the child
  product id maps built at resolution (`StorefrontService:434`, `CustomerPortalService:542`).
  Idempotency, TTL, notifications, magic links: untouched (they never see products).

## The remaining 1:1 seams (architecture §5 — each is an explicit AC)

| Seam | Fix |
|---|---|
| `enrich`/`getListing`/`resolveAvailability` in_stock | any-**active**-variant EXISTS (parent-only listings keep today's single-product check) |
| Portal order detail `listingSlugByProduct` (#5) | UNION child→listing through the bridge, so delivered variant lines keep their `listing_slug` + "rate this item" |
| Review eligibility (#6) | `hasDeliveredProduct` gates on parent ∪ children — buying any variant qualifies you to review the listing |
| Reorder (#7) | child → (listing, variant key); re-adds the same variant; inactive/vanished variant → the `unavailable` list |
| Best-sellers + `sold=true` (#8) | `soldUnits` aggregates children into the parent's listing through the bridge |
| Primary-image lookup (#10) | same child→listing mapping when fed order-line product ids |

## Explicitly NOT in this slice

Facets (own slice). Frontend anything. Per-variant images. Any change to wishlist/review storage
(listing-keyed, correct as-is). Any inventory/reservation/fulfillment code (child products
already flow through — VG1 proved it).

## Tests (`VariantCommerceIT` — model: `PublicCheckoutIT` + `PortalCheckoutIT`; plus targeted extensions to existing suites)

1. **Detail + list**: variants block (active only, locale labels, per-variant in_stock), min
   "from" price + `has_variants` on rows; DRAFT listing's variants unreachable; whitelist test
   extended (no product_id/sku/barcode in the block).
2. **Availability**: `slug` = any-variant semantics; `slug::key` = that variant; unknown key →
   `in_stock:false` (opaque); mixed batch keeps request order.
3. **Checkout happy path** (anonymous + portal): a variant line reserves the **child** product's
   stock, snapshots the variant price + composed description; two variants of one listing = two
   lines; order → pay → deliver → invoice all green (proves the untouched core).
4. **Checkout rules**: missing-variant 400, spurious-variant 400, unknown/inactive key 404;
   variant shortage 409 carries `{listing_slug, variant, title, requested, available}`.
5. **Downstream seams**: portal order detail carries `listing_slug` for a variant line; review
   submit eligible after a variant delivery; reorder re-adds the same variant and reports a
   deactivated one unavailable; best-sellers ranks the parent listing by child sales and
   `sold=true` includes it.
6. **Regression**: a variant-less org's checkout/availability/search responses byte-identical to
   today (run the existing `PublicCheckoutIT`, `StorefrontSearchIT`, `BestSellersSortIT`,
   `StorefrontAvailabilityIT`, `PortalReorderIT`, `PortalReviewsIT` unchanged).

## Definition of done

All six IT groups green + the named existing suites untouched and green; whitelist test pinned;
spotless clean; full `-Dtest='*IT'` sweep green. No migration, no codegen.
