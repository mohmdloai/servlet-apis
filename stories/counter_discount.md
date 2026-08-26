# Slice: Counter discount on an in-store sale, behind a MANAGER gate

> Follow-up to [`in_store_sale.md`](./in_store_sale.md) (which shipped `discount_total = 0` and
> said *"a counter discount is a different authority question"*) and to
> [`honest_coupons.md`](./honest_coupons.md) (which built the discount arithmetic and the invoice
> proration this slice reuses). Loyverse gap #2 after
> [`capture_walk_in_customer.md`](./capture_walk_in_customer.md). **Branch `164_feat/counter-discount`
> stacked on `163_feat/walk-in-contact` (V87), migration V88** — renumber at merge if a sibling
> lands first.

---

## Goal

Let a **MANAGER** (or OWNER, or platform ADMIN) take a discount off a counter sale — a percentage
or a fixed amount, off the whole ticket — with the sale recording **who** granted it, **what** it
was, and optionally **why**; and refuse the same request from **STAFF** with a 403 that names the
role, not "you don't have permission".

Done means:
- A MANAGER rings a 3-line sale with `discount: {type: PERCENT, value: 10}` → the order carries
  `discount_total`, the invoice's grand total is the discounted number, the payment is for that
  number, the 80 mm receipt prints a `Discount (10%)` line, and the order row says which user
  granted it.
- The same request from a STAFF cashier → **403 `APPROVAL_REQUIRED` with `required_role:
  "MANAGER"`**, nothing written.
- A sale with no `discount` block is byte-identical to today.

---

## Why this shape

### The gate is the caller's role, not a step-up on the cashier's phone

Three ways to say "a manager must sign this" were considered:

- **Manager step-up on the cashier's device** (Loyverse's PIN): the cashier taps *Discount*, a
  manager types a credential into the cashier's phone, the backend mints a single-use approval
  token the sale then carries. Rejected for v1: this codebase has no PIN, so the credential would
  be a **password typed into a shared device** — worse than the problem; and it needs an approval
  token lifecycle (mint, bind to amount, expire, consume) that nothing else here has.
- **Async approval** (cashier requests, a manager approves from their own phone, the sale waits):
  a queue and a notification for a customer standing at the counter. Rejected — the ticket is
  live; a counter cannot park a sale (that is Loyverse gap #4's open-ticket territory).
- **Gate on the caller's role (chosen)**: the discount is accepted only when the account ringing
  the sale holds **MANAGER+**. This is the codebase's one existing authority pattern for money —
  `refund_approval_threshold` escalates a payout to the caller-role OWNER via
  `callerIsOwnerOrAdmin`, decided in the **service**, thrown as `ApprovalRequiredException`, which
  `ApiErrors` turns into the 403 the admin app already renders as *"Needs {role} approval"*. The
  POST stays STAFF-gated (a sale is a STAFF action); only the `discount` block raises the bar. In
  a small shop the owner is usually at the counter; where they are not, the manager rings the
  discounted sale on their own session — the same thing a refund already requires today.

**Deliberately deferred, and sketched so it is a slice and not a redesign:** a per-org knob
`counter_discount_staff_limit` (percent, default **0**) mirroring `refund_approval_threshold` —
STAFF may discount up to it without a manager, above it the gate applies. It is the *threshold*
half of the same pattern; this slice ships the *gate* half with the threshold effectively 0. No
column for it now (a knob nobody has asked for yet is a knob nobody has tuned).

### Receipt-level, one discount, the coupon's arithmetic

- **One discount per sale, off the whole ticket** — not per line. `discount_total` exists at
  order level today; per-line discounts need line columns plus invoice-line proration, and
  Loyverse's own item discount is the rarer gesture. Deferred.
- **PERCENT or FIXED**, the coupon's two types, computed by the coupon's function — `PERCENT`
  rounds HALF_EVEN at scale 2, `FIXED` is capped at the goods subtotal, neither exceeds it — so a
  "10% off" at the counter and a `SAVE10` code produce the same piastres. The arithmetic moves
  from `Coupon.discountFor` to a small domain helper (`DiscountMath.discountFor(type, value,
  subtotal)`) that `Coupon` delegates to, so it stays **one implementation**.
- **Off the goods subtotal, pre-tax; tax stays on the undiscounted lines** — exactly the coupon's
  convention (`grand = subtotal + tax + shipping − discount`). Whether VAT should be charged on
  the discounted base is a money-model decision for *both* discount paths together, not something
  to fork at the counter.
- **A discount that would zero the grand is a 400** ("This discount exceeds the order total"),
  the coupon's rule for the same reason: the payment machinery assumes money moves. A free
  giveaway is an inventory adjustment, not a sale.
- **Mutually exclusive with a coupon by construction** — the in-store POS takes no coupon (v1 of
  `honest_coupons.md`), and a DB CHECK pins it so a future "both" is a decision, not an accident.
- **The invoice takes the whole discount, for free.** `InvoiceService.discountToBill` prorates the
  order's `discount_total` across invoices and gives the invoice that completes the goods the
  exact remainder; an in-store sale is one invoice for all its goods, so it takes all of it. No
  change there — only a test that says so.

### The order row is the audit

`counter_discount_by` (the granting user), the type and value, and the optional reason live on
the order — the same "freeze it where it happened" rule as `coupon_code` and the V80/V87
contacts. No `platform_audit` row: that ledger is the platform plane's; an org-plane money action
is answered by its own row, the way a refund's `executed_by` is.

---

## Data model — **V88** `Sales_order_counter_discount`

```sql
ALTER TABLE sales_order
    ADD COLUMN counter_discount_type   VARCHAR(10)
        CHECK (counter_discount_type IN ('PERCENT', 'FIXED')),
    ADD COLUMN counter_discount_value  NUMERIC(12,2),
    ADD COLUMN counter_discount_reason TEXT,
    ADD COLUMN counter_discount_by     UUID REFERENCES app_user(id),
    -- all-or-nothing: a discount is a (type, value, by) triple; reason is optional
    ADD CONSTRAINT ck_so_counter_discount_shape CHECK (
        (counter_discount_type IS NULL) = (counter_discount_value IS NULL)
        AND (counter_discount_type IS NULL) = (counter_discount_by IS NULL)
        AND (counter_discount_type IS NOT NULL OR counter_discount_reason IS NULL)),
    -- one discount authority per order: a coupon OR a counter discount, never both (v1)
    ADD CONSTRAINT ck_so_one_discount_source CHECK (
        coupon_id IS NULL OR counter_discount_type IS NULL);
```

- `discount_total` (V17) stays **the money**; these four say *why* it is non-zero. `value` is the
  rate for `PERCENT` (`(0, 100]`) or the amount for `FIXED` (`> 0`) — the coupon's own two
  meanings, so a report can print "10%" instead of reverse-engineering it from the piastres.
- No index (nothing queries by discount; a "discounts given" report is a later slice, and it will
  aggregate over `discount_total > 0 AND counter_discount_type IS NOT NULL`, which the order's
  `org_id` index already serves per tenant). No backfill — every existing order has none.
- `COMMENT ON COLUMN` each, in the V80/V87 voice.

---

## Application & wire

### Request — `POST /api/orgs/{orgId}/sales-orders`, `channel: IN_STORE`
```json
{
  "channel": "IN_STORE",
  "lines": [ { "product_id": "<uuid>", "quantity": 3 } ],
  "payment": { "provider": "CASH", "amount": 100 },
  "discount": { "type": "PERCENT", "value": 10, "reason": "damaged box" }
}
```
- `discount` is **optional**; when present `type` and `value` are required, `reason` optional
  (`Text.normalizeText`, ≤ 200 chars → 400 beyond).
- Validation (400, cause-naming, in `validateInStoreInputs`): `type` ∉ {PERCENT, FIXED}; PERCENT
  `value` ∉ (0, 100]; FIXED `value` ≤ 0; `discount` on `ONLINE`/`PHONE` → *"discount is only
  accepted on IN_STORE sales"* (staff phone orders have coupons; a counter discount on an order
  that still has to be paid remotely is a different authority again).
