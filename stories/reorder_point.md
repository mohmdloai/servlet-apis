# Slice: Reorder point — a threshold on the product, a `LOW_STOCK` notification off the sale, a replenishment worklist

> Item 2 of the owner's gap table: *"Per-product reorder point, low-stock notification, reorder
> worklist — Odoo reorder rules, Loyverse low stock — the notification supertable and staff feed
> exist; the sale transaction already touches stock — S–M."* The mandate: **reuse the existing
> notification system and the existing inventory mutation performed by sales**; do not build a
> parallel alerting mechanism. So: one nullable column on `product`, one new `NotificationType`
> raised by the two places a sale already decrements sellable stock, and one more value on the
> stock-overview `?stock=` filter. No `low_stock_event` table, no scheduler, no second feed.
> **Branch `175_feat/reorder-point` off `master`, migration V94.** Frontend pair:
> `frontst/stories/138_st_reorder_worklist.md`.
>
> **Built 2026-09-04.** V94 + codegen; `Product.reorderPoint` through domain/repo/DTOs
> (`ReorderPointChange` on the service so the pre-V94 overloads cannot clear it);
> `NotificationType.LOW_STOCK` + EN/AR templates; `LowStockNotifier` (with
> `ProductRepository.findByIds`) wired into `ReservationService.reserveForOrder` and the in-store
> path of `FulfillmentService`, both via a new constructor overload; `InventoryStockFilter.REORDER`
> with the deepest-first order; perfdb hand-migrated V93→V94. Tests: `LowStockNotificationIT` 11 ·
> `InventoryReadsServiceIT` +1 (8) · `ProductHandlerReorderPointTest` 3 · `NotificationTemplatesTest`
> covers the new type (26) — all green; `service` 277 + `api` 304 unit tests; `InStoreSaleIT`,
> `ProductCostIT`, `PlacementIdempotencyRaceIT`, `SaleCostSnapshotIT`, `NotificationDeliveryIT`,
> `PublicCheckoutIT` re-run green.

---

## Goal

1. **A threshold on the product.** `product.reorder_point INT NULL` — the merchant's "tell me
   when I'm down to N". `NULL` means no rule (the default for every existing product); `0` means
   "tell me when it's gone". Carried on `ProductResponse` and the inventory overview row, set
   through the ordinary product `POST`/`PUT`.
2. **A notification when a sale crosses it.** The two mutations that reduce *sellable* stock on a
   sale — the reservation at online/phone placement and the in-store sale's decrement — raise
   **`LOW_STOCK`** to the org's staff (in-app, the `ORDER_PLACED` fan-out) when a product's
   available quantity goes from above the threshold to at or below it, inside the same
   transaction, so a rolled-back sale tells nobody.
3. **A replenishment worklist.** `GET /inventory?stock=reorder` — the products whose available
   quantity sits at or below their own reorder point, deepest below first. The same predicate the
   notification fires on, so the feed and the list can never disagree.

Done means: a merchant sets *reorder at 5* on notebooks, a cashier sells the sixth-to-last, every
staff member's bell shows "Low stock: Notebooks — 5 left, reorder point 5", and the Reorder tab
lists notebooks until a restock lifts it out.

---

## What exists, what is missing

- `product` has no threshold. The overview's `?stock=low&low_lte=N` (story `inventory_reads.md`)
  is a **client-supplied** bound — "your alert level", one number for the whole shop, persisted
  in the browser. Nothing on the server knows that notebooks reorder at 5 and pens at 50.
- Both `stock=low` and `stock=out` compare **`available = stock_qty − reserved_qty`**, tracked
  rows only. That is the number a merchant can still sell, and the reorder rule uses it too.
- A sale reduces available stock in exactly two places, both already holding the inventory row
  lock and both already writing the `SOLD`/`RESERVED` ledger row:
  `ReservationService.reserveForOrder` (online/phone placement — `reserved +q`, so available
  drops at placement) and `FulfillmentService`'s in-store delivered path (`stock −q`, reserved
  unchanged). A **ship** moves `stock` and `reserved` down together and leaves available where it
  was; a **release** (cancel, expiry) raises it. Neither is a sale reducing what can be sold.
