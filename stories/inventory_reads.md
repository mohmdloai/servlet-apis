# Slice: Inventory reads (stock overview, movement ledger, order/product reservations)

> **Status: SHIPPED (backend).** All four reads are implemented behind the contract below. Backend
> decisions on the two open questions are folded in-line and flagged **[BACKEND DECISION]**. Two
> deviations from the drafted shapes are noted **[DEVIATION]** — both are house-convention effects
> the frontend mapping layer must honour. Verified end-to-end against real PostgreSQL
> (`InventoryReadsServiceIT`) plus handler auth/param tests (`InventoryReadsHandlerAuthTest`).
>
> Endpoints shipped:
> - `GET /api/orgs/{orgId}/inventory?page&size&q&stock&low_lte` — stock overview list
> - `GET /api/orgs/{orgId}/inventory/{productId}/log?page&size` — movement ledger
> - `GET /api/orgs/{orgId}/sales-orders/{id}/reservations` — order holds panel
> - `GET /api/orgs/{orgId}/inventory/{productId}/reservations?status=` — per-product holds
>
> **[BACKEND DECISION — note column]** No `inventory_log.note` column ships in this slice (it never
> existed; adding one is a mutation change out of read-scope). The ledger row therefore carries no
> `note`, and the frontend adjust form shows **no** note field. `impersonator_id` **is** exposed when
> non-null.
>
> **[BACKEND DECISION — reserved_qty invariant]** `reserved_qty == Σ quantity of ACTIVE reservations`
> holds for **order-placed** reservations (placement, expiry, cancel, fulfillment all keep it true — it
> is the ground-truth invariant asserted after every `ExpiryIntegrationTestBase` test). The legacy
> manual `reserve`/`release`/`confirm-sale` actions move `reserved_qty` **without** writing
> `inventory_reservation` rows, so they *can* break it — which is exactly why those actions are
> deliberately **not** surfaced in the admin UI (see "Mutations" below). The per-product read returns
> the itemised order-placed ACTIVE holds; if manual actions were ever used, the aggregate `reserved_qty`
> may exceed their sum, and the UI should fall back to the aggregate.

---

## Goal

A storekeeper/manager reads the whole stock picture end to end, without psql:

1. `GET /api/orgs/{orgId}/inventory?page=&size=&q=&stock=&low_lte=` — the **stock overview list**,
   product-driven (LEFT JOIN from `product`), so untracked products appear as rows with null stock.
2. `GET /api/orgs/{orgId}/inventory/{productId}/log?page=&size=` — the **movement ledger** for one
   product, newest first, each row carrying deltas + running balances + (batch-loaded)
   `sales_order_number`.
3. `GET /api/orgs/{orgId}/sales-orders/{id}/reservations` — the **order's stock holds**, all
   statuses, mirroring `/{id}/payments`.
4. `GET /api/orgs/{orgId}/inventory/{productId}/reservations?status=ACTIVE` — the **per-product
   active holds** with order context (recommended; the stock detail degrades to the aggregate
   `reserved_qty` without it).

All reads: **VIEWER** role, org-scoping is a **404** (not 403), Jackson SNAKE_CASE, ISO-8601 UTC,
`PageResponse` envelope where paginated (`{data, total, page, size}`). Read-only on `rootDsl`, no
lock (mutations re-read `FOR UPDATE`). `page` floors at 0; `size` clamps to `[1, 100]` (default 20).

---

## 1. Stock overview list

```
GET /api/orgs/{orgId}/inventory?page=0&size=20&q=widget&stock=low&low_lte=5
```

Product-driven: the list is **every product** in the org (catalog-shaped), LEFT JOINed to its
`inventory` row. **A product with no inventory row is a row with null stock fields** — this is the
only way the UI can surface the "product created but never tracked" gap and offer *Initialise*.

- **Sort:** `name ASC, product_id ASC` (catalog order — this is not a queue; the queue-vs-ledger
  `created_at` convention does **not** apply).
- **`q=`** — case-insensitive substring match on product `name` OR `sku` (reuse the product list's
  search convention if one exists). Absent/blank ⇒ no filter.
- **`stock=`** filter (tracked rows only unless noted):
  - `out` — `available_qty == 0` **and the product is tracked** (an untracked row is *unknown*, not
    *out* — exclude it).
  - `low` — `available_qty <= low_lte` (tracked only). The backend has **no** low-stock threshold
    column; the client supplies the bound via `low_lte` (integer ≥ 0). If `stock=low` is sent
    without `low_lte`, default the bound to `5`.
  - `tracked` — has an inventory row.
  - `untracked` — no inventory row (the "Not tracked" segment). Ignores `low_lte`.
  - absent ⇒ all products. Unknown value ⇒ **400** (fail loudly, per house convention).
- **`total`** in the envelope must reflect the filtered count (drives the frontend pager).

**Row shape** (`InventoryOverviewRow`):

