# Slice: Order status counts (the worklist tabs' numbers)

> The admin orders worklist has five URL-driven tabs — All / Pending / Paid / Fulfilling / Closed —
> and the frontend's `Worklist.Tabs` component has carried an optional per-tab `count` since it was
> built. Nothing has ever filled it, because the backend offers **no way to count orders by status
> short of listing them**: the worklist read
> ([`SalesOrderHandler.doGetOrList`](../api/src/main/java/com/loai/inventory/api/servlet/handler/SalesOrderHandler.java))
> returns a `PageResponse` whose `total` covers only the *requested* filter. Filling five chips
> would take five `size=1` list calls per render — exactly the fan-out
> [`org_health_rollup.md`](org_health_rollup.md) (G1/G4) exists to forbid, and each one drags a
> page of fully-mapped `Placed` rows (order + batch-loaded lines) through the mapper just to read
> one integer. The health rollup itself carries only `pending_payment_orders` — one chip of five.
> This slice gives the tabs their numbers in one cheap read. Frontend pair: `frontst`
> story 119.

---

## Today (the gap)

- **No status-counts read.** `SalesOrderService.list` → `repo.count(orgId, status)` computes any
  single count, but only bundled with a page of rows. There is no counts-only door, so the tab
  strip either stays numberless or scrapes `total` five times.
- The org health rollup deliberately stays four figures (`org_health_rollup.md` §Out: "no new
  aggregates" without extending both planes together) — widening it to eight order statuses would
  bloat a strip read that most pages consume for other tiles.

## Goal

**One call powers the tab chips.** `GET /api/orgs/{orgId}/sales-orders/status-counts` returns
every status's live count plus the ledger total, so the orders page renders `All 12 · Pending 2 ·
Paid 4 · Fulfilling 3 · Closed 3` from a single query — and a chip always equals the `total` the
tab's own list read would report.

---

## Design

### `GET /api/orgs/{orgId}/sales-orders/status-counts`

- **Routing:** a fixed segment on the sales-orders collection, matched in
  `SalesOrderHandler.handle` **before** the `{id}` UUID parse (`parts.length == 1 &&
  "status-counts".equals(parts[0])`) — the `GET /api/portal/notifications/unread-count` precedent:
  a counts subresource beside `/{id}` routes. Non-GET on it → **405**; it can never collide with
  an id (`status-counts` is not a UUID).
- **Shape:**

  ```json
  {
    "counts": {
      "DRAFT": 0, "PENDING_PAYMENT": 2, "PAID": 4, "FULFILLING": 3,
      "FULFILLED": 0, "CLOSED": 3, "CANCELLED": 0, "EXPIRED": 0
    },
    "total": 12
  }
  ```

  **All eight `OrderStatus` values are always present, `0` included** — the funnel's
  `reached: 0` rule ("nobody got here" ≠ "no data"); a client must never have to treat absence
  as zero. `total` is the unfiltered ledger count (== Σ counts by construction — the same rows
  partitioned by status) and is on the wire so the All chip reads one field instead of summing.
  Statuses without a tab today (DRAFT, FULFILLED, CANCELLED, EXPIRED) are deliberately included:
  the map is the enum, and a future tab costs zero backend work.
- **Query:** one new repository method, `SalesOrderRepository.countByStatus(orgId)` →
  `SELECT status, count(*) FROM sales_order WHERE org_id = ? GROUP BY status`; the service folds
  the result over `OrderStatus.values()` filling absent statuses with `0` and summing `total`.
  Same table, same org predicate as `repo.count(orgId, status)` — the chip-equals-tab-total
  invariant is pinned by IT (below), the two queries being trivially the same `WHERE`.
- **Authorization: VIEWER** — the counts are a projection of the worklist the caller can already
  page through (`doGetOrList` is VIEWER); no figure here is more sensitive than the list itself.
  `requireOrgAccess` gives suspension enforcement + platform-ADMIN bypass for free.
- **DTO:** `OrderStatusCountsResponse{Map<OrderStatus, Long> counts, long total}` — Jackson
  serializes enum keys by name, matching the frontend's `SalesOrderStatus` strings; SNAKE_CASE
  doesn't touch either field name.
- **Not on `/health`:** the rollup stays the four-figure strip read (its own story's "no new
  aggregates" rule); these counts belong to the orders surface and are fetched by the orders
  page, not by every dashboard visit.
- **Not a `?counts=true` shape-switch on the list:** that route already switches shape on
  `order_number` (single object vs page); a third response shape keyed on which parameter you
  passed is the incoherence `org_customer_reads.md` refused for `?customer_id=`.

### Performance (measure, don't assume)

V66 already ships `sales_order_org_status_idx (org_id, status, created_at, id)` — the grouped
count should resolve as an index-only scan over one org's slice. Per the V73 rule (no unmeasured
indexes; the planner is the authority): capture `EXPLAIN (ANALYZE, BUFFERS)` for the grouped
query on `perfdb` into `tools/seed/results/order_status_counts_NN.txt` on the branch
(`measurements` convention — results as attached docs, not Javadoc). Expected outcome: **no new
index**; if the planner declines V66's index, that capture is the evidence for whatever ships
instead. perfdb seeds commerce tables, so no empty-table `count(*)` caveat applies here.

---

## Errors

| Status | Cause |
|---|---|
| `403` | caller lacks VIEWER, is not a member of `orgId`, or the org is suspended (platform-ADMIN bypass preserved) |
| `404` | `orgId` unknown |
| `405` | non-GET on `/sales-orders/status-counts` |

---

## Tests

- **Chips equal tab totals** (IT — the drift pin): seed an org with orders across ≥ 3 statuses;
  for each status, `counts[status]` equals the `total` of `GET /sales-orders?status={s}&size=1`,
  and `total` equals the unfiltered list's `total` **and** Σ `counts` — the tile-equals-drilldown
  rule, asserted rather than assumed.
- **All eight keys, zeros included** (IT): an org with only PENDING_PAYMENT orders still gets all
  eight statuses on the wire, absent ones as `0`, never omitted.
- **Isolation** (IT): a second org's orders never leak into the first's counts.
- **Gating** (IT): non-member → 403; VIEWER member → 200; suspended org's member → 403; platform
  ADMIN bypasses; `POST /sales-orders/status-counts` → 405.
- **Routing** (IT): the fixed segment never falls through to the UUID parse — no 400 "invalid id".

---

## Out of scope / known gaps

- **Point-in-time, unversioned.** The chips and the page's list are two reads; a mutation between
  them can skew a chip by one until the next RSC refetch. Accepted — the same staleness every
  dashboard count here has.
- **No `?channel=`/date narrowing.** The tabs are status-only today; a parameterized counts read
  is a different (filter-bar) story.
- **The org health rollup is untouched** — `pending_payment_orders` continues to serve the
  dashboard strip; this read serves the orders page. Two callers, two shaped reads, one truth
  each.