- `NotificationService.notifyOrgStaff(txDsl, orgId, type, payload, sourceType, sourceId,
  linkTarget)` fans one row per active STAFF/MANAGER/OWNER, in-app for USER recipients, honouring
  each member's opt-out preferences, rendered from `NotificationTemplates` in the member's locale.
  `NotificationType` is a Java enum stored as open TEXT, so a new type needs no migration.
- `GET /inventory` LEFT JOINs `product` → `inventory`, so a product column is one `SELECT` away
  from the overview row.

Missing, therefore: the column, the type + template, the crossing check at the two sites, the
filter value. Nothing else.

---

## Why this shape

### The threshold lives on the product, and compares against *available*

Odoo's reordering rule is per product per location on the *forecast*; Loyverse's low-stock flag
is per item on *in stock*. Here there is one location and the honest "what can I still sell" is
`available` — it is what `stock=low` and `stock=out` already measure, and what the storefront's
`in_stock` answers. Comparing against `stock_qty` instead would let a shop with 10 on hand and 9
reserved for orders sit "fine" at reorder point 5 while a walk-in cannot buy a single unit.
The column sits on `product` because that is where the merchant describes a product (price,
cost, barcode) and the product form is the one edit surface; the inventory row is the quantity,
not the policy.

### The notification fires on the crossing, not on the state

*"When a sale causes stock to reach or fall below that threshold"* is an edge:
`before > reorder_point && after <= reorder_point`. Firing on the state instead (`after <= rp`)
would raise `LOW_STOCK` on every sale of a product already below its point — thirty
notifications for thirty pens sold from a low shelf. The edge fires once per descent; a restock
that lifts available above the point re-arms it, so the next descent fires again — which is what
a merchant who decided *not* to reorder last week wants to hear. A release (cancel/expiry) that
lifts available above the point re-arms it the same way, and a re-descent through a new sale
fires; that is honest, if occasionally chatty in a cancel-heavy shop, and it needs no dedupe
state. There is deliberately **no** "already notified" flag anywhere — the ledger and the feed
are the record.

### Only the two sale sites raise it

A stocktake (`STOCKTAKE`), a manual adjust, a counter return's restock and a failed-fulfillment
return all move stock too, and a count that discovers a shelf is empty is arguably low stock. The
mandate scopes this to the sale, and the sale is where the merchant is not looking — an operator
typing an adjustment is already staring at the number. The check is one method
(`LowStockNotifier.afterSale`) taking the per-product before/after pairs the two sites already
hold; wiring a third site later is one call. Filed under *Out*.

### The worklist is a filter value, not a new read

`stock=reorder` joins the family `out | low | tracked | untracked` on the existing overview: same
`PageResponse`, same row shape (now carrying `reorder_point`), same `q` composition. **One
deliberate deviation:** the overview is catalog-ordered (`name ASC`) because it is a catalog view;
the reorder segment is a worklist and orders **deepest below first** —
`(available − reorder_point) ASC, name ASC` — so the empty shelf is at the top of a long list.
No other segment changes order.

### Why not a count on `/health`

The dashboard strip could carry `low_stock_products`. The worklist's `total` is that number, the
strip is MANAGER-gated while the worklist is VIEWER, and adding a tile is a frontend decision the
pair story makes. Not here.

---

## Contract

### Migration V94

```sql
ALTER TABLE product ADD COLUMN reorder_point INT
    CONSTRAINT product_reorder_point_non_negative CHECK (reorder_point >= 0);
```

Nullable, no default, no backfill, no index (the filter is per-org over an already-indexed join,
and `NULL` rows fall out of the predicate).

### Product

- `POST /products` and `PUT /products/{id}` accept **`reorder_point`** (integer ≥ 0, or `null`).
  A negative value → 400 `"reorder_point must be >= 0"`. On `PUT` the field is **full-replace like
  `barcode`**: absent or `null` clears it. (Not tri-state like `cost_price` — that exists because
  STAFF cannot see the cost; every role sees the reorder point and the form always sends it.)
