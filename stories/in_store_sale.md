# Slice: In-store sale (one txn, no reservation)

> Outbound **Flow 1** per [`FLOW.md §3`](../sys-analysis/outbound/FLOW.md) and the in-store
> branches of the [SalesOrder](../sys-analysis/system/state-machines.md#a2-in-store-channel--in_store),
> [Fulfillment](../sys-analysis/system/state-machines.md#b2-in-store) and
> [SalesInvoice](../sys-analysis/system/state-machines.md#c-salesinvoice) state machines. This is the
> mirror image of the online chain (slices 1+4+5+6): everything those five transactions do over
> hours/days, the cashier does in **one DB transaction** at the counter.

---

## Goal

`POST /api/orgs/{orgId}/sales-orders` with `channel = IN_STORE` runs order placement, payment,
invoicing and fulfillment **in a single DB transaction**. No reservation phase, no PENDING_PAYMENT,
no SHIPPED phase — the customer is taking the goods now.

Done means: one POST returns a **CLOSED** order with an **ISSUED→PAID** invoice, an **ALLOCATED**
payment, and a **DELIVERED** fulfillment, and `inventory.stock_qty` has dropped — all or nothing.

---

## Why this slice is mostly composition

The online flow already owns every aggregate move this slice needs. If in-store felt like new code,
the earlier slices were over-coupled to the online timeline — so this slice **first refactors the
shared core out, then composes it**:

| In-store step | Reused from | Refactor done here |
|---|---|---|
| Build DRAFT order + lines (customer upsert, product snapshots, totals, order number) | slice 1 (`placeOnlineOrder`) | Extract `buildDraftOrder(...)` so both channels share it; channel is a parameter, not a hard-coded `ONLINE`. |
| Issue invoice + auto-allocate prepayment FIFO | slice 6 (`markDelivered`) | Extract `InvoiceService.issueForFulfillment(...)` — one allocation implementation, called by both `markDelivered` (online) and the in-store sale. |
| Decrement stock + record `-stock` log | slice 5 (`ship`) | New `FulfillmentService.createDelivered(...)`: the in-store variant of the SHIPPED stock move — checks availability (no prior reservation guarantees it) and decrements `stock_qty` only (`reserved_qty` untouched). |
| Create payment, recognise it | slices 4+5 (`reconcileAndCreate`) | New `PaymentService.recordInStorePayment(...)`: a cash / in-store-InstaPay transaction created VERIFIED + MATCHED on the spot, with the `RECEIVED` Payment, in the caller's txn. |

The orchestrator `SalesOrderService.placeInStoreSale(...)` is then a thin sequence over those
collaborators — each of which already runs inside a caller-supplied `txDsl` (the established
`ReservationService` / `PaymentService.reconcileAndCreate` pattern).

---

## The in-store transaction (all in one `txDsl`)

Causal order per [`FLOW.md §3`](../sys-analysis/outbound/FLOW.md) and `state-machines.md` A2.
FK order forces the Fulfillment to be inserted before the SalesInvoice (`sales_invoice.fulfillment_id`
is `NOT NULL UNIQUE`); the *logical* order (invoice owes, payment settles) is preserved by issuing the
invoice after the payment exists so auto-allocation finds it.

1. **SalesOrder** built DRAFT, `channel = IN_STORE`, optional walk-in customer (`customer_id` may be
   NULL); lines snapshot product description, unit price and tax rate; totals frozen.
2. **Fulfillment** created directly **DELIVERED** (no PENDING/SHIPPED). Per line: availability check
   (`available >= qty`, else 409 with all shortages), `-stock` `inventory_log` row (`reason = SOLD`),
   `inventory.stock_qty -= qty` (`reserved_qty` unchanged). `fulfillment_line.inventory_reservation_id
   = NULL`.
3. **Payment**: a `PaymentTransaction` (`provider ∈ {CASH, INSTAPAY_IN_STORE}`, `direction = CREDIT`)
   created **VERIFIED + MATCHED** on the spot, then a `Payment` **RECEIVED** with
   `unallocated_amount = amount`, `sales_order_id` set.
4. **SalesOrder** DRAFT → **PAID** (`prepaid_amount = grand_total`).
5. **SalesInvoice** built DRAFT mirroring the order lines → **ISSUED** (gapless `INV-YYYY-NNNN`),
   then auto-allocation consumes the just-created Payment FIFO up to `grand_total`: one
   `PaymentAllocation`, Payment → **ALLOCATED**, invoice `paid_amount = grand_total` → **PAID**.
6. **SalesOrder** PAID → **CLOSED** (`closed_at` stamped). In-store never observably reaches
   FULFILLING/FULFILLED (`state-machines.md` A2).

Observable post-commit: order **PAID → CLOSED** (DRAFT/RECEIVED/ISSUED exist only intra-txn).

---

## Scope

### In
- New domain: `Fulfillment.createDelivered(...)` (created directly DELIVERED); `SalesOrder` gains a
  PAID → CLOSED transition for the in-store fast path (`close()` still requires FULFILLED for online).
- New collaborator `InvoiceService.issueForFulfillment(txDsl, ...)` extracted from `markDelivered`;
  `markDelivered` refactored to call it (behaviour-preserving — covered by `DeliverInvoiceIT`).
- New collaborator `FulfillmentService.createDelivered(txDsl, ...)` (in-store direct-DELIVERED stock
  move) and `PaymentService.recordInStorePayment(txDsl, ...)`.
- `SalesOrderService.placeInStoreSale(...)` orchestrating the single transaction; `buildDraftOrder`
  shared with `placeOnlineOrder`.
- `POST /api/orgs/{orgId}/sales-orders` now dispatches by request `channel`: `IN_STORE` →
  in-store sale (requires **STAFF**; ADMIN bypasses); `ONLINE`/`PHONE` → existing online path
  (unchanged, still requires the `Idempotency-Key` header).

### Out (deferred)
- **Exact tender only**: payment amount equals `grand_total`. Overpaid (cashier hands back change)
  and underpaid (chase / CreditNote partial-accept) both need the Refund/CreditNote machinery — a
  later slice. A non-exact amount is rejected `400`.
- **No barcode lookup**: lines are by `product_id` (same shape as online). `(org_id, barcode)`
  resolution is a thin add-on for a later slice.
- **No discount proration** — `discount_total` is 0 in v1 (matches the online invoice).
- **No post-checkout return / void** — CreditNote + Refund is a later slice.
- **No `Idempotency-Key`** on in-store: the cashier action is interactive and not retried by a
  storefront; a double-submit creates a second order (acceptable for v1, unlike storefront).

---

## Concurrency & idempotency

- The whole sale is one `rootDsl.transactionResult(...)`: any failure (out of stock, bad input)
  rolls back every row — no partial order, no orphaned stock movement.
- Inventory rows are locked `FOR UPDATE` in `product_id ASC` order (project-wide deadlock rule),
  matching reservation/ship discipline, so concurrent in-store sales of the same SKU serialise on
  availability.
- `payment_transaction (provider, provider_ref)` is `UNIQUE`; the synthesised in-store ref
  (`<provider>-<order_number>`, or the caller's ref) is unique per order, so a retried POST that
  reaches payment insertion fails the constraint rather than double-charging. `sales_invoice
  (fulfillment_id)` and `(org_id, invoice_number)` remain the issuance backstops.

---

## API contract

### Request — `POST /api/orgs/{orgId}/sales-orders`
```json
{
  "channel": "IN_STORE",
  "customer": { "name": "Walk-in", "email": "...", "phone": "...", "address": "..." },
  "lines": [ { "product_id": "<uuid>", "quantity": 2 } ],
  "payment": { "provider": "CASH", "provider_ref": "optional" },
  "notes": "counter sale"
}
```
- `channel` defaults to `ONLINE` when omitted (backward compatible).
- `customer` is **optional** for `IN_STORE` (walk-in → `customer_id` NULL, invoice snapshot
  "Walk-in customer"); required for `ONLINE`.
- `payment.provider` ∈ `{CASH, INSTAPAY_IN_STORE}` (enum name or DB literal); `INSTAPAY_MANUAL` is
  rejected (that's the online path). `provider_ref` optional — synthesised from the order number when
  blank.

### Response — `201 Created`
```json
{
  "order":   { "id", "order_number", "channel": "IN_STORE", "status": "CLOSED",
               "grand_total", "prepaid_amount", "lines": [...] },
  "invoice": { "id", "invoice_number", "status": "PAID", "grand_total", "paid_amount", "lines": [...] },
  "payment": { "id", "status": "ALLOCATED", "amount", "unallocated_amount": "0.00" },
  "fulfillment": { "id", "status": "DELIVERED", "delivered_at" }
}
```

### Errors
| Status | Cause |
|---|---|
| `400` | blank/invalid lines, `quantity <= 0`, missing product, `payment.provider` missing or `INSTAPAY_MANUAL`, payment amount ≠ grand_total |
| `403` | caller lacks STAFF (or system ADMIN) in `:orgId` |
| `409` | insufficient stock for any line (lists every shortage) |

---

## Authorization
- `AuthzHelper.requireOrgAccess(orgId, STAFF)` — system ADMIN bypasses (same as the online POST).

---

## Tests

`api/src/test/java/.../sale/InStoreSaleIT.java` (TestContainers Postgres, drives the service):
- happy path (cash, single line): order **CLOSED**, invoice `INV-YYYY-0001` **ISSUED→PAID**
  `paid_amount == grand_total`, exactly one `PaymentAllocation`, payment **ALLOCATED**
  `unallocated == 0`, fulfillment **DELIVERED** with `inventory_reservation_id` NULL, `stock_qty`
  dropped by qty and `reserved_qty` unchanged, one SOLD `inventory_log` row.
- multi-line, INSTAPAY_IN_STORE: both products decrement; one invoice with two lines; order CLOSED.
- walk-in (no customer): order `customer_id` NULL, invoice snapshot "Walk-in customer".
- out of stock on any line → **409**, transaction rolled back (no order / payment / stock move).
- `INSTAPAY_MANUAL` provider → **400**; payment amount ≠ grand_total → **400**.
- channel omitted / `ONLINE` still routes to the online path (regression): `Idempotency-Key`
  required, order PENDING_PAYMENT with reservations.
- regression: `DeliverInvoiceIT` and `FulfillmentShipIT` still pass after the `InvoiceService`
  extraction.
