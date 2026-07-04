# Slice: List an order's payments (G3 — the order's money story)

> Today an order's money story is invisible beyond the single `prepaid_amount` scalar on the
> order row. The admin reconciling, chasing, cancelling, or disputing needs the constituent
> payments: which transfers paid this order, when, how much of each is still unallocated, what was
> refunded, what is frozen in dispute. Frontend driver: the **Sales Order detail** screen's money
> panel and the cancel/dispute ActionBars in
> [`docs/frontend-architecture.md`](../docs/frontend-architecture.md) §1.

---

## Goal

An org member reads every payment ever applied to an order, each with its refunds:

`GET /api/orgs/{orgId}/sales-orders/{id}/payments`

Done means: the response reconstructs `prepaid_amount` from its parts — an UNDERPAID top-up
sequence shows two partial payments FIFO, an overpaid order shows the excess sitting on
`unallocated_amount`, a cancelled order shows each payment's PENDING direct refund, a disputed
payment shows DISPUTED with its reason — without psql access.

---

## Why this slice is small

| Piece | Already existed | Added here |
|---|---|---|
| Payment rows per order | `payment.sales_order_id` + FIFO ordering convention (`received_at ASC, id ASC` — the allocation order) | `PaymentRepository.findByOrderId(orgId, salesOrderId)` — the unlocked, unfiltered sibling of `findUnallocatedByOrderForUpdate` |
| Refunds per payment | `RefundRepository.findByPaymentId(orgId, paymentId)` (orphan-refund slice, oldest-first) | reused verbatim |
| Row shapes | `PaymentResponse` (incl. `unallocated_amount`, `refunded_amount`, dispute fields), `RefundResponse` | reused; a thin `OrderPaymentsResponse` envelope nests them |
| Order header | the `OrderSummary` shape from `PaymentTransactionResponse` (id, order_number, status, grand_total, prepaid_amount) | reused |
| Routing | `SalesOrderHandler` (POST-only today — place + `/{id}/cancel`) | a `GET` branch for `/{id}/payments`; 405 guard + javadoc updated |

No migration. No pagination — an order's payment count is small and bounded by real-world
transfers (v1 is manual InstaPay); revisit only if a PSP feed ever changes that.

---

## Semantics

- Returns **all** payments referencing the order, regardless of status — RECEIVED, ALLOCATED,
  DISPUTED, REFUNDED. The money story includes the money that left.
- Ordered `received_at ASC, id ASC` — the same FIFO order invoice allocation consumes them in,
  so the list reads as the audit trail of `prepaid_amount`.
- Each payment carries its refunds (oldest first) from the existing per-payment finder: cancel-
  created PENDING direct refunds, executed ones, cancelled ones. The refund lifecycle itself
  stays on `/refunds/{id}` routes.
- The order header is included so the screen renders standalone (`grand_total` vs
  `prepaid_amount` vs the sum of parts).
- Standalone orphan-refund payments (`sales_order_id` NULL) can never appear here by
  construction.
- Read-only on `rootDsl`, no explicit transaction — same as every other read.

### Service

`PaymentService.listForOrder(orgId, salesOrderId)` → `OrderPayments(SalesOrder order,
List<PaymentWithRefunds> payments)`; 404 (`NotFoundException`) if the order isn't in `:orgId`.

---

## API contract

```
GET /api/orgs/{orgId}/sales-orders/{id}/payments
```

`200 OK`:
```json
{
  "order": {
    "id": "<uuid>",
    "order_number": "SO-2026-000123",
    "status": "CANCELLED",
    "grand_total": "1000.00",
    "prepaid_amount": "600.00"
  },
  "data": [
    {
      "id": "<uuid>",
      "sales_order_id": "<uuid>",
      "payment_transaction_id": "<uuid>",
      "amount": "400.00",
      "currency": "EGP",
      "unallocated_amount": "0.00",
      "refunded_amount": "400.00",
      "status": "REFUNDED",
      "received_at": "2026-06-20T09:00:00Z",
      "refunds": [
        {
          "id": "<uuid>",
          "amount": "400.00",
          "method": "INSTAPAY_MANUAL",
          "status": "EXECUTED"
        }
      ]
    },
    {
      "id": "<uuid>",
      "amount": "200.00",
      "unallocated_amount": "200.00",
      "refunded_amount": "0.00",
      "status": "RECEIVED",
      "received_at": "2026-06-21T14:30:00Z",
      "refunds": [
        { "id": "<uuid>", "amount": "200.00", "status": "PENDING" }
      ]
    }
  ]
}
```
(Payment rows are the existing `PaymentResponse` shape; refund rows the existing
`RefundResponse`; empty `refunds` arrays are present-but-empty, and an order with no payments
returns an empty `data`.)

### Errors
| Status | Cause |
|---|---|
| `400` | malformed `{id}` |
| `403` | caller has no role in `:orgId` |
| `404` | order not found in `:orgId` |

---

## Authorization

`AuthzHelper.requireOrgAccess(orgId, VIEWER)` — the project-wide read bar (same as
`GET /payments/{id}`). The POST routes on the handler keep their existing STAFF/MANAGER gates.

---

## Scope

### In
- `PaymentRepository.findByOrderId(orgId, salesOrderId)` (+ jOOQ impl, `received_at ASC, id ASC`).
- `PaymentService.listForOrder(...)` + `PaymentWithRefunds`/`OrderPayments` records (reusing
  `RefundRepository.findByPaymentId` per payment).
- `GET /{id}/payments` branch in `SalesOrderHandler`; `OrderPaymentsResponse` DTO nesting the
  existing `PaymentResponse` + `RefundResponse`.
- Docs: CLAUDE.md endpoint list.

### Out (deferred)
- **`GET /sales-orders/{id}`** (the order itself + timeline) — the Sales Order detail screen
  slice; this endpoint's `order` header is deliberately just the summary shape.
- **Invoice allocations per payment** — "which invoice consumed this payment" is the Allocation
  view; the `unallocated_amount` residual is enough for the money panel v1.
- **Pagination** — bounded cardinality (see above).

---

## Tests

`api/src/test/java/.../payment/OrderPaymentsIT.java` (TestContainers Postgres, drives services):
- two UNDERPAID partials then an exact top-up → three payments FIFO by `received_at`, sum equals
  `prepaid_amount`, order header PAID.
- OVERPAID single payment → one row, `unallocated_amount` = the excess.
- cancel the order → each unallocated payment now carries its PENDING direct refund; refunded
  payment shows `refunded_amount` after execute.
- disputed payment → row present with `status=DISPUTED` + `dispute_reason`.
- order with no payments → `data: []`, header intact.
- unknown order / another org's order → 404 (scoping, not 403).
- VIEWER reads fine; non-member → 403.
