# Slice: Honest coupon codes (`coupon` + checkout `coupon` + prorated invoice discount)

> Roadmap item 9 (Tier 3) — flagged there as needing an explicit owner call, which was given
> 2026-07-24: **approved in principle** — a merchant-issued code that reduces the **real** total,
> shown transparently at checkout. The honesty rule stands untouched: no compare-at, no was–now,
> no % OFF badges on listings, no countdowns — a coupon is a genuine discount on the money actually
> charged, filling the `discountTotal` field that item 5 deliberately left at zero. Feeds frontend
> story 71.

---

## Goal

A merchant creates a code (`RAMADAN10`: 10% off, or `EGP50` fixed) with optional window, minimum
subtotal, and redemption cap. A shopper applies it at checkout (anonymous or portal); the placed
order's `discount_total` is the real reduction, the frozen code shows on the order, invoices stay
penny-exact, and every downstream money invariant holds.

## The money math (the load-bearing part)

- **Discount computes off the goods `subtotal`** (pre-tax, excluding shipping): `PERCENT` →
  `round(subtotal × value/100, HALF_EVEN)`; `FIXED` → `min(value, subtotal)`. The result becomes
  `discountTotal` in the existing formula `grand = subtotal + tax + shipping − discount`
  (`SalesOrder.setTotals` — its `discount ≤ subtotal` guard already exists).
- **Grand total must stay > 0**: `markPendingPayment`/`markPaid` reject non-positive grand
  (`SalesOrder:226/241`). A coupon that would zero the order → **400**
  `"This code exceeds the order total"`. Free orders are out of scope v1 (the whole payment
  machinery assumes money moves).
