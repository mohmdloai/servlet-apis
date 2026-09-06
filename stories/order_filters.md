# Slice: Order filters + sorting (`?from/?to`, `?balance=`, `?min/?max`, `?sort=` + a money summary on the orders worklist)

> The admin Orders page has a search field (`order_search.md`), five status tabs
> (`order_status_counts.md`) and a channel chip row (`counter_return.md`) — and nothing else. The
> worklist read ([`SalesOrderHandler.doGetOrList`](../api/src/main/java/com/loai/inventory/api/servlet/handler/SalesOrderHandler.java))
> takes `status`, `channel`, `q`, `page` and `size`. A merchant asking "what came in this week?",
> "which orders still owe me?" or "the biggest orders first" has no door; the frontend's Filters
> sheet was drawn (`design/orders-search-filters.html`) with two of its three dimensions ghosted,
> waiting for exactly the parameters below. Invoices got its filters in `invoice_filters.md` and
> the stock overview its sort in `inventory_filters.md`; this slice gives Orders the same
> vocabulary. Frontend pair: `frontst` story 145 (branch `143_feat/orders-filters`). Branch
> `183_feat/order-filters`, **no migration**.

---

## Today (the gap)

- **Five parameters.** `SalesOrderService.list(orgId, status, channel, q, page, size)` — the rows
  and a separate `count`, no window, no balance, no bounds, and the ORDER BY is decided by
  `status` alone (queue oldest-first / ledger newest-first).
- **The money question is a client sum of one page.** "How much is still owed across these
  orders?" can only be answered for the rows on screen.
- The data is on the row: `created_at` (NOT NULL, inside `sales_order_org_status_idx`),
  `grand_total`, `prepaid_amount` (maintained by `updatePaymentState`, so the balance is
  `grand_total − prepaid_amount` with no join), `expires_at` (the payment-hold deadline, nulled on
  `markPaid`).

## Goal

**One read answers "which orders, in what order, and how much?"** — a window, a balance state, an
amount band and an explicit sort composing with the tab, the channel and the search, with a
`total` that equals the rows and a `summary` that adds the same rows up.

## Design

### Contract

`GET /api/orgs/{orgId}/sales-orders?status=&channel=&q=&from=&to=&balance=&min=&max=&sort=&page=&size=`
(VIEWER). Every new parameter is optional; blank is absent.

| param | meaning | can 400 |
| --- | --- | --- |
| `from` / `to` | half-open `[from, to)` on **`created_at`**, ISO-8601 date-times (the `/reports` convention). Either side may be open. | not a date-time; `from >= to` |
| `balance` | `owing` → `prepaid_amount < grand_total`; `settled` → `=`; `overpaid` → `>`. The row's own money meter as a filter — pure arithmetic, like the invoice `paid=`: it composes with `status` and never guesses at it (an EXPIRED hold with nothing paid reads as owing, exactly as the row's balance cell shows it). | any other value |
| `min` / `max` | inclusive bounds on `grand_total`, plain decimals (EGP). | not a number; negative; `min > max` |
| `sort` | `newest` (`created_at DESC`), `oldest` (`ASC`), `total` (`grand_total DESC`), `balance` (`grand_total − prepaid_amount DESC`), `expiring` (`expires_at ASC NULLS LAST` — the holds about to lapse first; paid orders, whose deadline is cleared, sink to the end). Every sort is tie-broken on `(created_at DESC, id DESC)` (`oldest`: ASC), so a page boundary never shuffles equal keys. | any other value |

**Ordering without `sort` is unchanged**: a status = queue `created_at ASC, id ASC`; none = ledger
`created_at DESC, id DESC`. The window, balance and amount narrow, never reorder. An explicit
`sort` overrides the queue-vs-ledger rule on any tab — that is what it is for.

**Why `created_at` and not `placed_at`.** `placed_at` is stamped in the same transaction that
creates every placed order (`markPendingPayment` / `markPaid` from DRAFT), so the two never
disagree for a listed row; `created_at` is the one that is NOT NULL, is what the ledger already
sorts by and the row shows, and is the third column of the worklist index.

**Response**: the `PageResponse` envelope plus `summary`:

```json
{ "data": [ … ], "total": 12, "page": 0, "size": 20,
  "summary": { "outstanding": 1240.00, "value": 18900.00 } }
```

