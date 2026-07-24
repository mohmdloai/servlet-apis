# Slice: Best-sellers sort + sold-filter (`?sort=best_selling`)

> Roadmap item 4 (Tier 1) — [`docs/storefront-growth-roadmap.md`](../docs/storefront-growth-roadmap.md).
> The honest, computed half of the "Recommendations" gap: rank PUBLISHED listings by **actual
> confirmed units sold** over a rolling window. No behavior tracking, no personal data, no fake
> ranking — an aggregate over rows the platform already stores. Gives the storefront its first
> *automatic* discovery surface (today only the merchant's manual Featured strip exists) without
> touching the honesty rule. **No migration; live query; the existing `max-age=60` cache absorbs
> load.** Feeds frontend story 63.

---

## Goal

`GET /api/public/{orgSlug}/listings?sort=best_selling` returns the published catalog ranked by
units sold in the last **30 days** (a fixed constant — cache-friendly, no parameter), never-sold
listings included last. `&sold=true` narrows to listings with ≥1 confirmed unit sold in that window
— the home strip's honesty guard: a store with no sales gets an **empty** result (the strip
collapses) instead of a fabricated "best sellers" list.

## The aggregate (mirror `ReportRepositoryImpl.topProducts`, lines ~173–204)

The reporting slice already codified the semantics — reuse them exactly so the two reads agree:

- **Tables**: `sales_order_line` (has `product_id`, `quantity`) JOIN `sales_order` (has `org_id`,
  `status`, `placed_at`).
- **"Sold" statuses**: `PAID, FULFILLING, FULFILLED, CLOSED` (the reporting `SALE_STATUSES` set —
  DRAFT/PENDING_PAYMENT are not yet money; CANCELLED/EXPIRED never were). Reads *current* status: an
  order that cancels drops out on the next 60s refresh — intended ("stood-up sales").
- **Window timestamp**: `COALESCE(sales_order.placed_at, sales_order.created_at)` ∈
  `[now − 30d, now)` half-open — the reporting `window()` semantics.
- **Metric**: `SUM(quantity)` (units, per the roadmap wording), **not** revenue.
- **Join back to listings**: `product_listing.product_id = sales_order_line.product_id`;
  `UNIQUE(org_id, product_id)` on `product_listing` makes the mapping 1:1 (no fan-out).

## Design

- **`ListingSort.BEST_SELLING`** — new enum constant (like `FEATURED`, but unlike it, a real wire
  value).
- **`StorefrontService.parseSort`** (~line 712): `case "best_selling"` + extend the cause-naming
  400 message (`"…must be one of: newest, price_asc, price_desc, best_selling"`).
- **`ProductListingRepositoryImpl`** (`findByFilters` ~260 / `countByFilters` / `orderFields`
  ~416): the ranking MUST happen **in SQL** — the read paginates with `offset/limit`, so a
  post-fetch sort would corrupt page ≥ 2. Shape: a derived table / lateral aggregate
  (`sold_units` per `product_id`, filtered to the org + statuses + window) LEFT-JOINed to the
  PUBLISHED-filtered listing query; order by `COALESCE(sold_units, 0) DESC, slug ASC` (keep the
  standing `slug ASC` stable tie-break so paging stays deterministic). Never-sold listings appear
  (COALESCE 0), ranked last.
- **`sold=true`** — one more optional B3 predicate (the `featured=true` grammar): value must be
  exactly `true` or absent, anything else → 400 (no silent coercion); AND-composes with
  `q`/`category`/price/`featured`. Semantics: `sold_units > 0` for the same 30-day window. Exists
  for the home strip's collapse-when-empty honesty; the catalog **sort** alone never hides items.
- **Everything else unchanged**: PUBLISHED hard-coded, whitelisted `PublicListingResponse` (NO
  sold-count crosses the boundary — the honest part is the *ordering*, not a displayed number),
  envelope, availability/images/rating enrichment, `Cache-Control: public, max-age=60`,
  `PublicStorefrontServlet` forwards `sort`/`sold` params as-is.
- **Constant**: `BEST_SELLING_WINDOW_DAYS = 30` beside the sort handling; the 90-day variant is a
  one-line change if merchandising ever asks.
- **Indexes**: V66 already ships `sales_order_line(product_id)` and
  `sales_order(org_id, status, created_at, id)`. No new migration; if EXPLAIN ever shows a
  `placed_at` range cost at scale, a `(org_id, status, placed_at)` index is the named follow-up.

## Scope

### In
Enum + parse + error message; the SQL aggregate in `findByFilters`/`countByFilters`/`orderFields`;
the `sold=true` predicate + parse-or-400; `BEST_SELLING_WINDOW_DAYS`; ITs.

### Out
Any migration or materialized view (reporting_reads.md names the MV as the escape hatch — not
needed at current volumes under the 60s edge cache). Personalization / co-purchase (Tier 3, item
10). A displayed sold-count. A `?window=` parameter (fragments the cache key).

## Tests (`BestSellersSortIT`, model: `TaxShippingConfigIT`'s harness or seed rows directly)

1. **Ranking**: three listings with 5 / 2 / 0 units sold (mixed PAID/FULFILLED orders) →
   `?sort=best_selling` orders 5, 2, 0; the zero-sales listing still present, last.
2. **Status discipline**: units on CANCELLED / PENDING_PAYMENT orders don't count.
3. **Window discipline**: an order placed 31+ days ago doesn't count.
4. **Paging in SQL**: with `size=2`, page 0 = top-2, page 1 = the rest — no overlap/omission.
5. **Tie-break**: equal units → `slug ASC`.
6. **`sold=true`**: excludes the zero-sales listing; a no-sales org → empty page (`total: 0`);
   `sold=yes` → 400.
7. **No leak / composition**: rows stay the whitelisted shape (no sold count); DRAFT listing of a
   well-selling product is absent; `sort=best_selling&category=…` composes.
8. Unknown `sort` still 400s, message now lists `best_selling`.

## Definition of done

All eight ITs green; the existing storefront read ITs (search/filters/featured) untouched and
green; spotless clean. No migration, no codegen needed (no schema change).
