# Slice: Reporting & analytics reads (revenue series, sales by channel, top products, AR aging, inventory valuation)

> The platform's **#1 read gap**: beyond the four-count health rollup
> ([`OrgHealthRepositoryImpl.health`](../repository/src/main/java/com/loai/inventory/repository/OrgHealthRepositoryImpl.java) —
> `{member_count, pending_payment_orders, open_disputes, unallocated_payments}`, surfaced at
> `GET /api/orgs/{orgId}/health` and mirrored on the admin plane) there are **zero aggregate
> endpoints**. The only other `GROUP BY` in the whole repository layer is the number-sequence
> reconciliation healer — an ops repair, not reporting. Every figure a dashboard chart would want
> (revenue over time, sales by channel, top products, AR aging, refund rate, stock valuation)
> exists **only as raw list rows** behind paginated `PageResponse` endpoints, so a chart today
> means the browser paging `/payments` or `/invoices` to exhaustion and summing in JS. This slice
> adds the small set of read-only aggregates that make the tenant dashboard chartable — the thing
> that sells the product in a demo. The data model already supports every metric (V21 even ships a
> partial index for exactly the AR-aging query); the one caveat is that inventory valuation can
> only be **retail** valuation, because `product` has no cost column (V1: `base_price` only).

---

## Today (the gaps)

- **G1 — no revenue/refund time series.** Money facts are fully recorded — `payment.amount` +
  `received_at` (V23), `sales_invoice.grand_total` + `issued_at` (V21), `refund.amount` +
  `executed_at` + `status` (V26) — but nothing aggregates them. A revenue chart or a refund-rate
  figure requires client-side pagination over three ledgers.
- **G2 — no sales-by-period/channel aggregate.** `sales_order` carries `channel`
  (`ONLINE|IN_STORE|PHONE`, V10), `grand_total`, `status`, and `placed_at` (V17), but the only
  order reads are the by-number lookup and the by-id detail — not even an unfiltered list, let
  alone a grouped one.
- **G3 — no top-products read.** `sales_order_line` snapshots `quantity`, `unit_price`,
  `line_total` per product (V18); nothing ranks them. "What sells?" is unanswerable without
  paging every order.
- **G4 — AR is a queue, not an aging.** `GET /invoices?status=ISSUED` gives the awaiting-payment
  worklist and a `total` **count**, but no outstanding **amount** and no age buckets — even though
  V21 already ships the exact partial index (`idx_invoice_unpaid` on `(org_id, issued_at) WHERE
  status='ISSUED' AND paid_amount < grand_total`) this query wants.
- **G5 — no inventory valuation.** `inventory.stock_qty` (V7) × `product.base_price` (V1) is one
  join away, but the inventory list only pages rows.

---

## Goal

One new org-scoped, read-only handler — `/api/orgs/{orgId}/reports/*` — with five GET endpoints
that answer the dashboard's aggregate questions **server-side, in one round-trip each**:

1. `GET /reports/revenue` — invoiced / collected / refunded per time bucket (refund **rate** is a
   client-side division of two returned columns — deliberately not a sixth endpoint).
2. `GET /reports/sales` — order count + gross per bucket × channel.
3. `GET /reports/top-products` — ranked products by revenue or quantity over a window.
4. `GET /reports/ar-aging` — outstanding invoice money in age buckets, riding `idx_invoice_unpaid`.
5. `GET /reports/inventory-valuation` — point-in-time stock units + **retail** value.

Done means: every metric in the list renders from a single GET; no client ever pages a raw list to
compute an aggregate; all five are org-isolated, VIEWER-gated, and covered by ITs that assert the
sums against seeded rows.

---

## Design

### Shape of the slice (follows the `OrgHealth` precedent end-to-end)

- **Routing:** `OrgServlet` dispatches `/{orgId}/reports/<report>` to a new
  [`ReportsHandler`](../api/src/main/java/com/loai/inventory/api/servlet/handler) (sibling of
  `HealthHandler`). GET only; any mutating verb → **405** (append-only convention, like
  `/api/admin/audit`).
