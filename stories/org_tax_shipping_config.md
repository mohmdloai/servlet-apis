# Slice: Config-complete tax + shipping fee (`org.tax_rate` / `org.shipping_fee`)

> Roadmap item 5 (Tier 1) — [`docs/storefront-growth-roadmap.md`](../docs/storefront-growth-roadmap.md).
> The money fields threaded through orders/invoices already computed (per-line `tax_rate`, order
> `tax_total`) but were pinned to `0` in `SalesOrderService`. This slice turns them into per-org
> settings and adds the missing **shipping fee**, making the money math real. **Currency stays EGP**
> (owner call 2026-07-24: per-org currency deferred — the frontend Money value object's currency is
> a compile-time literal, a real refactor for no near-term gain). Feeds frontend story 62.

---

## Goal

An OWNER sets `tax_rate` (a fraction, e.g. `0.14`) and `shipping_fee` (flat EGP per order) in
Settings. From then on every placement applies them: each order line is taxed at the org rate, and
ONLINE/PHONE orders carry the shipping fee. Orders, invoices, PDFs, and the public order views all
show the real breakdown. An unconfigured org (both 0) reproduces the historic math exactly.

## The two structural decisions

1. **Shipping is a scalar, never an order line.** `sales_order_line.product_id` is `NOT NULL` FK and
   the reservation engine iterates every line (`fetchProductSnapshots` 404s, `reserveForOrder` 409s
   on a product-less line) — a synthetic shipping line breaks placement. So `sales_order` gains
   `shipping_total` and the grand-total formula becomes
   `subtotal + tax + shipping − discount` (`SalesOrder.setTotals`).
2. **Invoices bill shipping on the first live invoice only.** Invoices recompute totals from line
   specs, and the CLOSED roll-up + FIFO allocator depend on *live invoices summing to the order
   grand total*. `InvoiceService.shippingToBill` charges `order.shipping_total` on the first
   non-VOID invoice of the order and 0 afterwards; a void+reissue re-carries it naturally (the
   predecessor is VOID by the time the replacement issues). `sales_invoice` gains `shipping_total`.

## Design

- **V68**: `org.tax_rate NUMERIC(6,4) CHECK 0…1` + `org.shipping_fee NUMERIC(14,2) CHECK ≥0` (both
  NOT NULL DEFAULT 0); `sales_order.shipping_total` + `sales_invoice.shipping_total` (same shape).
- **Placement** (`SalesOrderService.buildDraftOrder`): now takes the `Org` (loaded in-txn — the
  online path already loaded it for the TTL; the in-store path loads it too). Per-line
  `taxRate = org.tax_rate`; `shipping_total = channel == IN_STORE ? 0 : org.shipping_fee` (an
  in-store sale walks out with the goods). `DEFAULT_TAX_RATE` deleted.
- **Settings**: `OrgService.StoreConfig{taxRate, shippingFee}` + `validateStoreConfig` (mirrors the
  V68 CHECKs) + `applyStoreConfig` (null = leave unchanged), threaded through the OWNER
  `PUT /api/orgs/{orgId}` and the platform `PATCH /api/admin/orgs/{orgId}` (audited in the
  `ORG_UPDATE` detail). `OrgResponse` echoes both.
- **Read surfaces**: `SalesOrderResponse` / `PublicOrderResponse` / `InvoiceResponse` /
  `PortalInvoiceResponse` gain `shipping_total`; the public storefront profile
  (`GET /api/public/{orgSlug}`) gains `tax_rate` + `shipping_fee` so the cart/checkout can preview
  totals honestly before placement (not sensitive — both render on every order summary anyway).
- **PDF**: the A4 invoice totals gain a Shipping row (this also fixes a latent off-by-one that
  bolded "Paid" instead of "Grand total"). The 80mm receipt is in-store-only (shipping always 0) and
  is unchanged.

## Out of scope / deliberate

- **Currency** — deferred (EGP constant stays; the invoice already inherits `order.currency`).
- **Discounts** — `discountTotal` stays 0 (coupons are roadmap #9, approved-in-principle, later).
- **Shipping refunds on failed fulfillment** — `grandTotalOf(specs)` still sizes the refund by goods
  value only; refunding the delivery fee stays a manual (orphan/direct-refund) decision.
- **Per-zone shipping** — a flat fee ships first; a `shipping_zone` table is the named follow-up.
- **Credit notes** — credit goods lines only, as today.

## Tests (`TaxShippingConfigIT` + updated suites)

1. Online order: 14% on every line + flat fee → subtotal 30 / tax 4.20 / shipping 25 / grand 59.20;
   the full grand reconciles as MATCHED.
2. Partial delivery: first invoice bills shipping (26.00), second doesn't (11.00); live invoices sum
   to the order grand; order CLOSES.
3. In-store: tax yes (34.20), shipping never; invoice mirrors.
4. Unconfigured org → historic zero math byte-for-byte.
5. `validateStoreConfig` rejects `tax_rate > 1` / negative fee; boundaries pass.

Full regression: 190 unit + 681 IT green.
