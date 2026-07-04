# Slice: Money-side reads (the refunds worklist, the cap meter, and invoice discovery)

> Until now the money-out flow was write-mostly: refunds could be created, executed and cancelled
> but there was no list — the "to-execute queue" the whole two-step refund lifecycle presupposes
> could not be rendered at all. A `RefundResponse` was bare uuids (`payment_id`/`credit_note_id` +
> amount), so even a hand-rolled list couldn't say *what the money is for*. The dispute-resolution
> flow had no entry point: `GET /payments/{id}` didn't say which invoices the payment funded, so
> the UI couldn't know which invoice to issue the `DISPUTE_RESOLUTION` credit note against. The
> cumulative-credit cap was discoverable only by bouncing off the 400. And invoices were reachable
> only from a cached deliver-response id. Frontend driver: the refunds worklist + dispute + credit
> screens in [`docs/frontend-architecture.md`](../docs/frontend-architecture.md).

---

## Goal

An org member reads the money-out flow end to end:

1. `GET /api/orgs/{orgId}/refunds?status=&page=&size=` — the refunds worklist
   (`?status=PENDING` = the to-execute queue, oldest first) / refund ledger (unfiltered, newest
   first), every row carrying its **source context**: `sales_order_id` + `sales_order_number` for a
   payment-backed refund (both absent for an orphan payment — an unmatched transfer has no order),
   `sales_invoice_id` + `credit_note_number` for a CreditNote-backed one.
2. `GET /api/orgs/{orgId}/payments/{id}` — now decorated with `allocations`: every invoice the
   payment funded (FIFO), each with `sales_invoice_id`, `invoice_number`, `invoice_status`,
   `amount`, `received_at` — the dispute-resolution entry point.
3. `GET /api/orgs/{orgId}/credit-notes?sales_invoice_id=&status=` — one invoice's crediting story:
   the invoice header (the cap = `grand_total`), `credited_total` (the cap guard's own
   ISSUED+SETTLED sum), and the notes oldest-first — the honest "EGP X of EGP Y already credited"
   meter.
4. `GET /api/orgs/{orgId}/sales-orders/{id}/invoices` — the order's billing story, mirroring
   `/{id}/payments` and `/{id}/fulfillments`.

Done means: a refunds worklist card can say "EGP 250 — Order SO-1001" or "CN-2026-0003" or
"Unmatched transfer" without one extra request per row, and the dispute-resolution flow can pick
its target invoice from reads alone.

---

## Semantics

- **Queue vs ledger** (same convention as `GET /payment-transactions` and `GET /fulfillments`): a
  `status` filter makes `GET /refunds` a worklist — `created_at ASC, id ASC` (execute the oldest
  first); no filter makes it the audit ledger — `created_at DESC, id DESC`. `page` floors at 0,
  `size` clamps to `[1, 100]` (default 20), `PageResponse` envelope. Unknown `status` → 400.
- **Source context is batch-loaded** — one projection per source aggregate per page
  (`PaymentRepository.findOrderIdsByIds` → `SalesOrderRepository.findOrderNumbersByIds`;
  `CreditNoteRepository.findRefsByIds`), never per row. Orphan-payment refunds legitimately carry
  no order — absence is data ("unmatched transfer"), not a loading failure.