- **Layers:** `ReportRepository` interface in **domain** (returns small result records),
  `ReportRepositoryImpl` in **repository** (jOOQ aggregate queries — `DSL.sum`, `count`,
  `trunc`/`date_trunc` grouping), `ReportService` in **service** (parameter validation only — no
  business rules live here), wired in `AppConfig`.
- **Authorization: VIEWER.** Every money read at org scope is VIEWER (`/payments`, `/refunds`,
  `/invoices`, `/payment-transactions`); these aggregates reveal nothing a VIEWER cannot already
  compute by paging those same lists, so gating higher would be security theater. (`/health` is
  MANAGER only because it carries a roster-derived count — no report here touches the roster.)
  `AuthzHelper.requireOrgAccess(req, orgId, VIEWER)` gives suspension enforcement + platform-ADMIN
  bypass for free.
- **Multi-tenancy:** every query filters `org_id = :orgId` on the driving table (all six tables
  involved carry `org_id NOT NULL` — V15/V17/V21/V23/V26). An org-isolation IT is mandatory
  (see §Tests): two seeded orgs, org A's report never moves when org B's data changes.
- **Money:** decimal EGP `NUMERIC(14,2)` serialized exactly as every existing DTO (Jackson,
  snake_case). The frontend converts to piastres at its DTO boundary as usual.

### Common windowed-query parameters (`revenue`, `sales`, `top-products`)

- `from`, `to` — ISO-8601 date-times; the window is **half-open `[from, to)`**. Defaults:
  `to` = now, `from` = `to` − 30 days. `from >= to` → 400. Window capped at **366 days** → 400
  above it (an unbounded scan is a self-inflicted outage, and a year is the longest demo-able
  range; longer horizons are a later rollup-table slice).
- `bucket` — `day | week | month` (default `day`; unknown → 400, same convention as every enum
  filter). Buckets are `date_trunc(bucket, ts)` in **UTC** — a deliberate v1 simplification
  (§Out of scope): org-local (Africa/Cairo) day boundaries shift a day's edge by 2–3 hours,
  acceptable for v1 charts, revisited if it ever misleads.
- **Series are sparse**: a bucket with no rows is **omitted** (no `generate_series` zero-fill),
  rows ordered `period ASC`. Zero-filling is a presentation concern the client owns — spelled out
  in the response contract so no consumer assumes continuity.

### G1 — `GET /reports/revenue?from=&to=&bucket=`

Three independent per-bucket sums over the three money ledgers, merged on `period`:

| column | source | timestamp key | predicate |
|---|---|---|---|
| `invoiced` | `SUM(sales_invoice.grand_total)` | `issued_at` | `status IN (ISSUED, PAID)` — VOID excluded, DRAFT has no `issued_at` |
| `collected` | `SUM(payment.amount)` | `received_at` | all payments — money that actually arrived; a later REFUNDED status does **not** retro-remove it (the outflow shows in `refunded`) |
| `refunded` | `SUM(refund.amount)` | `executed_at` | `status = EXECUTED` only — PENDING refunds haven't moved money (two-step lifecycle, `refund.md`) |

Response:

```json
{ "bucket": "day", "from": "…", "to": "…",
  "series": [ { "period": "2026-07-01T00:00:00Z", "invoiced": "1250.00", "collected": "1100.00", "refunded": "50.00" }, … ],
  "totals": { "invoiced": "…", "collected": "…", "refunded": "…" } }
```

- **Refund rate is `totals.refunded / totals.collected`, computed by the client** (per bucket or
  overall). Returning both numerators keeps the endpoint honest and kills a whole endpoint.
- "Invoiced vs collected" is deliberately **both**, not a `basis` param: they answer different
  questions (accrual vs cash) and a chart wants them as two lines on one axis. Netting credit
  notes into `invoiced` is out of scope v1 (see §Out of scope).

### G2 — `GET /reports/sales?from=&to=&bucket=&channel=`

