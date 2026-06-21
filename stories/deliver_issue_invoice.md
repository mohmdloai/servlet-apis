# Slice: Deliver, issue invoice, auto-allocate (the magic moment)

> Outbound flow **TX-6** per the [fulfillment design](../sys-analysis/outbound/fulfillment.md).
> Continues the chain from [`ship_fulfillment.md`](ship_fulfillment.md): a SHIPPED fulfillment now
> reaches the customer — and that single DELIVERED transition issues the bill, settles it from the
> prepayment, and closes the order.

---

## Goal

Marking a `Fulfillment` **DELIVERED** issues a `SalesInvoice` for the delivered lines and
auto-allocates the order's prepayment FIFO — all in **one DB transaction**.

- `POST /api/orgs/{orgId}/fulfillments/{id}/deliver` — mark a SHIPPED fulfillment DELIVERED.

Done means: the full ONLINE flow works end to end — order → reserve → pay → ship → **deliver →
invoice + allocated + closed**.

---

## Scope

### In
- New domain: `SalesInvoice`, `SalesInvoiceLine`, `PaymentAllocation`, `InvoiceStatus`. `Payment`
  gains an `allocate(amount, now)` mutator (RECEIVED → PARTIALLY_ALLOCATED → ALLOCATED).
  `Fulfillment` gains `markDelivered(now)` (SHIPPED → DELIVERED).
- New repositories: `SalesInvoiceRepository` (+ factory; owns the gapless invoice-number counter),
  `PaymentAllocationRepository` (+ factory). New methods on `PaymentRepository`
  (`findUnallocatedByOrderForUpdate`, `updateAllocationState`), `SalesOrderRepository`
  (`updateFulfillmentState`), `FulfillmentRepository` (`sumDeliveredQtyByOrderLine`).
- New migration **V30** `invoice_number_counter` (per-org per-year sequence row).
- `FulfillmentService.markDelivered(orgId, fulfillmentId, actor)` — one transaction owning the whole
  sequence below.
- `FulfillmentHandler` routes `POST /fulfillments/{id}/deliver` (requires **STAFF**; ADMIN bypasses).

### The DELIVERED transaction (all in one txn)
1. **Fulfillment** SHIPPED → DELIVERED.
2. **SalesInvoice** built DRAFT mirroring the delivered `FulfillmentLine`s — each invoice line
   snapshots the order line's product id, description, unit price and tax rate at the delivered
   quantity. Totals are frozen.
3. Invoice → **ISSUED**: assigns a gapless `invoice_number` (`INV-YYYY-NNNN`) claimed from the
   per-org per-year counter via `SELECT … FOR UPDATE` — the lock is held to commit, so a rolled-back
   delivery burns no number.
4. **Auto-allocation** — the order's prepayment Payments are read FIFO and locked:
   `WHERE sales_order_id = :order AND unallocated_amount > 0 AND status NOT IN ('REFUNDED','DISPUTED')
   ORDER BY received_at ASC, id ASC FOR UPDATE`. Each is consumed up to the invoice's `grand_total`.
5. Per consumed payment: insert a `PaymentAllocation`, decrement `payment.unallocated_amount` and
   advance `payment.status`, and accrue `sales_invoice.paid_amount` (→ PAID when it reaches
   `grand_total`).
6. **Order roll-up**: FULFILLING → **FULFILLED** once every order line's delivered quantity equals
   its ordered quantity (`fulfilled_at` stamped); then FULFILLED → **CLOSED** once every invoice for
   the order is PAID (`closed_at` stamped).

### Out (deferred)
- **No in-store flow** (synchronous invoice+payment+DELIVERED at checkout).
- **No discount proration** onto per-fulfillment invoices — `discount_total` is 0 in v1.
- **No VOID / CreditNote / Refund** — corrections are a later slice.
- **No overpayment refund** — excess simply stays as `payment.unallocated_amount`.
- **No ETA e-invoicing** submission — schema fields reserved, untouched.

---

## Concurrency & idempotency

- `markDelivered` locks the **fulfillment** row `FOR UPDATE`; a double `POST /deliver` fails the
  SHIPPED guard (409). The **order** is also locked, so concurrent deliveries of the same order
  serialize on the FULFILLED/CLOSED roll-up.
- The prepayment Payments are locked `FOR UPDATE` in FIFO order, so two concurrent allocators of the
  same order's money can't double-spend a payment's unallocated balance.
- `sales_invoice.fulfillment_id` is `UNIQUE` — a backstop making issuance idempotent per fulfillment
  even if the status guard were bypassed.

---

## Tests

`api/src/test/java/.../fulfillment/DeliverInvoiceIT.java` (TestContainers Postgres, drives the
service directly):
- fully-prepaid single fulfillment → invoice ISSUED→PAID, gapless `INV-YYYY-0001`, one allocation
  consuming the full prepayment, payment ALLOCATED, order FULFILLED → CLOSED.
- partial delivery (two lines, two fulfillments) → one invoice each (`-0001`, `-0002`); order stays
  FULFILLING after the first, CLOSES after the last; payment PARTIALLY_ALLOCATED then ALLOCATED.
- re-deliver an already-DELIVERED fulfillment → 409, no duplicate invoice.
- deliver a still-PENDING (not shipped) fulfillment → 409, no invoice.
- two prepayments consumed FIFO by `received_at` — the earlier is exhausted before the later is
  touched; the leftover stays unallocated.