```json
{
  "product_id": "…uuid…",
  "name": "Blue Widget",
  "sku": "BW-001",
  "base_price": "19.99",
  "tracked": true,
  "stock_qty": 18,
  "reserved_qty": 6,
  "available_qty": 12,
  "version": 4,
  "updated_at": "2026-07-04T10:15:30Z"
}
```

For an **untracked** product: `"tracked": false` and `stock_qty` / `reserved_qty` /
`available_qty` / `version` / `updated_at` are **`null`**. **[DEVIATION]** The project-wide
ObjectMapper omits null fields, so an untracked row serializes as `{"tracked": false, …}` with the
stock keys **absent** (not present-as-`null`). This is fine — `tracked` is the explicit signal and
the frontend must key off it, never off a zero or a present/absent key. `base_price` is the
product's `base_price` (nullable if the product allows it); rendered with `<Money>` client-side.

---

## 2. Movement ledger for one product

```
GET /api/orgs/{orgId}/inventory/{productId}/log?page=0&size=20
```

The append-only `inventory_log` for one product, **`created_at DESC, id DESC`** (audit ledger,
newest first). `PageResponse` envelope. **[BACKEND DECISION]** 404 iff the **product** is not in
`:orgId` (checked against `product`, not `inventory`). A tracked-but-never-moved product **and** an
untracked-but-existing product both return empty `data` (not 404) — 404 is reserved for a product id
that genuinely isn't in the org.

**Row shape** (`InventoryLogRow`) — deltas **and** running balances, so cards render honest
absolute numbers:

```json
{
  "id": 4021,
  "stock_delta": -2,
  "reserved_delta": -2,
  "stock_after": 16,
  "reserved_after": 4,
  "reason": "SOLD",
  "order_id": "…uuid…",
  "sales_order_number": "SO-2026-000123",
  "actor_id": "…",
  "actor_type": "USER",
  "created_at": "2026-07-04T09:00:00Z"
}
```

- `reason` ∈ the 7 `StockReason` values: `RESERVED`, `RELEASED`, `SOLD`, `RESTOCK`, `ADJUSTMENT`,
  `RETURNED` (dead enum — never written, but map defensively), `RESTOCKED_FAILED_FULFILLMENT`.
- `order_id` is present only for order-linked reasons; **`null`** for `RESTOCK` / `ADJUSTMENT`.
- **`sales_order_number`** is **batch-loaded** (one query per page keyed on the page's distinct
  `order_id`s, the `list_order_payments` / `fulfillment_reads` pattern) — `null` when `order_id` is
  null. This lets a card say "−2 · Sold · SO-2026-000123" without a request per row.
- If `inventory_log` gains an `impersonator_id` (it exists on the domain model), include it when
  non-null; the frontend will render an "on behalf of" note but does not require it.
- **No free-text note column exists.** **[BACKEND DECISION]** None is added this slice, so
  `ADJUSTMENT` rows stay anonymous beyond actor + delta and the frontend adjust form shows **no**
  note field. (Adding a note is a future mutation-side change: `inventory_log.note` column + an
  `adjust` request field + this read field — out of scope for a read slice.)

---

## 3. Order reservations (order detail holds panel)

```
GET /api/orgs/{orgId}/sales-orders/{id}/reservations
```

Mirrors `/{id}/payments` / `/{id}/fulfillments` / `/{id}/invoices`: the order header
(`OrderSummary` — `id`, `order_number`, `status`, `grand_total`, `prepaid_amount`) + **every**
reservation for the order, **all statuses** (`ACTIVE` / `CONSUMED` / `RELEASED` are all part of the
story). **No pagination** (bounded by line count). 404 if the order isn't in `:orgId`.

**Envelope** (`OrderReservationsResponse`, mirroring `OrderPaymentsResponse`):

```json
{
  "order": { "id": "…", "order_number": "SO-2026-000123", "status": "FULFILLING",
             "grand_total": "59.97", "prepaid_amount": "59.97" },
  "data": [
    {
      "id": "…uuid…",
      "product_id": "…uuid…",
      "product_name": "Blue Widget",
      "sales_order_line_id": "…uuid…",
      "quantity": 2,
      "status": "CONSUMED",
      "expires_at": "2026-07-05T09:00:00Z",
      "consumed_at": "2026-07-04T09:00:00Z",
      "released_at": null,
      "released_reason": null,
      "created_at": "2026-07-04T08:00:00Z"
    }
  ]
}
```

- `product_name` batch-loaded from `product` (one query keyed on the reservations' distinct
  `product_id`s).
- `status` ∈ `ACTIVE` | `CONSUMED` | `RELEASED`. For terminal rows: `consumed_at` set on CONSUMED;
  `released_at` + `released_reason` set on RELEASED (`released_reason` ∈ `CANCELLED` | `EXPIRED` |
  `ADMIN` | `FULFILLMENT_CANCELLED`). Non-applicable timestamps are `null`.