- Grouped `bucket × channel` over `sales_order`: `count(*)` as `orders`, `SUM(grand_total)` as
  `gross`.
- **What counts as a sale:** `status IN (PAID, FULFILLING, FULFILLED, CLOSED)` — money committed.
  DRAFT/PENDING_PAYMENT (not yet money), CANCELLED/EXPIRED (never became money) are excluded.
  This intentionally reads *current* status: an order that later cancels drops out of the series —
  documented, since "sales" here means *stood-up sales*, not gross attempts.
- **Timestamp key:** `COALESCE(placed_at, created_at)` — `placed_at` is when the order became
  real; the fallback is defensive for any row that reached a sale status without one.
- `channel` — optional exact filter, `ONLINE|IN_STORE|PHONE` (unknown → 400). Unfiltered returns
  every channel's row per bucket; the client pivots for a stacked chart.
- Response rows: `{period, channel, orders, gross}`, ordered `period ASC, channel ASC`, plus a
  `totals` block `{orders, gross}`.

### G3 — `GET /reports/top-products?from=&to=&by=revenue|quantity&limit=`

- `sales_order_line` JOIN `sales_order` (same sale-status set + window + `org_id` as G2) JOIN
  `product` — grouped by product: `SUM(quantity)` as `quantity`, `SUM(line_total)` as `revenue`.
  The product join is safe: product deletion is FK-blocked while order lines reference it
  (the existing 409 on `DELETE /products/{id}`), so a ranked row can always carry the live
  `name`/`sku`.
- `by` — sort key, `revenue` (default) or `quantity` (unknown → 400); ties broken `product_id ASC`
  for stable pagination-free output. `limit` — default 10, cap **50** (400 above; a "top-N" that
  needs more than 50 is a list, and we have lists).
- Rows: `{product_id, name, sku, quantity, revenue}`. No `PageResponse` — a capped top-N is not a
  pageable collection (same reasoning as the unpaginated per-order reads).

### G4 — `GET /reports/ar-aging?buckets=`

- Population: `status = ISSUED AND paid_amount < grand_total` — **exactly** the predicate of
  V21's partial index `idx_invoice_unpaid (org_id, issued_at)`, so the scan is index-ranged by
  construction. Outstanding per invoice = `grand_total − paid_amount`; age = `now − issued_at`.
- `buckets` — optional comma-separated ascending positive day-edges, default `30,60,90` (→ bands
  `0–30`, `31–60`, `61–90`, `90+`). 1–6 edges; non-numeric, non-ascending, or >6 → 400.
- Response:

```json
{ "as_of": "…",
  "buckets": [ { "label": "0-30", "count": 4, "outstanding": "2100.00" }, …, { "label": "90+", … } ],
  "total_count": 9, "total_outstanding": "5400.00" }
```

- All bands are always present (zero-filled — unlike the sparse time series, the band set is
  fixed and tiny, and an aging report with missing bands reads as broken).