- `outstanding` = Σ `grand_total − prepaid_amount` over the rows of the whole filtered set that
  are **live and still owing** — status in PENDING_PAYMENT, PAID, FULFILLING, FULFILLED, CLOSED
  with a positive balance. An overpaid row adds nothing (its excess is a refund, not debt).
- `value` = Σ `grand_total` over the **live** rows — a DRAFT, CANCELLED or EXPIRED order's amount
  is not in force, so it never counts (the invoice summary's VOID rule).
- Both always present, `0.00` for an empty set, computed in the **same query** as `total`
  (`SalesOrderRepository.stats`) with the **same predicate** as the rows. The list no longer runs a
  separate `count`; when `stats.total()` is 0 the rows query is skipped.

### The predicate (`SalesOrderRepositoryImpl.listConditions(orgId, filter)`)

One definition for `list`, `stats` and `countByStatus`, so none can disagree. The filter is one
value, `OrderListFilter` (domain): `status, channel, q, createdFrom, createdTo, balance, minTotal,
maxTotal, sort`, with `of(status, channel, q)` / `none()` so the pre-slice overloads
(`list(orgId, status, channel, q, page, size)` and friends, used by `OrderSearchIT`,
`OrderStatusCountsIT`, `OrderChannelFilterIT`) delegate unchanged. The `q` legs are
`order_search.md`'s, untouched.

The three worklist mappers now share one parser (`api/mapper/QueryParams`: `parseTs`,
`parseMoney`, `parseEnum`) — `InvoiceMapper` and `InventoryMapper` lost their private copies;
their messages are byte-identical.

### Indexes

No new index. `sales_order_org_status_idx (org_id, status, created_at, id)` (V66) serves the
status-tab window and both default orders; the ledger-wide window and the amount/balance/expiry
sorts are org-scoped scans of the tenant's own ledger (the `order_search.md` measurement). A
dedicated `(org_id, created_at, id)` or an expression index on the balance is deferred until an
EXPLAIN on the perf seed says so (`docs/query-index-audit.md` discipline).

## Errors

- Unknown `status` / `channel` → 400 (unchanged). `balance` outside `owing|settled|overpaid` →
  400 naming the three; `sort` outside the five → 400 naming them.
- `from`/`to` not ISO-8601 date-times (a bare `2026-08-01` is refused, as on `/reports`) → 400;
  `from >= to` → 400.
- `min`/`max` not numbers, negative, or `min > max` → 400.
- `q` cannot fail; no match is `data: [], total: 0, summary: {0.00, 0.00}`.

## Tests

- **`OrderFiltersIT`** (repository + mapper against Testcontainers Postgres 17, rows seeded
  directly): the window is half-open on `created_at` with either side open; `balance` reads the
  row's meter (an EXPIRED hold is owing, a 1620-on-1560 row is overpaid) and composes with the
  tab; the amount band is inclusive at both ends; each explicit sort overrides the queue rule with
  the stated tie-break (`expiring` puts equal deadlines newest-first and the paid row last); the
  stats share the predicate (the count includes non-live rows, the money excludes them, an empty
  set is `0.00` scale 2); the dimensions compose with `channel` and `q` and are org-scoped; the
  mapper parses every dimension (trimmed, case-folded) and each 400 names its parameter.
- **`OrderLookupHandlerAuthTest`**: the nine parameters reach the service as one
  `OrderListFilter`; the envelope carries `summary`; bare date, prose date, `balance=half`,
  `sort=price`, `min=abc`, `max=-1`, `channel=KIOSK`, `from > to`, `min > max` are 400s with the
  service never called.
- **`OrderSearchIT`, `OrderStatusCountsIT`, `OrderChannelFilterIT`** unchanged in substance (the
  overloads delegate to the filter); **`InvoiceFiltersIT`, `InventoryFiltersIT`,
  `InvoiceHandlerAuthTest`, `InventoryReadsHandlerAuthTest`** green on the shared parser.

## Out of scope

- **A payment-method filter.** The provider lives on `payment_transaction`, not the order row —
  an `EXISTS` over two tables with no index; the Money worklist already filters by provider.
- **A courier / shipment-state filter.** Same shape (`fulfillment.carrier`); the Fulfillments
  worklist owns it.
- **A CRM customer name on list rows.** The list DTO carries the walk-in `customer_name` only
  (`order_search.md`); unchanged here.
