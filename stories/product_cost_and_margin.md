# Slice: What the stock cost — `product.cost_price`, the per-line cost snapshot, and profit on the reports

> The gap [`reporting_reads.md`](./reporting_reads.md) parked on 2026-07-12 ("a cost-basis
> valuation is a schema slice — `product.cost_price` — and would then ship as an additive
> `cost_value` field") and frontend story 22 filed as **B3**. It is also the sixth gap against
> Loyverse, found after the owner's ranked five
> ([`capture_walk_in_customer.md`](./capture_walk_in_customer.md) V87 →
> [`escpos_receipt.md`](./escpos_receipt.md)) closed: Loyverse's item form has a *Cost* beside the
> *Price*, its inventory valuation report shows *inventory value* (at cost) next to *retail value*,
> and its sales-by-item report has *cost of goods*, *gross profit* and *margin* columns. **Branch
> `173_feat/product-cost` off `master`, migration V92.** Frontend pair:
> `frontst/stories/136_st_product_cost_margin.md`.

---

## Goal

Give a product a **cost**, freeze that cost onto every sale line at the moment of sale, and let
the reports answer the three questions a merchant asks that nothing here can answer today:

1. **What is my stock worth?** `GET /reports/inventory-valuation` gains `cost_value` — units on
   hand × what they cost — beside the existing `retail_value`, with an honest statement of how
   much of the shelf the number covers.
2. **What did I make on each product?** `GET /reports/top-products` rows gain `cost` and
   `gross_profit` (and the two facts a client needs to turn them into a margin), plus
   `?by=profit`.
3. **What did I make, full stop?** `GET /reports/profit?from&to` — the window's cost of goods and
   gross profit in one number each.

All of it is **MANAGER-plane data**: a cashier's screens never carry a cost, a cost-derived figure
or the option to ask for one.

Done means: a manager types a cost on a product once, sells it at the counter, and the reports
page shows the profit on that sale — with the products she has not costed yet named as a gap,
not silently priced at zero.

---

## What exists, what is missing

- `product` (V1) carries `base_price NUMERIC(12,2) NOT NULL` and nothing else money-shaped. V40's
  comment that listings exist so "internal data (cost, supplier, base_price, barcode) never
  leaks" was aspirational: the codebase has no cost to leak.
- `sales_order_line` (V18) snapshots `unit_price` at placement ("price-at-order-time") — the
  precedent that a sale freezes its own money. It snapshots nothing about cost, because there was
  nothing to snapshot.
- `ReportRepositoryImpl.inventoryValuation` sums `stock_qty * base_price` and the field is named
  `retail_value` **on purpose** (story `reporting_reads.md`: "naming it `retail_value` makes the
  basis unmistakable"). The frontend renders the caveat "cost basis not tracked" under it. That
  honesty is the right posture for a system without costs — and it is still a valuation report
  that cannot tell the merchant what the shelf is worth.
- `topProducts` ranks by `SUM(line_total)` (tax-inclusive) or `SUM(quantity)`. There is no
  notion of what the units cost, so there is no profit, so nothing can rank by it.
- Every line is built in **one place**: `SalesOrderService`'s order builder pulls a
  `ProductSnapshot(productId, description, unitPrice)` per line and calls
  `SalesOrderLine.create(...)`. Storefront, portal, phone and in-store sales all pass through it.
  A variant line's `product_id` is the **child** product (V70), so whatever the snapshot carries
  is per-variant for free.
- Restock is `POST /inventory/{productId}/restock {qty}` — a quantity, no money. There is no
  purchase order, no supplier, no receiving flow anywhere.

Missing, therefore: a place to record a cost, the snapshot that makes past margins stable when
today's cost changes, the report reads that consume both, and the gate that keeps all three off
the cashier's plane.

---

## Why this shape

### One cost per product, typed by the merchant — not a moving average

Loyverse recomputes an item's *average cost* every time stock is received with a cost. That is
the right model **when receiving carries a cost**, and here it does not: restock is a bare `qty`,
there is no purchase order to hang a unit cost on, and a stationery shop's owner knows the cost
of a notebook the way she knows its price — from the supplier's last invoice, in her head. So the
slice ships the number she has: **`product.cost_price`**, one current cost per product, edited
where the price is edited. Weighted-average costing is deferred with its shape written down
(below) — it is a strict addition (a `unit_cost` on restock that *updates* this column), not a
replacement, so shipping the column first loses nothing.

### `NULL` means "not costed", and the reports say so

`cost_price` is **nullable, no default**. A product the merchant has not costed is not a product
that costs nothing, and the difference matters everywhere the number is consumed: a valuation
that counts uncosted stock at zero *understates* the shelf; a profit that treats an uncosted
sale as pure margin *overstates* the month. Loyverse defaults cost to 0 and its gross profit is
quietly wrong until every item is costed. This codebase's rule is the opposite — a figure that
cannot be computed is **absent, never zero** (`degraded[]`, the funnel's `null`s, `rating_avg`) —
so every cost-derived number here comes with the coverage it was computed over
(`uncosted_units`, `costed_quantity`), and is omitted outright when there is nothing to compute
it from.

### The sale line freezes the cost — no backfill

`sales_order_line.unit_cost` is stamped at placement from the product's `cost_price` at that
instant, exactly as `unit_price` is. Consequences, all deliberate:

- A cost edit tomorrow does not rewrite last month's margin. Profit is a fact about the sale, not
  about the current catalog — the `unit_price` rule, one column over.
- **Existing lines stay `NULL`.** Backfilling history with today's cost would fabricate a margin
  the merchant never measured; those units appear as "uncosted" in every report, and the merchant
  narrows the window to "since I set costs" to see clean numbers. V92 writes no data.
- A sale of an uncosted product freezes `NULL`, and the reports count its units as uncosted.
- The snapshot moment is **placement**, because that is what the reports call the sale
  (`COALESCE(placed_at, created_at)`, status ∈ PAID/FULFILLING/FULFILLED/CLOSED). An online order
  delivered three days later at a changed cost does not re-cost; a PENDING_PAYMENT order that
  expires carries a snapshot nothing ever counts.
- **Variants:** the line's `product_id` is the child, so the child's `cost_price` is what lands.
  Per-variant cost is therefore the child product's cost, edited through the ordinary
  `PUT /products/{childId}` (children are ordinary org products). The variants set-replace does not
  take a cost in this slice — deferred, below.

### Net sales for margin: tax out, order discount prorated in

The existing `revenue` on top-products is `SUM(line_total)` — **tax-inclusive** — and stays
exactly that (renaming a shipped field is a wire break for a label). A margin computed against a
tax-inclusive base is wrong by the tax rate, so the profit arithmetic uses its own base:

```
line_discount_share = CASE WHEN so.subtotal > 0
                           THEN round(so.discount_total * sol.line_subtotal / so.subtotal, 2)
                           ELSE 0 END                     -- HALF_EVEN, the InvoiceService.discountToBill rule
net_sales_line      = sol.line_subtotal - line_discount_share       -- ex-tax, ex-shipping
cost_line           = sol.quantity * sol.unit_cost                  -- NULL when uncosted
gross_profit_line   = net_sales_line - cost_line                    -- NULL when uncosted
```

The discount (coupon V72 or counter discount V88) is recorded **on the order**, off the goods
subtotal. Prorating it onto lines by subtotal share is the rule `InvoiceService.discountToBill`
already applies when an order is invoiced in parts; using it here means a product's profit and
the window's profit are the same arithmetic at two grains — the tile and its drill-down agree by
construction. This is a ranking/summary report, so Σ shares landing a piastre off the order's
discount is acceptable and stated; the invoice keeps the exact-remainder rule because it must.

**Every one of these expressions is defined once**, as jOOQ fields in a `ReportRepositoryImpl`
helper, and consumed by both the per-product read and the profit total. The epic's recurring
defect is the same predicate written twice; this slice writes it once.

### Costed subset, always with its coverage

Per product: `costed_quantity = SUM(quantity) FILTER (WHERE unit_cost IS NOT NULL)`, and
`costed_net_sales`, `cost`, `gross_profit` are sums **over those lines only**. The client's margin
is `gross_profit / costed_net_sales` — numerator and denominator over the same lines, so a product
costed mid-window shows the true margin on the units it can speak for and "*N of M* units
uncosted" beside it. The alternative — omit profit unless every line is costed — would blank most
rows for the first month after a merchant adopts costing, which is exactly when she wants to see
them.

`costed_quantity` is a **primitive, always present** when cost figures are visible at all;
`costed_net_sales`/`cost`/`gross_profit` are **absent when `costed_quantity == 0`** — a
`gross_profit: 0` on a product nobody costed reads as "made nothing", and this codebase's absent
key means "no news" (`email_verified` + `email_verified_at`, same reflex). No percentage ever
crosses the wire (the funnel's rule; the client is the only layer that knows when "3 of 7" beats
"43%").

Returns are **not netted** here — the shipped `revenue` does not net credit notes either, and
netting one without the other would make profit and revenue disagree about what a sale is. They
move together in the deferred item below.

### Cost is MANAGER data

A cashier ringing sales on `/sell` should not learn the shop's markups from the product list.
Loyverse models this as a *View cost of items* access right that its Cashier role lacks. Here the
role hierarchy already has the seam: MANAGER is "the money-shaped authority" (coupons, counter
discounts, refunds). So:

- `cost_price` is **written** only by MANAGER+ (or platform ADMIN). A STAFF `PUT`/`POST` that
  carries the key is a 403 naming it — not silently dropped, so a client that shows the field to
  the wrong role finds out.
- `cost_price` and every derived figure are **read** only by MANAGER+: the key is not on the wire
  for STAFF/VIEWER (not `null`, not `0` — absent), on `ProductResponse`, the inventory product
  projections, `inventory-valuation`, `top-products`. `?by=profit` from below MANAGER → 403;
  `GET /reports/profit` below MANAGER → 403.
- The predicate is **one method**: `SalesOrderHandler.isManagerOrAdmin` (V88's discount gate) is
  promoted to `AuthzHelper.hasManagerAuthority(SecurityContext, UUID orgId)` and the discount gate
  delegates to it. Four consumers, one definition, one unit test.
- The public and portal planes carry no cost by construction — `PublicOrderResponse.Line` and
  `PublicListingResponse` are pinned by whitelist tests that this slice extends.

---

## Data model — **V92** `Product_cost_price`

```sql
-- product.cost_price: what a unit cost the merchant. NULLABLE ON PURPOSE — NULL is "not costed",
-- a different fact from 0.00 ("free"), and every report that consumes it states its coverage.
-- No default, no backfill: nothing in this system knows what existing stock cost.
ALTER TABLE product
    ADD COLUMN cost_price NUMERIC(12, 2)
        CHECK (cost_price IS NULL OR cost_price >= 0);

-- sales_order_line.unit_cost: product.cost_price frozen at placement, the unit_price rule one
-- column over. NULL when the product was uncosted at the time of sale. Existing rows stay NULL —
-- backfilling history with today's cost would fabricate margins that were never measured.
ALTER TABLE sales_order_line
    ADD COLUMN unit_cost NUMERIC(14, 2)
        CHECK (unit_cost IS NULL OR unit_cost >= 0);
```

Both are nullable-no-default adds — catalog-only in Postgres 11+, O(1) regardless of row count
(perfdb's `sales_order_line` is not rewritten). **No index**: valuation is already a full scan of
the org's `inventory`; top-products and the profit total ride the existing
`sales_order (org_id, status, …)` + line join, adding two `SUM … FILTER` terms to a plan that
does not change shape. Measured, not assumed — see Tests.

jOOQ codegen re-run; `Product` gains `costPrice`, `SalesOrderLine` gains `unitCost` (both
nullable `BigDecimal`), `SalesOrderRepository.ProductSnapshot` gains `unitCost`.

---

## Application & wire

### Products — `POST /api/orgs/{orgId}/products` · `PUT /api/orgs/{orgId}/products/{id}`

`cost_price` joins the body as a **tri-state** field: **absent → unchanged** (create: stays
unset), **`null` → cleared**, **number → set** (`>= 0`, else 400 `"cost_price must be >= 0"`).
Jackson does not distinguish absent from `null` on a plain field, so `CreateProductRequest` /
`UpdateProductRequest` record presence in the setter (`costPricePresent`). The org merge-PUT
treats absent and `null` alike (both leave-unchanged) and so can never clear a field; here `null`
has to mean *clear*, which is why presence is tracked rather than copied from that precedent. It
is also what lets the STAFF product form keep doing a full-replace `PUT` **without the key** and
never touch a cost it cannot see.

Present from a caller without manager authority → **403** `"cost_price needs MANAGER"` — before
any write. `ProductService.create/update` take `costPrice` + `costPricePresent` and validate as
above; `ProductRepositoryImpl` writes the column on both.

`ProductResponse` gains `cost_price` — written only when the caller has manager authority
**and** the product is costed. The mappers take the visibility decision as a parameter
(`ProductResponse.from(p, costVisible)`); there is deliberately no overload that guesses.

### Inventory reads — `GET /inventory`, `GET /inventory/{productId}`

`InventoryOverviewRow` and the product-basics block already carry `base_price`; both gain
`cost_price` under the same rule (manager authority + costed). `InventoryRepositoryImpl`'s three
`PRODUCT.BASE_PRICE` selects each gain `PRODUCT.COST_PRICE`.

### `GET /api/orgs/{orgId}/reports/inventory-valuation`

Unchanged for VIEWER/STAFF (byte-identical envelope). With manager authority, three more fields:

```json
{
  "as_of": "…", "tracked_products": 60, "total_units": 1240, "retail_value": 48200.00, "out_of_stock": 3,
  "cost_value": 27310.00,        // Σ stock_qty × cost_price over COSTED tracked products; absent when none is costed
  "uncosted_products": 12,       // tracked products with cost_price IS NULL — always present
  "uncosted_units": 180          // Σ stock_qty over them — always present
}
```

The client shows potential profit (`retail_value − cost_value`) only when `uncosted_units == 0`;
across a partially costed shelf the two values are over different sets and the subtraction is
not a fact.

### `GET /api/orgs/{orgId}/reports/top-products?from=&to=&by=revenue|quantity|profit&limit=`

Rows keep `{product_id, name, sku, quantity, revenue}` exactly. With manager authority each row
adds `costed_quantity` (always) and — when `costed_quantity > 0` — `costed_net_sales`, `cost`,
`gross_profit`, per *Costed subset* above. `by=profit` orders `gross_profit DESC NULLS LAST,
product_id ASC` (uncosted products rank last, never hidden — a sort must not shrink the list);
without manager authority `by=profit` → **403**, and `revenue`/`quantity` behave as today.
Envelope `by` echoes `"profit"`.

### `GET /api/orgs/{orgId}/reports/profit?from=&to=` — new, MANAGER+

The window's total over the same lines and the same expressions:

```json
{ "from": "…", "to": "…",
  "quantity": 3120, "costed_quantity": 2860,
  "costed_net_sales": 91400.00, "cost": 61250.00, "gross_profit": 30150.00 }
```

Money fields absent when `costed_quantity == 0`. Common windowed-parameter rules verbatim
(`from < to`, ≤ 366 days, ISO-8601, defaults). Below manager authority → 403; GET only → 405;
subpath → 404. The relationship the IT pins: over the same window, Σ `gross_profit` across a
`limit`-uncapped top-products read equals this `gross_profit` — one arithmetic, two grains.

### Service

```
AuthzHelper.hasManagerAuthority(sc, orgId)          // promoted from SalesOrderHandler.isManagerOrAdmin; that gate delegates
ProductService.create/update(…, costPrice, costPricePresent, …)
SalesOrderRepository.ProductSnapshot(productId, description, unitPrice, unitCost)
SalesOrderLine.create(…, unitPrice, unitCost, taxRate)  // unitCost nullable, frozen, never recomputed
ReportRepository.topProducts(…, TopProductsSort sort, boolean withCost, limit)
ReportRepository.profit(orgId, from, to)            // new
ReportRepository.inventoryValuation(orgId)          // returns cost figures always; the handler decides what crosses
ReportService.profit(orgId, fromParam, toParam)     // new; parseBy accepts "profit"
ReportRepositoryImpl.ProfitFields                   // the four expressions, defined once, consumed twice
```

The repository always computes the cost figures (they are cheap `FILTER` sums on a query that
runs anyway); **the handler decides what crosses**, from one `boolean costVisible =
AuthzHelper.hasManagerAuthority(sc, orgId)` computed once per request. Gating in the DTO mapper,
not the SQL, keeps one query plan and makes the test "a STAFF response has no cost key" a JSON
assertion rather than a mock choreography.

### Receipts, invoices, credit notes, notifications

Untouched. No document prints a cost; no customer-facing DTO gains one. `PublicOrderResponse.Line`
is extended in its whitelist test to assert `unit_cost` is unrepresentable.

---

## Authorization

- Product/inventory **reads** stay VIEWER; the `cost_price` key rides only for MANAGER/OWNER or
  platform ADMIN.
- Product **writes** stay STAFF; a body carrying `cost_price` needs MANAGER+ (403 otherwise).
- `/reports/*` stays VIEWER except `GET /reports/profit` and `?by=profit`, MANAGER+; cost fields
  on the other two reads ride only for MANAGER+.
- The org-suspension gate, JWT filter and rate limits are unchanged.

---

## Out (deferred)

- **Weighted-average cost on receiving.** The shape: `POST /inventory/{productId}/restock {qty,
  unit_cost?}` + `inventory_log.unit_cost`; when present, `cost_price ← (stock_qty × cost_price +
  qty × unit_cost) / (stock_qty + qty)` in the restock txn (an uncosted product takes `unit_cost`
  outright). The open question it must answer first: what the units already on the shelf cost
  when the first costed receipt arrives. Not answered blind.
- **`cost_price` per variant row** on `PUT /product-listings/{id}/variants` (minting a child
  with a cost). Today: cost the child through `PUT /products/{childId}`.
- **Netting returns** into `revenue`, `net_sales` and `cost` together (a RETURN credit note's
  lines carry `product_id`, and a `restock:false` return is a loss, not a cost reversal — the
  accounting question `reporting_reads.md` deferred, still deferred).
- **A profit time series** (`/reports/profit?bucket=`) for a chart; **`?channel=`** on the profit
  total; **per-order profit** on `SalesOrderResponse` lines (`unit_cost` does not ride the staff
  line DTO in this slice).
- **Clearing a cost from below MANAGER**, **cost history / audit** (product edits are unaudited
  today), **supplier / purchase orders**.
- **perfdb dressing**: V92 is a hand-migration there per `tools/seed/README.md`; the seeded
  products stay uncosted (a `cost_price ≈ 0.6 × base_price` dressing for `mart-cairo` is a
  separate seed decision, not this slice's).

---

## Tests

`ProductServiceTest`:
- `create_costPriceNegativeIs400` · `create_costPriceAbsentStaysNull` ·
  `update_costPriceAbsentLeavesExistingCost` · `update_costPriceNullClears` ·
  `update_costPriceSetsAndScales` (`30` → `30.00`).

`api/.../ProductHandlerCostTest` (handler, mocked service):
- `staffPutWithCostPriceIs403BeforeAnyWrite` · `staffPutWithoutCostPriceIsUnchanged200` ·
  `managerResponseCarriesCostPrice` · `staffAndViewerResponseHasNoCostPriceKey` (JSON tree
  assertion — the key, not its value) · `uncostedProductHasNoCostPriceKeyEvenForManager` ·
  `platformAdminBypasses` · `hasManagerAuthority_ownerPasses_staffFails_adminBypasses` (the promoted
  predicate; `SalesOrderHandler`'s discount gate test stays green through the delegation).

`InStoreSaleIT` / `SalesOrderServiceIT` (Testcontainers):
- `saleFreezesUnitCostFromProduct` — cost `30.00`, sell 2 → line `unit_cost = 30.00`; set cost to
  `35.00`; the line still reads `30.00`.
- `saleOfUncostedProductFreezesNull` · `storefrontCheckoutFreezesUnitCostToo` (one builder, every
  channel) · `variantLineFreezesTheChildsCost`.

`ReportReadsIT`:
- `valuation_costValueOverCostedOnly_andCoverage` — 3 tracked products, 2 costed: `cost_value =
  Σ costed stock × cost`, `uncosted_products = 1`, `uncosted_units` = its stock; `retail_value`
  unchanged; all products uncosted → no `cost_value` key, `uncosted_products == tracked_products`.
- `topProducts_costFieldsOverCostedLines` — a product sold before and after costing:
  `quantity = all`, `costed_quantity = after`, `cost`/`costed_net_sales`/`gross_profit` over the
  after-lines only; a never-costed product → `costed_quantity: 0` and no money keys.
- `topProducts_netSalesExcludesTaxAndProratesDiscount` — org tax 14 %, a two-line in-store sale
  with a 10 % counter discount: each line's `costed_net_sales = line_subtotal − round(discount ×
  share, 2)`; `revenue` on the same row still includes tax (they differ by exactly the tax).
- `topProducts_byProfitOrdersNullsLast` — profit DESC, uncosted last, `product_id` tie-break;
  `by=profit` from a VIEWER → 403; `revenue`/`quantity` from a VIEWER → 200 with **no cost key on
  any row**.
- `profit_totalEqualsSumOfRows` — `GET /reports/profit` vs Σ over `top-products?limit=100` for the
  same window, all five fields; excluded statuses (PENDING_PAYMENT, CANCELLED, EXPIRED) contribute
  nothing; empty window → `quantity: 0, costed_quantity: 0`, no money keys.
- `profit_viewerIs403_postIs405_subpathIs404`.

`PublicOrderResponseForOrderViewTest`: `lines_carryNoUnitCost` (structural — the `Line` class has
no such field). `PublicListingResponse` whitelist test: unchanged and green (nothing added).

`SalesOrderLineTest`: `create_carriesUnitCostVerbatim_nullStaysNull`.

**Measured, recorded in `tools/seed/results/product_cost_173.txt`** (the file, not prose):
`EXPLAIN (ANALYZE, BUFFERS)` on perfdb after the hand-migration for (a) top-products with the four
new sums vs before, (b) the profit total, (c) valuation with the three new terms — the claim being
that no plan changes shape and no index is warranted. V73's rule applies: an index the planner
will not choose is write cost for no read benefit.

Regression green: `ReportReadsIT`, `ReportServiceTest`, `ReportsHandlerAuthTest`,
`InStoreSaleIT`, `CounterDiscountIT`, `SplitTenderIT`, `CounterReturnIT`, `ProductRepositoryImplTest`.

## Definition of done

V92 + codegen + `Product`/`SalesOrderLine`/`ProductSnapshot` fields + tri-state request DTOs +
`hasManagerAuthority` promoted + the three report reads + `/reports/profit` + the suites above
green + the EXPLAIN capture; `spotless:apply`; CLAUDE.md's Products / Inventory / Reports lines
updated; story committed on `173_feat/product-cost`. The frontend pair
`frontst/stories/136_st_product_cost_margin.md` ships **after** this merges — against the old API
its cost field would be silently ignored on write and never echoed on read, which is a lie on a
form, so the pair must not land first. perfdb needs the V92 hand-migration before the next bench.