- `paid_amount` is the cached `SUM(payment_allocation.amount)` (V23's transactional cache), so
  aging agrees with the invoice detail by construction. Credit notes do **not** reduce
  outstanding here (they aren't payments); that nuance stays on the invoice's crediting story
  (`GET /credit-notes?sales_invoice_id=`).

### G5 — `GET /reports/inventory-valuation`

- Point-in-time, no window: `inventory` JOIN `product` on the org —
  `COUNT(*)` as `tracked_products`, `SUM(stock_qty)` as `total_units`,
  `SUM(stock_qty * base_price)` as `retail_value`,
  `COUNT(*) FILTER (WHERE stock_qty = 0)` as `out_of_stock` (same `FILTER` idiom as the health
  rollup's single-scan counts).
- Response: `{as_of, tracked_products, total_units, retail_value, out_of_stock}`.
- **The field is named `retail_value`, on purpose.** `product` has **no cost column** — V40's
  "internal data (cost, …)" comment is aspirational, not schema — so valuation at `base_price` is
  the only honest figure. Naming it `retail_value` (never `valuation` or `cogs`) makes the basis
  unmistakable in every consumer. A cost-basis valuation is a schema slice (`product.cost_price`)
  that can extend this response additively later.
- Untracked products (no `inventory` row) are excluded — they have no quantity to value; the
  inventory list already surfaces them as `tracked:false` rows for anyone auditing coverage.

---

## Errors

| Status | Cause |
|---|---|
| `400` | unknown `bucket`/`channel`/`by`; `from >= to`; window > 366 days; malformed ISO date; `limit` < 1 or > 50; malformed/non-ascending/oversized `buckets` |
| `403` | caller lacks VIEWER on `orgId`, is not a member, or the org is suspended (platform-ADMIN bypass preserved) |
| `404` | `orgId` unknown; unknown report name under `/reports/` |
| `405` | any non-GET verb on any `/reports/*` path |

---

## Tests

- **Revenue sums are exact** (IT): seed invoices (ISSUED, PAID, one VOID, one DRAFT), payments,
  and refunds (EXECUTED + PENDING + CANCELLED) across three days; per-bucket `invoiced` excludes
  VOID/DRAFT, `collected` includes every payment, `refunded` counts EXECUTED only; `totals` equal
  the column sums; empty buckets are absent from `series`.
- **Sales grouping & status filter** (IT): seed orders across two days × two channels in every
  status; only PAID/FULFILLING/FULFILLED/CLOSED contribute; `channel=IN_STORE` narrows; unknown
  channel → 400; bucket boundaries honor `[from, to)` half-open edges (an order at exactly `to`
  is excluded).
- **Top products ranking** (IT): seeded lines rank correctly under both `by=revenue` and
  `by=quantity`; `limit` caps; excluded-status orders' lines never contribute.
- **AR aging** (IT): invoices seeded at ages 10/45/100 days with partial payments land in the
  right bands with `outstanding = grand_total − paid_amount`; a fully-paid ISSUED invoice and a
  VOID invoice are excluded; custom `buckets=15,45` re-bands; all bands present at zero.
- **Inventory valuation** (IT): `retail_value == Σ stock_qty × base_price` over tracked products
  only; `out_of_stock` counts `stock_qty = 0` rows; untracked products don't contribute.
- **Org isolation** (IT, all five): two orgs seeded; org B's data changes never move any org A
  figure.
- **Auth** (IT): VIEWER passes all five; non-member 403; suspended-org member 403; platform ADMIN
  bypasses; POST/PUT/DELETE on `/reports/revenue` → 405.
- **Param validation** (unit, `ReportService`): every 400 row in the table above.

---

## Out of scope / known gaps

- **UTC bucketing.** Day/week/month edges are UTC, not org-local (Africa/Cairo). A `tz` parameter
  (IANA name → `AT TIME ZONE`) is a clean additive follow-up if the 2–3h skew ever misleads a
  tenant; shipping v1 without it keeps validation surface small.
- **Sparse series.** Time-series endpoints omit empty buckets; the client zero-fills for
  continuous axes. Documented contract, not an accident.
- **No pre-aggregation.** Every call scans live tables. Fine at current volumes (org-scoped,
  index-backed, ≤366-day windows); a rollup/materialized-view slice is the escape hatch if a
  tenant's ledgers grow past interactive latency — the response contracts here are designed so
  that swap is invisible.
- **`invoiced` is gross of credit notes.** Netting ISSUED/SETTLED credit-note totals into the
  revenue series is a real accounting question (which period does a credit hit?) deferred until a
  tenant asks; `refunded` already shows the cash outflow.
- **Cost-basis valuation needs schema.** `retail_value` is the v1 truth; COGS requires
  `product.cost_price` (separate migration + backfill story) and would then ship as an additive
  `cost_value` field.
- **No CSV/export, no platform-plane (cross-org) analytics.** Both are separate slices; the
  admin plane can reuse `ReportRepository` with its own gating when wanted.
- **Sales series reads current status** — an order cancelled after the window was charted drops
  out on the next read. That is the chosen semantic (stood-up sales), stated so nobody files it
  as a bug.
