# Slice: Fulfillment reads (the packing queue, the shipment story, and the order-by-id read)

> Until now the outbound flow was write-only: `FulfillmentHandler` answered 405 to every GET, no
> order response embedded fulfillments, and the serialized `FulfillmentResponse` omitted
> `delivered_at`, `cancelled_at`, `resolution`, and `replaces_fulfillment_id` — a FAILED
> fulfillment's disposition could not be read back at all. Even `GET /sales-orders/{id}` did not
> exist (only the `?order_number=` lookup and `/{id}/payments`). The fulfillment frontend
> (packing/shipping queue, order shipment panel) had nothing to load from. Frontend driver: the
> fulfillment worklist + Sales Order detail screens in
> [`docs/frontend-architecture.md`](../docs/frontend-architecture.md).

---

## Goal

An org member reads the outbound flow end to end:

1. `GET /api/orgs/{orgId}/fulfillments?status=&page=&size=` — the packing/shipping queue
   (filtered, oldest first) / fulfillment ledger (unfiltered, newest first).
2. `GET /api/orgs/{orgId}/fulfillments/{id}` — one fulfillment + lines, with **every** lifecycle
   field: all timestamps, `failed_reason`, `returned_at`, `resolution`, `replaces_fulfillment_id`.
3. `GET /api/orgs/{orgId}/sales-orders/{id}` — one order + lines by stable id (the detail read the
   worklist row navigates to; same shape as the `?order_number=` lookup).
4. `GET /api/orgs/{orgId}/sales-orders/{id}/fulfillments` — the order's shipment story, mirroring
   `/{id}/payments`.

Done means: a FAILED fulfillment's full disposition (failed → returned → REPLACED, with the
replacement's lineage) is reconstructible from reads alone — without psql access, and without
scraping cached mutation responses.

---

## Why this slice is small

| Piece | Already existed | Added here |
|---|---|---|
| Detail row | `FulfillmentRepository.findById` / `findLinesByFulfillmentId` | `FulfillmentService.get` wrapping them (404 semantics) |
| By-order rows | `FulfillmentRepository.findByOrderId` (`created_at ASC`) | `FulfillmentService.listForOrder` + order header (mirrors `PaymentService.listForOrder`) |
| Queue/ledger list | the queue-vs-ledger split from `PaymentTransactionRepository.list` | `FulfillmentRepository.list/count` (status filter) + batch `findLinesByFulfillmentIds` |
| Order by id | `SalesOrderService.findPlaced` (the magic-link loader) | `SalesOrderService.getById` — `findPlaced` with 404 semantics |
| Row shape | `FulfillmentResponse` | + `delivered_at`, `cancelled_at`, `resolution`, `replaces_fulfillment_id` (fields the domain model always carried) |
| Envelopes | `PageResponse`, the `OrderSummary` header shape | reused; thin `OrderFulfillmentsResponse` mirrors `OrderPaymentsResponse` |
| Routing | `FulfillmentHandler` (POST-only), `SalesOrderHandler` GET branches | GET branches; 405 guards + javadocs updated |

No migration. The four omitted response fields were already persisted and rehydrated — the gap was
purely serialization.

---

## Semantics

- **Queue vs ledger** (same convention as `GET /payment-transactions`): a `status` filter makes it
  a worklist — `created_at ASC, id ASC` (FIFO: pack the oldest first); no filter makes it the audit
  ledger — `created_at DESC, id DESC`. `page` floors at 0, `size` clamps to `[1, 100]`
  (default 20), `PageResponse` envelope. Unknown `status` → 400 (fail loudly).
- Every list row carries its lines (the packing queue is unusable without them); lines are
  batch-loaded — one query per page, not per row.
- Every read row also carries `sales_order_number` (batch-loaded the same way), so a queue card can
  name its order without a per-row lookup.
- The detail and by-order reads carry `fulfillment_value` — the grand total the invoice for this
  fulfillment's lines would carry at delivery, computed by the **same helper** `refundFailed` sizes
  its refund (and the OWNER-approval threshold check) by, so a client-side threshold pre-warning can
  never disagree with the guard. (The actual executed refund may still be capped by the order's
  remaining unallocated prepayment.) The queue list omits it — pricing a page would need every
  parent order's lines, and the refund flow lives on the detail screen.
- The by-order list returns **all** fulfillments regardless of status, oldest first — CANCELLED and
  FAILED are part of the story — plus the order header (`OrderSummary`) so the panel renders
  standalone. No pagination: fulfillment count is bounded by the order's line count.
- `GET /sales-orders/{id}` returns the same full `SalesOrderResponse` as the `?order_number=`
  lookup — one shape for the order detail screen regardless of how it was reached.
- Reads are read-only on `rootDsl`, no lock — mutations re-read `FOR UPDATE` inside their own
  transactions, so a stale read can never corrupt a write.
- Org-scoping is a 404, not a 403 — another org's rows are invisible (shared-schema convention).

### Service

- `FulfillmentService.get(orgId, id)` → `FulfillmentView`; 404 if not in `:orgId`.
- `FulfillmentService.list(orgId, status, page, size)` → `FulfillmentPage(items, total)`.
- `FulfillmentService.listForOrder(orgId, salesOrderId)` → `OrderFulfillments(order, fulfillments)`;
  404 if the order isn't in `:orgId`.