- `RefundResponse` now also carries `created_at` (the queue's sort key) on every shape. Mutation
  responses (`POST /refunds`, `execute`, `cancel`) omit the context fields — null → omitted.
- **Payment allocations**: `payment.allocations` is present on the detail read only (the embedded
  payment shapes in `GET /sales-orders/{id}/payments` stay lean). Empty list = never allocated.
  Each entry joins the invoice's identity so the dispute flow can issue the `DISPUTE_RESOLUTION`
  CreditNote against the right invoice without further lookups.
- **The cap meter is the guard's own arithmetic**: `credited_total` =
  `CreditNoteRepository.sumIssuedTotalByInvoice` (ISSUED + SETTLED, VOID excluded) — the exact sum
  `CreditNoteService.issue` enforces against `invoice.grand_total`, so a client-side meter can
  never disagree with the 400. The `status` filter narrows the returned notes, never the sum.
  Notes are returned oldest-first, without lines (the detail read `GET /credit-notes/{id}` has
  them). No pagination: the count is bounded by the cap itself.
- **`sales_invoice_id` is required** on `GET /credit-notes` — the bare list 400s, reserving the
  route for a future unfiltered slice (same convention as the bare `GET /sales-orders`).
- **The by-order invoice list** returns **all** invoices regardless of status, oldest first — VOID
  is part of the story (a reissue reads as voided + replacement) — each with its lines, plus the
  order header (`OrderSummary`) so the billing panel renders standalone. No pagination: invoice
  count is bounded by the order's fulfillment count.
- Reads are read-only on `rootDsl`, no lock; org-scoping is a 404 (shared-schema convention).

### Service

- `RefundService.list(orgId, status, page, size)` → `RefundPage(List<RefundView>, total)`;
  `RefundView` = refund + `salesOrderId`/`salesOrderNumber`/`salesInvoiceId`/`creditNoteNumber`.
  (RefundService gained a `SalesOrderRepositoryFactory` dependency for the number projection.)
- `PaymentDisputeService.get(orgId, id)` → `PaymentView(payment, List<AllocationView>)`;
  `AllocationView(allocation, invoice)`. 404 semantics unchanged.
- `CreditNoteService.listForInvoice(orgId, salesInvoiceId, status)` →
  `InvoiceCreditNotes(invoice, creditedTotal, notes)`; 404 if the invoice isn't in `:orgId`.
- `InvoiceAdminService.listForOrder(orgId, salesOrderId)` → `OrderInvoices(order,
  List<InvoiceView>)`; 404 if the order isn't in `:orgId`.
  (`SalesInvoiceRepository.findByOrderId` now orders `created_at ASC, id ASC`.)

---

## API contract

```
GET /api/orgs/{orgId}/refunds?status=PENDING&page=0&size=20
```

`200 OK` (`PageResponse` envelope; each row the full `RefundResponse` + source context):
```json
{
  "data": [
    {
      "id": "<uuid>",
      "payment_id": "<uuid>",
      "amount": 400.00,
      "currency": "EGP",
      "status": "PENDING",
      "method": "CASH",
      "created_at": "2026-07-01T10:00:00Z",
      "sales_order_id": "<uuid>",
      "sales_order_number": "SO-2026-000123"
    },
    {
      "id": "<uuid>",
      "credit_note_id": "<uuid>",
      "amount": 60.00,
      "currency": "EGP",
      "status": "PENDING",
      "method": "INSTAPAY_MANUAL",
      "created_at": "2026-07-01T09:00:00Z",
      "sales_invoice_id": "<uuid>",
      "credit_note_number": "CN-2026-0003"
    },
    {
      "id": "<uuid>",
      "payment_id": "<uuid>",
      "amount": 150.00,
      "currency": "EGP",
      "status": "PENDING",
      "method": "INSTAPAY_MANUAL",
      "created_at": "2026-07-01T08:00:00Z"
    }
  ],
  "total": 3,
  "page": 0,
  "size": 20
}
```
(The third row is an orphan-payment refund: no order context — render "Unmatched transfer".)

```
GET /api/orgs/{orgId}/payments/{id}
```
`200 OK` — the existing `PaymentResponse` plus:
```json
{
  "…": "existing payment fields",
  "allocations": [
    {
      "sales_invoice_id": "<uuid>",
      "invoice_number": "INV-2026-0007",
      "invoice_status": "ISSUED",
      "amount": 30.00,
      "received_at": "2026-07-01T10:00:00Z"
    }
  ]
}
```

```
GET /api/orgs/{orgId}/credit-notes?sales_invoice_id=<uuid>&status=ISSUED
```
`200 OK`:
```json
{
  "invoice": {
    "id": "<uuid>",
    "invoice_number": "INV-2026-0007",
    "status": "ISSUED",
    "grand_total": 200.00,
    "currency": "EGP"
  },
  "credited_total": 80.00,
  "data": [ { "…": "CreditNoteResponse rows, oldest first, lines omitted" } ]
}
```

```
GET /api/orgs/{orgId}/sales-orders/{id}/invoices
```
`200 OK` (mirrors `OrderFulfillmentsResponse`):
```json
{
  "order": { "id": "<uuid>", "order_number": "SO-2026-000123", "status": "FULFILLING",
             "grand_total": "500.00", "prepaid_amount": "500.00" },
  "data": [ { "…": "InvoiceResponse rows with lines, created_at ASC, VOID included" } ]
}
```

### Errors
| Status | Cause |
|---|---|
| `400` | unknown `status`; non-integer `page`/`size`; missing/malformed `sales_invoice_id`; malformed `{id}` |
| `403` | caller has no role in `:orgId` |
| `404` | payment / invoice / order not found in `:orgId` |

---

## Authorization

`AuthzHelper.requireOrgAccess(orgId, VIEWER)` on all four — the project-wide read bar. Mutation
routes keep their existing MANAGER/OWNER gates.

---

## Scope

### In
- `RefundRepository.list/count`; `PaymentRepository.findOrderIdsByIds`;
  `CreditNoteRepository.findByInvoiceId/findRefsByIds` (+ `CreditNoteRef`);
  `PaymentAllocationRepository.findByPaymentId`; ordering on `SalesInvoiceRepository.findByOrderId`
  (+ jOOQ impls).
- `RefundService.list` (+ `RefundView`/`RefundPage`), `PaymentDisputeService.get` →
  `PaymentView`, `CreditNoteService.listForInvoice`, `InvoiceAdminService.listForOrder`.
- GET branches in `RefundHandler` and `CreditNoteHandler`; the `invoices` sub-route in
  `SalesOrderHandler`; `RefundResponse` context fields + `created_at`; `PaymentResponse.allocations`;
  `InvoiceCreditNotesResponse`, `OrderInvoicesResponse` DTOs.
- Docs: CLAUDE.md endpoint list.

### Out (deferred)
- **The unfiltered credit-notes list** — the bare `GET /credit-notes` stays a reserved 400.
- **Refund list filters beyond `status`** (by method, by customer, by amount) — add when the queue
  outgrows the status tabs.
- **Allocations on the order-payments embed** — the money story stays lean; the dispute flow goes
  through the payment detail.

---

## Tests

`api/src/test/java/.../refund/MoneyReadsIT.java` (TestContainers Postgres, drives services;
refunds created through the real flows — verify→direct refund, orphan refund — with `created_at`
pinned for deterministic ordering):
- refunds list: PENDING queue oldest-first; executed refund leaves the queue and shows under
  EXECUTED; unfiltered ledger newest-first with totals; page/size clamping; org scoping.
- source context: order-backed rows carry `sales_order_id`+`number`; orphan rows carry neither;
  CreditNote-backed rows carry `sales_invoice_id`+`credit_note_number`.
- credit notes by invoice: oldest-first incl. VOID; `credited_total` = ISSUED+SETTLED regardless
  of the `status` filter; unknown/foreign invoice → 404.
- invoices by order: every status oldest-first with lines + order header; empty for an
  invoice-less order; unknown/foreign order → 404.

`PaymentDisputeIT` (extended): one payment delivered across two invoices reads back with both
allocations (amount + invoice identity, ISSUED); a never-allocated payment reads back with an
empty list; 404 unchanged.

`MoneyReadsHandlerAuthTest` / `OrderDetailReadsHandlerAuthTest` (extended) /
`PaymentDisputeHandlerAuthTest` (updated for `PaymentView`): VIEWER suffices; non-member 403 /
anon 401 (service never called); unknown `status`, non-integer paging, missing/malformed
`sales_invoice_id` → 400 before the service; the bare `GET /credit-notes` stays reserved.