- `expires_at` is the payment-hold TTL; meaningful while ACTIVE, retained on terminal rows as
  historical.

---

## 4. Per-product active reservations (recommended, not blocking)

```
GET /api/orgs/{orgId}/inventory/{productId}/reservations?status=ACTIVE
```

"What is holding this stock right now." Same reservation row shape as §3 **plus** order context so
the stock detail can itemise `reserved_qty` and deep-link each hold to its order:

```json
{
  "data": [
    {
      "id": "…", "sales_order_id": "…uuid…", "sales_order_number": "SO-2026-000123",
      "sales_order_line_id": "…", "quantity": 4, "status": "ACTIVE",
      "expires_at": "2026-07-05T09:00:00Z", "created_at": "2026-07-04T08:00:00Z"
    }
  ]
}
```

- `sales_order_id` + `sales_order_number` joined through `sales_order_line` → `sales_order`.
- `status=` filters (default `ACTIVE`; accept `ACTIVE`/`CONSUMED`/`RELEASED`; unknown ⇒ 400). No
  pagination.
- **Invariant the UI leans on:** `reserved_qty == Σ quantity of ACTIVE reservations for the
  product`. **[BACKEND DECISION]** Guaranteed for order-placed holds; the legacy manual
  `reserve`/`release`/`confirm-sale` actions can break it (they don't write reservation rows) and are
  deliberately **not** in the admin UI (see "Mutations"). If itemised ACTIVE quantities ever sum to
  less than `reserved_qty` (a sign manual actions were used), fall back to the aggregate.

Without this endpoint the slice still ships: the stock detail shows the aggregate `reserved_qty`
line ("held by open orders") linking nowhere.

---

## Mutations — already shipped, no change requested

The frontend Story 07 write surface uses the **existing** endpoints unchanged and is **not**
requesting new mutations:

- `POST /inventory {product_id, stock_qty}` (initialise, STAFF) — 400 `"stockQty must be >= 0"`;
  409 `"Inventory already exists for product: <id>"`.
- `POST /inventory/{productId}/restock {qty}` (STAFF) — 400 `"restock qty must be > 0"`.
- `POST /inventory/{productId}/adjust {qty}` (STAFF) — signed delta; a decrement below
  `reserved_qty`/0 hits the DB guard (`stock_qty >= reserved_qty >= 0`) → 409/500-family. The
  frontend pre-floors the decrement at `available_qty`, so this should not normally fire.
- `DELETE /inventory/{productId}` (MANAGER) — 204.
- Version-conflict on any mutation ⇒ **409**; frontend treats it as "stock changed under you —
  refresh, retry".

The manual `reserve` / `release` / `confirm-sale` actions are **deliberately not surfaced** in the
admin UI (they move `reserved_qty` without touching `inventory_reservation` rows, breaking the §4
invariant). No backend change needed — noting it so the reads above are understood as the whole
admin contract.

---

## Fixtures the frontend e2e harness will mirror (please keep parity)

So the hermetic `apps/admin/e2e/mock-api.mjs` matches real behaviour, the `/__reset` seed will
cover: a **healthy** tracked product, an **out-of-stock** one (`available == 0`), a **reserved-heavy**
one (`reserved` near `stock`), an **untracked** one (no inventory row, appears in the list with null
stock + `tracked:false`), and one product whose ledger spans **all six written reasons** (`RESERVED`,
`RELEASED`, `SOLD`, `RESTOCK`, `ADJUSTMENT`, `RESTOCKED_FAILED_FULFILLMENT`) with correct
`stock_after`/`reserved_after` progression and `sales_order_number` populated on the order-linked
rows. If real backend semantics differ from any assumption above, the mock will be corrected to
match the backend — please flag deviations.

---

## Acceptance (what "shipped" means for this dependency)

- [x] `GET /inventory` returns products LEFT JOINed to inventory, `name ASC`, with `tracked` flag +
      null stock for untracked; `q`, `stock` (out/low/tracked/untracked), `low_lte` all honoured;
      unknown `stock` ⇒ 400; `total` reflects the filter.
- [x] `GET /inventory/{productId}/log` returns `created_at DESC, id DESC`, deltas + running
      balances + batch-loaded `sales_order_number`; 404 iff product not in org (untracked → empty).
- [x] `GET /sales-orders/{id}/reservations` returns order header + all-status reservations with
      product names; 404 org-scoped.
- [x] `GET /inventory/{productId}/reservations?status=` returns holds with order context
      (default ACTIVE; unknown status ⇒ 400); the `reserved_qty == Σ ACTIVE` invariant is
      guaranteed for order-placed holds (see decision above).
- [x] All VIEWER, snake_case, ISO-8601 UTC, `PageResponse` where paginated, 404 org-scoping.
- [x] **note** decision: no column added; adjust form shows no note field.