- `ProductResponse` carries `reorder_point` when set (Jackson omits null).

### Inventory overview

- `GET /inventory?stock=reorder` — tracked rows with `product.reorder_point IS NOT NULL AND
  (stock_qty − reserved_qty) <= reorder_point`, ordered `(available − reorder_point) ASC, name
  ASC, product_id ASC`. Composes with `q`. `low_lte` is ignored for this value (it belongs to
  `low`). Unknown values still → 400 naming all five.
- Every overview row carries **`reorder_point`** when the product has one (tracked or not).

### `LOW_STOCK` notification

Raised by `LowStockNotifier.afterSale(txDsl, orgId, moves)` where each move is
`(productId, availableBefore, availableAfter)`, from:

- `ReservationService.reserveForOrder` — after the per-product `reserved` writes.
- `FulfillmentService` in-store delivered path — after the per-product `stock` writes.

For each product with a non-null reorder point and `availableBefore > rp && availableAfter <= rp`
→ `notifyOrgStaff(txDsl, orgId, LOW_STOCK, payload, "product", productId,
"/orgs/{orgId}/inventory/{productId}")` with payload
`{product_id, name, sku, available, reorder_point}`. Template (EN): title *"Low stock: {name}"*,
body *"{available} left of {name} ({sku}) — reorder point {reorder_point}."*; Arabic alongside.
Products are batch-loaded once per sale (one query for every product the sale touched).

Not raised by: ship (available unchanged), release/cancel/expiry, restock, adjust, stocktake,
returns. Not raised when the reorder point is `NULL`, when the product was already at or below it
before the sale, or when the sale rolls back (shortage → the whole placement rolls back, and the
notification rows with it).

---

## Out (deferred, each with its shape)

- **Crossings from non-sale decrements** (stocktake, adjust) — one more `afterSale` call from
  `InventoryService.adjust` with the locked before/after; not wired because the operator is
  looking at the number, and the mandate names the sale.
- **A `low_stock_products` count on `/health`** — one more `COUNT` over the same predicate; the
  pair story decides whether the dashboard wants a tile.
- **Email/WhatsApp for staff** — `channelsFor(USER)` is in-app only, platform-wide; changing it
  is a notification-plan decision, not a reorder-point one.
- **Reorder quantity / supplier** (Odoo's max, Loyverse's "optimal stock") — there is no purchase
  order or supplier here; a quantity to order is a number a PO slice would own.
- **Dedupe across a cancel–resell churn** — a per-product "notified at" stamp would silence the
  re-descent; rejected above.

---

## Tests

- `LowStockNotificationIT` (Testcontainers, real `NotificationService`): in-store sale crossing
  fires one `LOW_STOCK` per active staff member with the payload and link · reservation crossing
  at placement fires · **ship does not fire** (available unchanged) · already-below sale does not
  fire · above-after sale does not fire · `NULL` point never fires · `rp = 0` fires on reaching
  zero · a restock above the point re-arms and the next crossing fires again · a release then
  re-descent fires again · a shortage rolls the whole sale back and leaves no notification row ·
  two lines of one product in one sale are one crossing, one notification.
- `InventoryReadsServiceIT` additions: `stock=reorder` selects only tracked rows at/below their
  own point, orders deepest-below first, composes with `q`, ignores `low_lte`; rows carry
  `reorder_point`.
- `ProductHandler`/service tests: `reorder_point` round-trips on create and update, `null` clears
  on update, negative → 400.
- Existing `InStoreSaleIT`, `ReservationService` tests and `RestockIdempotencyIT` stay green.

---

## Definition of done

- [x] V94 applied, codegen re-run; `Product.reorderPoint` threaded through domain/repo/DTOs.
- [x] `NotificationType.LOW_STOCK` + templates (EN/AR); `LowStockNotifier` wired at the two sites.
- [x] `InventoryStockFilter.REORDER` + repository predicate/order; overview row carries the point.
- [x] Tests above green; `service` + `api` batteries green.
- [x] CLAUDE.md: the products line, the overview line, the notification types line.
- [x] perfdb hand-migrated to V94 (a nullable column — the standing procedure).