- `SalesOrderService.getById(orgId, orderId)` → `Placed`; 404 semantics; null id → 400.

---

## API contract

```
GET /api/orgs/{orgId}/fulfillments?status=PENDING&page=0&size=20
```

`200 OK` (`PageResponse` envelope; each row the full `FulfillmentResponse`):
```json
{
  "data": [
    {
      "id": "<uuid>",
      "org_id": "<uuid>",
      "sales_order_id": "<uuid>",
      "sales_order_number": "SO-2026-000123",
      "status": "FAILED",
      "carrier": "Bosta",
      "tracking_number": "TRK-1",
      "shipped_at": "2026-07-01T10:00:00Z",
      "failed_at": "2026-07-03T08:00:00Z",
      "failed_reason": "package lost",
      "returned_at": "2026-07-04T09:00:00Z",
      "resolution": "REPLACED",
      "created_at": "2026-07-01T09:00:00Z",
      "updated_at": "2026-07-04T09:00:00Z",
      "lines": [
        { "id": "<uuid>", "sales_order_line_id": "<uuid>", "quantity": 2 }
      ]
    }
  ],
  "total": 1,
  "page": 0,
  "size": 20
}
```
(A replacement fulfillment additionally carries `replaces_fulfillment_id`; a DELIVERED one
`delivered_at`; a CANCELLED one `cancelled_at`. Null fields are omitted, as everywhere.)

```
GET /api/orgs/{orgId}/fulfillments/{id}          → the same row shape, single object,
                                                   plus "fulfillment_value": 30.00
GET /api/orgs/{orgId}/sales-orders/{id}          → the existing SalesOrderResponse
GET /api/orgs/{orgId}/sales-orders/{id}/fulfillments
```

`200 OK` (mirrors `OrderPaymentsResponse`):
```json
{
  "order": {
    "id": "<uuid>",
    "order_number": "SO-2026-000123",
    "status": "FULFILLING",
    "grand_total": "500.00",
    "prepaid_amount": "500.00"
  },
  "data": [ { "…": "FulfillmentResponse rows, created_at ASC" } ]
}
```

### Errors
| Status | Cause |
|---|---|
| `400` | malformed `{id}`; unknown `status`; non-integer `page`/`size` |
| `403` | caller has no role in `:orgId` |
| `404` | fulfillment / order not found in `:orgId` |

---

## Authorization

`AuthzHelper.requireOrgAccess(orgId, VIEWER)` on all four — the project-wide read bar. The POST
routes keep their existing STAFF/MANAGER gates.

---

## Scope

### In
- `FulfillmentRepository.list/count/findLinesByFulfillmentIds` (+ jOOQ impls).
- `FulfillmentService.get/list/listForOrder` + `FulfillmentPage`/`OrderFulfillments` records;
  `SalesOrderService.getById`.
- GET branches in `FulfillmentHandler` and `SalesOrderHandler`; `OrderFulfillmentsResponse` DTO;
  the four missing `FulfillmentResponse` fields, plus the read decorations `sales_order_number`
  (all reads; `SalesOrderRepository.findOrderNumbersByIds` batch projection) and
  `fulfillment_value` (detail + by-order; the extracted `fulfillmentValueOf` helper shared with
  `refundFailed`).
- Docs: CLAUDE.md endpoint list.

### Out (deferred)
- **The unfiltered Sales Order list** (`GET /sales-orders` without `order_number`) — still the
  reserved 400; the order worklist slice owns it.
- **Embedding fulfillments in `SalesOrderResponse`** — the story is a sub-resource, mirroring
  payments; the detail screen composes the two calls.
- **Carrier/tracking search filters on the queue** — add when the queue outgrows status tabs.

---

## Tests

`api/src/test/java/.../fulfillment/FulfillmentReadIT.java` (TestContainers Postgres, drives
services):
- detail: full shape with lines; DELIVERED carries `delivered_at`; failed→returned→replaced
  exposes `failed_reason`, `returned_at`, `resolution=REPLACED` and the replacement's
  `replaces_fulfillment_id`; unknown/foreign id → 404.
- list: status filter (queue ASC), unfiltered ledger DESC with batch-loaded lines, pagination
  tiling without overlap, page/size clamping, org scoping; each row carries its own order's
  `sales_order_number` (and no `fulfillment_value`).
- value: the detail's `fulfillment_value` is the invoice arithmetic (subtotal + tax), and —
  guard-parity end-to-end — equals the PENDING refund total `refundFailed` actually creates.
- by-order: every status oldest-first + FULFILLING header; empty `data` for a fulfillment-less
  order; unknown/foreign order → 404.

`OrderLookupByNumberIT` (extended): `getById` same shape as the number lookup; unknown/foreign
→ 404; null id → 400.

`FulfillmentReadHandlerAuthTest` / `OrderDetailReadsHandlerAuthTest`: VIEWER suffices; non-member
403 / anon 401 (service never called); unknown `status`, non-integer paging, malformed id → 400
before the service.