- **Authority (403, before any write):** `discount` present and the caller is not MANAGER+ (or
  system ADMIN) → `ApprovalRequiredException(msg, "MANAGER", null, discount)` → the existing
  `APPROVAL_REQUIRED` 403 envelope with `required_role: "MANAGER"` and `requested_amount`, **no
  `threshold_amount`** (there is no configurable threshold in this slice — sending `0.00` would
  make the client's detail copy say "above the EGP 0.00 limit"; absent, it falls back to the
  role-only sentence). The handler computes `callerIsManagerOrAdmin` the way it computes
  `isOwnerOrAdmin` for cancel — but note that precedent tests `contains(OWNER)` only, so the
  manager check must accept **MANAGER or OWNER** explicitly (or reuse `AuthzHelper`'s `RANK`) —
  and the **service** decides —
  the same split as the refund gate, so a future caller (a job, a script) cannot dodge it.
- Underpaid tender is judged against the **discounted** grand; change on an overpaid tender is
  computed from it too — both fall out of the existing code once `grandTotal` is right.

### Service
- `placeInStoreSale(...)` gains `DiscountInput discount` (`type`, `value`, `reason`) and
  `boolean callerIsManagerOrAdmin`. Inside the txn, after `buildDraftOrder` (which still passes
  `couponCode = null` for in-store): compute `discount = DiscountMath.discountFor(type, value,
  order.getSubtotal())`, `order.setTotals(subtotal, tax, shipping, discount, now)` again with the
  discount (DRAFT-only, already the API), refuse `grand ≤ 0`, then
  `order.applyCounterDiscount(type, value, reason, actorUserId, now)` (DRAFT-only, mirrors
  `applyCoupon`). Then insert as today.
- `SalesOrderRepositoryImpl.insert` writes the four columns; the row → domain mapper carries them
  back (`setCounterDiscount`, beside `setDeliveryContact`/`setWalkInContact`).

### Response — `201`
```json
{
  "order": {
    "subtotal": 300.00, "tax_total": 0.00, "discount_total": 30.00, "grand_total": 270.00,
    "counter_discount": { "type": "PERCENT", "value": 10, "reason": "damaged box", "by": "<user-id>" }
  },
  "invoice": { "subtotal": 300.00, "tax_total": 0.00, "discount_total": 30.00, "grand_total": 270.00, "paid_amount": 270.00 },
  "payment": { "amount": 270.00 }, "change": null
}
```
- `SalesOrderResponse` gains a nested `counter_discount` block (omitted when none). **Staff plane
  only** — `forCustomerView` withholds it (it names a staff user id), the `notes`/`delivery_*`/
  walk-in rule; `discount_total` was already public and stays so.
- `InStoreSaleResponse.Invoice` gains **`discount_total`** (the invoice has carried the column
  since V21; the in-store DTO simply never surfaced it because it was always zero).

### Receipt (80 mm) and invoice PDF
- `DocumentRenderService.renderReceipt` prints **`Discount (10%)` / `Discount`** between `Tax`
  and `TOTAL` **only when `discount_total > 0`** — today the receipt prints no discount line at
  all (it was always zero), so a discounted receipt would otherwise show a subtotal and a total
  that disagree with no line between them. The invoice/credit-note PDF already prints a
  `Discount` row and needs nothing.

---

## Authorization
`POST /sales-orders` stays **STAFF**. `discount` present ⇒ **MANAGER+** or system ADMIN, decided in
the service (`ApprovalRequiredException`, 403). Read-only impersonation is already refused at
`requireOrgAccess` for any write.

## Out (deferred)
- **STAFF allowance knob** (`counter_discount_staff_limit`, percent, per org) — the threshold
  half of the pattern; see above.
- **Per-line discounts**, **preset/named discounts** (Loyverse's "Staff 10%" buttons), a
  **discounts-given report**, and a discount on `PHONE` orders.
- **Discount + coupon on one order** (pinned exclusive by `ck_so_one_discount_source`).
- **Editing a discount after the sale** — an in-store order is CLOSED in the same txn; the path
  out is the existing credit note.

---

## Tests

`api/src/test/java/.../sale/CounterDiscountIT.java` (TestContainers, `InStoreSaleIT`'s harness —
a new class so the money-math cases read as one suite):
- **MANAGER, PERCENT 10 on 3 × 100**: `discount_total 30.00`, `grand 270.00`; invoice
  `discount_total 30.00` / `grand 270.00` / `paid 270.00` (the proration hands one invoice the
  whole discount); payment `amount 270.00`, ALLOCATED, nothing unallocated; stock −3; the four
  snapshot columns persisted with `counter_discount_by` = the caller; response block echoed.
- **FIXED 25 on 100**: `discount_total 25.00`, `grand 75.00`. **FIXED 100 on 100** → 400
  "exceeds the order total", nothing written (order count 0, stock untouched, no counter claimed
  — the same rollback shape as the underpaid test). **PERCENT 100** → the same 400.
- **Rounding parity**: PERCENT 33 on 10.00 → `3.30`; on 0.05-tail subtotals the HALF_EVEN result
  equals `Coupon.discountFor` for the same inputs (pin that the helper and the coupon agree).
- **OWNER**: accepted (RANK-aware gate). **System ADMIN**: accepted.
- **STAFF with a discount** → 403; body `error_code APPROVAL_REQUIRED`, `required_role MANAGER`,
  `requested_amount` present, `threshold_amount` **absent**; **no order, payment, invoice or
  inventory_log row exists afterwards** and no order number was consumed.
- **STAFF without a discount** → unchanged happy path (the gate only bites on the block).
- **Overpaid tender on a discounted sale**: tender 300 on grand 270 → change 30, EXECUTED cash
  refund as today. **Underpaid vs the discounted grand** (tender 260) → 400.
- **Validation 400s**: bad type; PERCENT 0 / 101; FIXED 0 / −5; reason > 200; `discount` on
  `ONLINE`.
- **No discount block**: `counter_discount_*` all NULL, `discount_total 0.00`, response omits the
  block — regression on the existing `InStoreSaleIT` happy path (add the NULL assertions there).
- `SalesOrderResponseCustomerViewTest`: `customerView_omitsCounterDiscount` /
  `authenticatedView_keepsCounterDiscount`.
- `DocumentRenderServiceTest`: `renderReceipt_printsDiscountLineWhenDiscounted` beside
  `renderReceipt_producesPdfWithTenderAndChange`, and the existing receipt test asserts **no**
  `Discount` text for an undiscounted sale.
- `DiscountMathTest` (domain, plain JUnit): PERCENT/FIXED/cap/zero-subtotal/scale cases — **new**;
  `Coupon.discountFor` has no unit pin today (it is exercised only through the checkout ITs), so
  these become the arithmetic's first direct tests, and `Coupon` delegating to the helper means
  they cover the coupon path too.

## Definition of done
Migration + domain + service + handler/DTO + renderer + the suites above green; `spotless:apply`;
story committed on `164_feat/counter-discount` (stacked on 163); `CLAUDE.md`'s in-store bullet
gains one sentence on the discount block and its MANAGER gate; the frontend pair is
`frontst/stories/126_st_counter_discount.md`.