- **Invoice proration** — the identity *live invoices sum to the order grand total* (the CLOSED
  roll-up + FIFO allocator) must survive partial delivery. Item 5 solved shipping with
  first-live-invoice billing; a discount can exceed the first invoice's value, so it **prorates**
  instead. `InvoiceService.discountToBill(invoiceRepo, order, invoiceSubtotal)` (sits beside
  `shippingToBill`, replaces the hardcoded `BigDecimal.ZERO` "v1: no per-fulfillment discount
  proration"):
  ```
  remainingDiscount = order.discount_total − Σ live invoices' discount_total
  remainingSubtotal = order.subtotal      − Σ live invoices' subtotal
  bill = (invoiceSubtotal >= remainingSubtotal)            // the completing invoice
         ? remainingDiscount                               // takes the exact remainder
         : round(remainingDiscount × invoiceSubtotal / remainingSubtotal, HALF_EVEN)
  ```
  Penny-exact by construction (the last live invoice absorbs rounding), self-healing on
  void+reissue (re-derived from live rows, the `shippingToBill` property), and per-invoice
  `grand > 0` holds because the prorated share never exceeds the invoice's own subtotal.
  `SalesInvoice.createDraft` already accepts a discount — only the caller changes.
- **Everything downstream is money-shaped and unaffected**: reconciliation matches against
  `grand − prepaid`; cancel/expiry refunds unallocated prepayment; the payment-claim outstanding;
  credit notes credit goods lines as today (discount interplay on returns: out of scope,
  documented). Redemptions free themselves on cancel/expiry because counting is status-based
  (below) — no counter to drift.

## Migration — **V72** (after collections' V71; verify highwater)

```
coupon (
    id UUID PK, org_id UUID NOT NULL REFERENCES org,
    code VARCHAR(40) NOT NULL,                -- stored normalized UPPER; UNIQUE (org_id, code)
    type TEXT NOT NULL CHECK (type IN ('PERCENT','FIXED')),
    value NUMERIC(12,2) NOT NULL CHECK (value > 0),   -- percent 0–100 enforced in service
    min_subtotal NUMERIC(12,2),               -- nullable
    starts_at / expires_at TIMESTAMPTZ,       -- nullable window
    max_redemptions INT CHECK (> 0),          -- nullable = unlimited
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at / updated_at
)
ALTER TABLE sales_order
    ADD COLUMN coupon_id   UUID REFERENCES coupon,    -- nullable
    ADD COLUMN coupon_code VARCHAR(40);               -- the frozen display snapshot
```

**Redemption counting is a query, not a counter**: live redemptions = orders with this `coupon_id`
whose status ∉ (CANCELLED, EXPIRED). Checked inside the placement txn under
`SELECT … FOR UPDATE` on the coupon row (serializes the last-slot race); an expired/cancelled
order frees its slot automatically.

## Application & wire

- `PublicCheckoutRequest` + `PortalCheckoutRequest` gain optional **`coupon`** (the code). A new
  `CouponService.resolveForOrder(txDsl, orgId, rawCode, subtotal, now)` runs **inside the
  placement txn** after line resolution: normalize (trim/upper, `Text` discipline) → load FOR
  UPDATE → check active + window + `min_subtotal` + redemption cap → compute the discount →
  return `{couponId, code, discount}`. `buildDraftOrder` threads it into `setTotals` and the
  order snapshot (`coupon_id` + `coupon_code`). Idempotent replay returns the prior order —
  consistent for free.
- **Error surfaces (cause-naming, but no code-oracle)**: unknown / inactive / not-started /
  expired / exhausted all collapse to one uniform 400 **"This code isn't valid or has expired"**
  (codes are semi-public; which-reason is still nobody's business). The one helpful specific:
  below-minimum → 400 `"This code applies to orders of {min} EGP or more"` (the code IS valid —
  telling the shopper how to qualify is the honest move).
- **Pre-checkout preview**: `POST /api/public/{orgSlug}/coupons/validate {code, subtotal}` →
  `200 {code, discount}` or the 400s above — anonymous, `Cache-Control: no-store`, and a **strict
  new bucket `rl:pub-coupon`** (`PUBLIC_COUPON_LIMIT`, default 10/min — an enumeration surface).
  Serves both the guest and portal checkout pages (org-scoped, no session needed). The validate
  is advisory; placement re-validates inside the txn (the slot can vanish between preview and
  place → the uniform 400, order not created).
- **Scope**: storefront + portal checkout only. The in-store POS and admin phone orders take no
  coupon in v1 (a counter discount is a different product decision — documented).
- **Order display**: `SalesOrderResponse` + `PublicOrderResponse` gain `coupon_code` (frozen;
  `discount_total` already crosses). Admin order detail thereby shows both.

## Admin API (new `CouponHandler`)

- `GET /api/orgs/{orgId}/coupons?page&size` (VIEWER) — newest-first, each row with its live
  `redemption_count` (batch query, no N+1).
- `POST` (MANAGER — money-shaped) — `{code, type, value, min_subtotal?, starts_at?, expires_at?,
  max_redemptions?}`; percent ∈ (0,100]; fixed > 0; window sane (`starts < expires`); duplicate
  code → 409.
- `GET /{id}` · `PATCH /{id}` (MANAGER — `{active?, expires_at?, max_redemptions?}` only: code,
  type, and value are **immutable once created** — orders froze them; make a new code instead).
- `DELETE /{id}` (MANAGER) — only when never redeemed (referenced → 409 "deactivate instead").

## Tests (`CouponCheckoutIT` + `CouponAdminIT` — models: `PublicCheckoutIT`/`PortalCheckoutIT`, `TaxShippingConfigIT` for money math)

1. **Percent + fixed happy paths** (anonymous + portal): 10% off a 200 + 14%-tax + 25-shipping
   order → discount 20.00, grand = 200 + 28 + 25 − 20 = 233.00; frozen `coupon_code` on the
   order; exact-cover reconcile at the discounted grand → MATCHED/PAID.
2. **Proration identity**: two-line coupon order, deliver line A then B → each invoice carries its
   prorated discount, Σ live invoice grands = order grand, order CLOSES; an odd-piastre discount
   lands penny-exact on the completing invoice; void+reissue re-carries correctly.
3. **Eligibility wall**: unknown/inactive/expired/not-started/exhausted → the uniform 400;
   below-minimum → the specific 400; over-total fixed code → "exceeds the order total" 400;
   percent 100 on a tax-free, shipping-free order → grand 0 → 400, nothing placed.
4. **Redemption cap race + freeing**: cap 1 — two concurrent placements → exactly one succeeds
   (FOR UPDATE serialization); cancel/expire the winner → the slot frees and a new placement
   succeeds.
5. **Validate endpoint**: preview matches placement math; rate bucket 429s; advisory property
   (slot taken between preview and place → placement 400s cleanly).
6. **Admin**: CRUD + immutability (PATCH value → 400) + delete-guard 409 + auth matrix
   (VIEWER read / MANAGER write); redemption counts accurate across statuses.
7. **Regression**: no-coupon checkout byte-identical (existing checkout/money suites unchanged:
   `PublicCheckoutIT`, `PortalCheckoutIT`, `TaxShippingConfigIT`, `DeliverInvoiceIT`).

## Definition of done

V72 + codegen; `CouponService` + handler + checkout threading + `discountToBill` + validate route
+ `rl:pub-coupon` bucket; all seven IT groups green; named existing suites untouched and green;
spotless clean; full `-Dtest='*IT'` sweep green.
