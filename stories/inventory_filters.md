# Slice: Inventory filters (`?q=` with barcode, `?category=`, `?held=`, `?rule=`, `?changed_from/?changed_to`, `?sort=` + a stock summary and stock counts on the stock overview)

> The admin Inventory page is a title, an always-open search box and five stock tabs. The overview
> read ([`InventoryHandler.doGetOverview`](../api/src/main/java/com/loai/inventory/api/servlet/handler/InventoryHandler.java),
> `inventory_reads.md`) takes `q`, `stock`, `low_lte`, `page` and `size`; `q` is a plain `ILIKE` on
> name and SKU — without the Arabic-folded `name_search` key `GET /products` already searches
> (`unicode_normalization.md`) and without the barcode a wedge scanner types. A merchant asking
> "how much Food is on the shelf and what did it cost?", "what has not moved in 90 days?", "which
> products still have no reorder rule?" or "what is held for orders?" has no door in, and the tabs
> are numberless. Orders got `?q=` (`order_search.md`) and Invoices its filters
> (`invoice_filters.md`); this slice gives the stock overview the same treatment with the
> dimensions a shelf is actually read by. Frontend pair: `frontst` story 144 (branch
> `142_feat/inventory-search-filters`; the design canvas `design/inventory-search-filters.html`,
> whose "URL contract" note is this story's contract). Branch `181_feat/inventory-filters`,
> **no migration**.

---

## Today (the gap)

- **Search under-recalls.** `overviewConditions` matches `name ILIKE` / `sku ILIKE` only. The
  folded `name_search` column (V62) and `product.barcode` (V16) sit unused, so "سكر" misses
  "سُكّر" and a scanned code finds nothing.
- **One axis.** `stock` is the only filter, and it is the tab. Category (through the listing,
  V40), `reserved_qty`, `reorder_point` (V94) and `inventory.updated_at` (stamped by every
  restock, sale, hold and release — `InventoryRepositoryImpl.adjustQuantities`) are all there and
  unreachable.
- **One order.** `name ASC` (REORDER: deepest below its point first). No "emptiest first", no
  "what moved last".
- **No counts-only read**, for the reason `order_status_counts.md` fixed on Orders: filling five
  tabs would take five `size=1` list calls.
- **The shelf question is a client sum of one page.** "How many units, worth how much at cost?"
  can only be answered for the 20 rows on screen.

## Goal

**One read answers "which products, how many units, what do they cost?" from whatever the merchant
has** — a name in any Arabic spelling, a SKU fragment, a scanned barcode, a category, the holds,
the reorder rule, a movement window, an order — composing with the stock tab, with a `total` that
equals the rows and a `summary` that adds the same rows up. Plus one cheap read for the tab
numbers.

## Design

### Contract

`GET /api/orgs/{orgId}/inventory?q=&stock=&low_lte=&category=&held=&rule=&changed_from=&changed_to=&sort=&page=&size=`
(VIEWER). Every new parameter is optional; blank is absent.

| param | meaning | can 400 |
| --- | --- | --- |
| `q` | OR of: `name ILIKE '%q%'`; `name_search LIKE '%fold_search(q)%'` (the `ProductRepositoryImpl.searchCondition` precedent — both sides folded by the DB); `sku ILIKE '%q%'`; `barcode = q` **exactly** (a fragment of a barcode is not a hit). Trimmed; whitespace-only = absent. | never |
| `stock` / `low_lte` | the tab and the LOW bound, unchanged (`inventory_reads.md`, `reorder_point.md`). | as before |
| `category` | products whose storefront listing is in the category **or** whose variant child is attached to such a listing (`product_listing` → `product_listing_category`, `product_variant`). Exact category, not descendants. A product with no listing is in no category. | not a UUID |
| `held` | `true` → tracked and `reserved_qty > 0`; `false` → tracked and `= 0`. Untracked rows fall out either way — there is no reserved figure to compare. | not `true`/`false` |
| `rule` | `set` → `reorder_point IS NOT NULL`; `none` → `IS NULL`. Product-side, so untracked rows take part — "which products still lack a rule" is a setup worklist. | other value |
| `changed_from` / `changed_to` | half-open `[from, to)` on `inventory.updated_at`, ISO-8601 date-times (the `/reports` convention). Either side open. Untracked rows fall out. | not a date-time; `from >= to` |
| `sort` | `name` (ASC, the default), `available` (ASC), `on_hand` (`stock_qty` DESC), `updated` (`updated_at` DESC). Every order breaks ties on `(name, id)`; the stock-driven ones put untracked rows (NULL) last. | other value |

**Ordering**: an explicit `sort` wins. Without one the catalog order applies — `name ASC` — except
on the REORDER tab, the one segment that is a queue (`reorder_point.md`): deepest below its own
point first. The other dimensions narrow, never reorder.

**Response**: the `PageResponse` envelope plus `summary`:

```json
{ "data": [ … ], "total": 4, "page": 0, "size": 20,
  "summary": { "products": 4, "units_on_hand": 209, "units_available": 209,
               "costed_products": 3, "cost_value": 6120.00 } }
```

- `products` = every matching row, untracked included (== `total` by construction).
- `units_on_hand` = Σ `stock_qty`, `units_available` = Σ `stock_qty − reserved_qty`, over the
  tracked rows (an untracked row's stock is NULL and `SUM` skips it).
- `costed_products` = tracked rows with a `cost_price`; `cost_value` = Σ `stock_qty × cost_price`
  over those rows, scale 2, `0.00` when none is costed. `cost_value` crosses **only with manager
  authority** — the `cost_price` rule of `product_cost_and_margin.md`; the null-omission mapper
  drops the key for a STAFF/VIEWER. `costed_products` always crosses so the frontend can qualify
  a partial figure ("at cost, 3 of 4 costed").
- All computed in the **same query** (`InventoryRepository.statsOverview`) with the **same
  predicate** as the rows; the pager's `total` is that query's count.

`GET /api/orgs/{orgId}/inventory/stock-counts?low_lte=` (VIEWER) —
`{ "all": 128, "low": 9, "reorder": 4, "out": 3, "untracked": 6 }`. Flat, keyed by the frontend's
segment names; all five present (`0` included); org totals, unfiltered by search or the sheet (the
status-counts convention). `low` is at the caller's `low_lte`, default 5 like the LOW tab. Routed
as a fixed segment before the `{productId}` parse; `POST` → 405.

### The predicate (`InventoryRepositoryImpl.overviewConditions(orgId, filter)`)

One definition for `listOverview`, `countOverview` and `statsOverview`; `stockCounts` restates the
five `stock` arms as `COUNT(*) FILTER (WHERE …)` over the same LEFT JOIN, so a tab's chip and its
list's total are the same rows. The filter is one value, `InventoryListFilter` (domain): `q, stock,
lowLte, categoryId, held, rule, changedFrom, changedTo, sort`. The service keeps the pre-slice
`listOverview(orgId, q, stock, lowLte, page, size)` as a delegate (`InventoryListFilter.of`).

The category leg is an `EXISTS` over `product_listing ⋈ product_listing_category` scoped to the org
and the id, joined to the product either directly (`product_listing.product_id = product.id`) or
through `product_variant` (`product_variant.product_listing_id = product_listing.id AND
product_variant.product_id = product.id`) — a variant is a child `product` row
(`catalog_variants_model.md`), and its shelf belongs to its listing's category.

### Why the summary rides the list

The worklist shows "4 products match · 209 on hand · EGP 6,120.00 at cost" under its applied
filters. That line must be the **whole** set's and must agree with `total` — so it comes from the
one aggregate the pager already needs, never from a second endpoint that could read a different
instant.

### Indexes

No new index. The overview is an org-scoped LEFT JOIN over the tenant's own catalog; the new legs
are filters over the same rows. `product_name_search_trgm_idx` (V62) backs the folded leg and
`plc_category_idx` (V40) the category `EXISTS`. A `(org_id, updated_at)` on `inventory` for the
movement window is deferred until an EXPLAIN on the perf seed says it is needed
(`docs/query-index-audit.md` discipline).

## Errors

- Unknown `stock` → 400 (unchanged); `held` outside `true|false`, `rule` outside `set|none`,
  `sort` outside `name|available|on_hand|updated` → 400.
- `category` not a UUID → 400. An unknown or foreign-org UUID is not an error: it selects nothing.
- `changed_from`/`changed_to` not ISO-8601 date-times (a bare `2026-08-01` is refused, as on
  `/reports`) → 400; `changed_from >= changed_to` → 400.
- `low_lte` non-integer or negative → 400 (unchanged; also on `/stock-counts`).
- `q` cannot fail; no match is `data: [], total: 0, summary: {0, 0, 0, 0, 0.00}`.

## Tests

- **`InventoryFiltersIT`** (service against the real jOOQ repositories, Testcontainers Postgres
  17): `q` finds "سُكّر أبيض" from "سكر" through `name_search`, a SKU case-insensitively, and a
  barcode exactly but never a fragment of one; `category` selects the listing's product and its
  variant child, never an unlisted product or another org's category, and composes with the
  UNTRACKED tab; `held` partitions by `reserved_qty` (seeded through a real reservation) and drops
  untracked rows; `rule` partitions by `reorder_point` including untracked products, and
  `rule=none` on the REORDER tab is empty; the changed window is half-open on `updated_at`
  (boundary row belongs to the FROM side) and drops untracked rows; the four sorts, untracked last,
  and an explicit sort overriding the REORDER queue order; the summary counts every row, sums
  tracked units, values costed rows only (`30 × 12.50 = 375.00`, `0.00` when none) and narrows with
  `q`; the stock counts equal every tab's total at the given bound, the bound is the caller's, and
  null defaults to 5.
- **`InventoryReadsHandlerAuthTest`**: the LOW tab still reaches the service as one
  `InventoryListFilter`; all seven new parameters are parsed (trimmed `q`, UUID, boolean, enums,
  the window); the VIEWER envelope carries `summary` without `cost_value` and the MANAGER one with
  it; `category=food`, `held=yes`, `rule=maybe`, `sort=price`, a prose date and an inverted window
  are 400s with the service never called; `GET /stock-counts` is allowed for VIEWER, routed before
  the id parse and passes `low_lte`; anon → 401; `POST /stock-counts` → 405.
- **`InventoryReadsServiceIT`**, **`LowStockNotificationIT`**, **`CatalogVariantsIT`** unchanged
  in substance (calls go through the delegate overload).

## Out of scope

- Descendant categories ("Drinks" including "Tea"): exact category only, until the tree earns it.
- A "last sale" window from `inventory_log` (reason = SALE): the `updated_at` proxy covers "no
  stock change since"; a reason-specific window would need a correlated aggregate per row.
- Filtering the tab counts by the sheet: counts are org totals, as on Orders and Invoices.
- A price band (`base_price`): a shelf is read by quantity and cost, not by list price.
