# Slice: Look up an order by number (G4 — pre-flight for the manual money path)

> The manual reconcile path consumes an `order_number` the customer wrote in a transfer note:
> `POST /payment-transactions` matches on it, and `POST /payment-transactions/{id}/resolve`
> accepts `{order_number}` outright. But the admin types that number **blind** — there is no way
> to read the order first, see its status and outstanding balance, and predict whether the
> record/resolve call will land MATCHED, UNDERPAID, OVERPAID, or bounce off a non-PENDING_PAYMENT
> order. `SalesOrderHandler` is POST-only today; `SalesOrderRepository.findByOrderNumber(orgId,
> orderNumber)` already exists and is only reachable through the money-writing endpoints.

---

## Goal

An org member previews an order by its human-readable number before committing money against it:

`GET /api/orgs/{orgId}/sales-orders?order_number=SO-2026-000123`

Done means: the resolve dialog can show "SO-2026-000123 — PENDING_PAYMENT, grand total 1000.00,
prepaid 400.00 (600.00 outstanding), expires 2026-07-05T10:00Z" **before** the admin records a
transaction, and a typo'd number is a clean 404 instead of a surprise ORPHAN on the books.

---

## Semantics

- `order_number` is an **exact, case-sensitive match** (numbers are system-generated,
  `SO-`-prefixed, unique per org — the repository finder is `Optional`-returning). Leading and
  trailing whitespace is trimmed (it arrives by copy-paste from transfer notes).
- Exactly one query param is meaningful; the response is a **single object, not a list** — this
  is a lookup, not a search.
- A bare `GET /sales-orders` (no `order_number`) is a **400** with a message saying the filter is
  required — the unfiltered Sales Order list (status tabs, paging) is its own future slice, and
  this deliberately reserves the route for it without squatting on its semantics.
- Response is the full existing `SalesOrderResponse` (status, channel, totals, `prepaid_amount`,
  `expires_at`, lines) — the same shape placement returns, via
  `SalesOrderRepository.findLinesByOrderId`. Outstanding = `grand_total − prepaid_amount` stays
  client-side arithmetic.
- Read-only on `rootDsl`, no lock — the money endpoints re-read `FOR UPDATE`
  (`findByOrderNumberForUpdate`) inside their own transactions, so a stale preview can never
  corrupt a write; it can at worst show a number that a concurrent reconcile just changed.

### Service

`SalesOrderService.getByNumber(orgId, orderNumber)` → order + lines; `NotFoundException` if
absent.

---

## API contract

```
GET /api/orgs/{orgId}/sales-orders?order_number=SO-2026-000123
```

`200 OK` — the existing `SalesOrderResponse` shape:
```json
{
  "id": "<uuid>",
  "org_id": "<uuid>",
  "customer_id": "<uuid>",
  "order_number": "SO-2026-000123",
  "channel": "ONLINE",
  "status": "PENDING_PAYMENT",
  "grand_total": "1000.00",
  "currency": "EGP",
  "prepaid_amount": "400.00",
  "placed_at": "2026-07-01T10:00:00Z",
  "expires_at": "2026-07-05T10:00:00Z",
  "lines": [ { "product_id": "<uuid>", "qty": 2, "unit_price": "500.00" } ]
}
```

### Errors
| Status | Cause |
|---|---|
| `400` | missing/blank `order_number` |
| `403` | caller has no role in `:orgId` |
| `404` | no order with that number in `:orgId` |

---

## Authorization

`AuthzHelper.requireOrgAccess(orgId, VIEWER)` — the project-wide read bar. (The money endpoints
this previews for stay MANAGER; previewing is visibility, recording money is authority.)

---

## Scope

### In
- `SalesOrderService.getByNumber(...)` (reuses `findByOrderNumber` + `findLinesByOrderId` — **no
  repository changes**).
- `GET` branch in `SalesOrderHandler` (bare path + required param); 405 guard + javadoc updated
  (it currently declares GET unsupported).
- Docs: CLAUDE.md endpoint list.

### Out (deferred)
- **The Sales Order list** (`GET /sales-orders?status=&page=&size=`) — the worklist screen slice;
  this story leaves the bare route 400ing so the list can claim it compatibly.
- **`GET /sales-orders/{id}`** — the detail-screen slice (same reasoning as
  [`list_order_payments.md`](list_order_payments.md) §Out).
- **Fuzzy/prefix search** — exact match only; "find the order when the customer mangled the
  number" is a search-screen feature, not a pre-flight.

---

## Tests

`api/src/test/java/.../order/OrderLookupByNumberIT.java` (TestContainers Postgres, drives
services):
- placed online order found by its number → full shape incl. lines, `expires_at`,
  `prepaid_amount`.
- after an UNDERPAID reconcile, the preview shows the accumulated `prepaid_amount` (the
  pre-flight actually predicts the next reconcile outcome).
- whitespace-padded input matches; wrong-case input does not.
- number belonging to another org → 404 (scoping, not 403).
- missing / blank `order_number` → 400.
- VIEWER reads fine; non-member → 403.
