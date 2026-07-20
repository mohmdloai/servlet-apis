# Slice L2b — Snapshot the resolved listing title onto the order line

> Small companion to [`content_localization_2_listings.md`](content_localization_2_listings.md).
> **Confirmed necessary** (2026-07-20): the storefront/portal checkout persists the **internal
> `product.name`** as the order-line `description`, not the listing title — so after L2 a bilingual
> customer can still get an internal-language item name on their invoice/receipt/PDF. This slice
> snapshots the resolved listing title at checkout instead. Separate slice because it touches the
> checkout/placement contract (a different subsystem than listing storage), not just listing reads.

## What was verified (the L2 flag)

- `SalesOrderService.buildLines` sets the order-line `description` from
  `SalesOrderRepository.ProductSnapshot.description`, which
  `SalesOrderRepositoryImpl.fetchProductSnapshots` maps from **`PRODUCT.NAME`** (the internal product
  name) — not `product_listing.title`.
- The checkout **response** already labels lines with the listing title (`StorefrontService.checkout`
  builds `titleByProduct` from `CheckoutLineResolution.title`), but that title is **not** persisted onto
  the order line, so invoices/receipts/PDFs render the internal name.
- `resolveForCheckout` currently returns the legacy `product_listing.title` (default-locale) — it is
  **not** yet locale-aware (deliberately left for this slice; L2 did not change it).

## Design

1. **Locale-aware checkout resolution.** Add `locale` + `defaultLocale` to
   `ProductListingRepository.resolveForCheckout` so `CheckoutLineResolution.title` is the **resolved**
   listing title (requested locale → default), matching the L2 read resolver. Both anonymous
   (`StorefrontService.checkout`) and portal (`CustomerPortalService`) callers pass their locale.
2. **Carry the title into placement.** Add an optional `description` to
   `SalesOrderService.StorefrontLineInput`; when present, `buildLines`/`assembleLines` uses it as the
   order-line `description` instead of the product-name snapshot (online/in-store lines are unchanged —
   they keep snapshotting `product.name`, which is correct for the merchant-authored internal record).
3. **Locale source at checkout.** Add optional `locale` to the anonymous checkout request
   (`POST /api/public/{orgSlug}/checkout`) and the portal checkout request
   (`POST /api/portal/checkout`); unset → `org.default_locale`; unknown → 400 (same convention as the
   read `?locale=`). L5 (storefront) sends its route locale.

## Scope

**In:** `resolveForCheckout` locale params; `CheckoutLineResolution` resolved title; `StorefrontLineInput`
description + `buildLines` use; checkout/portal-checkout request `locale`; the two checkout callers.
**Out:** online/in-store line naming (stays `product.name`); anything in L2/L3/L4/L6.

## Acceptance criteria

1. A storefront checkout in `en` for a listing whose `en` title is `"Kettle"` (ar `"غلاية"`) persists
   the order line `description = "Kettle"`; the issued invoice line reads `"Kettle"`.
2. The same checkout in `ar` (or unset locale on an ar-default org) persists `"غلاية"`.
3. A listing with no `en` translation falls back to the default-locale title on an `en` checkout (never
   the internal `product.name`, never null).
4. `locale=fr` on checkout → 400. Online/in-store order lines still snapshot `product.name` (unchanged).
5. Portal checkout mirrors 1–4, scoped to the session's `(org, customer)`.

## Tests

`OrderLineTitleSnapshotIT`: storefront + portal checkout in each locale → assert the persisted
`sales_order_line.description` and the resulting invoice line; fallback case; `locale=fr` 400; an
online-order regression asserting `product.name` still snapshots there.

## Why separate from L2

L2 is listing **storage + reads + search** (the catalog surface). This is the **checkout/placement**
path — a different service (`SalesOrderService`) and two request contracts (anonymous + portal checkout).
Splitting keeps each diff reviewable and each test focused; ship L2b before (or with) L5 so the storefront
passes a checkout locale.
