# Slice: Reserve stock on placement

> Online flow **TX-1 tightening**. Closes the "no stock yet" gap deliberately left open by
> [Place an online order](./place_online_order.md). Implements the side effect listed in
> `sys-analysis/outbound/state-machines.md` §A1 ("Enter PENDING_PAYMENT → create inventory_reservation
> rows for each line") and follows the concurrency model in `sys-analysis/outbound/reservation.md`.

---

## Goal

Inside the same DB transaction that `SalesOrderService.placeOnlineOrder(...)` already opens — after
the order + lines are inserted, before it commits — atomically:

1. Lock each unique product's `inventory` row (deadlock-safe order).
2. Verify `available = stock_qty - reserved_qty ≥ requested` per product.
3. Bump `inventory.reserved_qty` per product by the per-product total.
4. Insert one `inventory_reservation` row per `sales_order_line` (`status = ACTIVE`).
5. Write one `inventory_log` row per affected product with `reserved_delta = +N`, `reason = RESERVED`.

If **any** line is short, the whole transaction aborts with a `409 Conflict` whose body lists
**every** shortage — never just the first.

The HTTP contract from slice 1 is unchanged: same endpoint, same happy-path response, one new failure mode.

---

## Scope

### In
- New domain `InventoryReservation` (private ctor + `createActive` / `rehydrate` factories, same style as `SalesOrder`).
- New repository `InventoryReservationRepository` + factory (transactional pattern, mirrors `SalesOrderRepository`).
- Extend existing `InventoryRepository` with `lockForUpdate(orgId, productIds)` returning current `(stock_qty, reserved_qty, version)` per row, ordered for stable locking.
- New service `ReservationService.reserveForOrder(DSLContext txDsl, UUID orgId, SalesOrder order, List<SalesOrderLine> lines)`. Runs in the caller's transaction; **does not open its own**.
- New exception in `common`: `InsufficientStockException extends ConflictException` carrying a `List<Shortage>` with `{productId, requested, available}` rows.
- `SalesOrderService.placeOnlineOrder(...)` invokes `ReservationService.reserveForOrder(...)` after lines insert. Idempotent-replay branch **skips** reservation (already done when the original order placed).
- `ApiError` (or a sibling response DTO) surfaces the shortage list in the 409 body so the storefront can show per-line errors.

### Out (deferred to later slices)
- **Release** on cancel / TTL expiry → cancellation + TTL-worker slices.
- **Consume** on fulfillment SHIPPED → fulfillment slice.
- No new HTTP endpoints — this slice is invisible to the API contract surface.
- Auto-creating an `inventory` row when a product has none — treat missing row as 0 stock and reject (matches existing behavior).
- Strict reservation→log lineage column — see "Schema notes" below.

---

## Schema — no migration needed

V19's `inventory_reservation`, V7+V15's `inventory`, V9+V5's `inventory_log` + `stock_reason` enum all carry everything required.

### Schema notes (alignment with the slice description)
- The slice description used `due_to = 'reservation'`. The actual column is **`reason`** and the enum value is **`RESERVED`** (`stock_reason` from V5). The implementation uses those.
- The slice description used `source_id = reservation.id`. `inventory_log` has no `source_id` column; the natural link in the existing schema is **`order_id`**, which the implementation uses. A separate micro-slice can later add `inventory_log.reservation_id` if richer lineage is wanted; not needed for v1.

---

## Concurrency model

Per `reservation.md` §Concurrency: **lock the `inventory` row, not the reservation table. The inventory row is the serialization point; reservations are children.**

```sql
SELECT product_id, stock_qty, reserved_qty, version
  FROM inventory
 WHERE org_id = :orgId
   AND product_id IN (:p1, :p2, …)
 ORDER BY product_id ASC          -- stable lock order
   FOR UPDATE;
```

**Deadlock-safety rule:** sort the distinct product IDs ascending before issuing
`SELECT … FOR UPDATE`. Two concurrent placements that share products will acquire locks in the
same order, so no AB/BA deadlock.

Sequence after the locks are held:

1. Aggregate requested quantities per product (a multi-line order with two lines for the same SKU sums them).
2. Compute `available = stock_qty - reserved_qty` (post-lock current values).
3. **Collect every shortage** before failing. Don't short-circuit on the first — return all of them.
4. If any shortage: throw `InsufficientStockException(shortages)`. Outer tx rolls back.
5. Otherwise, per affected product:
   ```sql
   UPDATE inventory
      SET reserved_qty = reserved_qty + :total,
          version      = version + 1,
          updated_at   = now()
    WHERE org_id = :orgId AND product_id = :pid;
   ```
6. INSERT one `inventory_reservation` row per `sales_order_line` (`status='ACTIVE'`, `expires_at = order.expires_at`).
7. INSERT one `inventory_log` row per affected product:
   `stock_delta = 0`, `reserved_delta = +total`, `stock_after = stock_qty (unchanged)`,
   `reserved_after = reserved_qty + total`, `reason = RESERVED`, `order_id = order.id`,
   `actor_id`/`actor_type` from `SecurityContext`.

One log row per affected product (not per reservation) keeps the log shape consistent with how
SHIPPED and RELEASED will later record their per-product deltas.

---

## Service contract

```java
public final class ReservationService {

  public ReservationService(
      InventoryRepositoryFactory inventoryRepoFactory,
      InventoryReservationRepositoryFactory reservationRepoFactory,
      InventoryLogRepositoryFactory inventoryLogRepoFactory) { … }

  /**
   * Reserve stock for an order. Runs inside the caller's transaction; never opens a new one.
   * Throws InsufficientStockException with the full shortage list if any line is short.
   */
  public List<InventoryReservation> reserveForOrder(
      DSLContext txDsl,
      UUID orgId,
      SalesOrder order,
      List<SalesOrderLine> lines);
}
```

### Plug-in point in `SalesOrderService.placeOnlineOrder`

Between the existing step 7 (`repo.insert(order, orderLines)`) and step 8 (return `Placed`):

```java
reservationService.reserveForOrder(txDsl, orgId, order, orderLines);
```

The idempotent-replay branch (existing `idempotency_key` hit) **must not** call this — the
original placement already created the reservations; replay only returns the cached row.

---

## Error contract

```
HTTP/1.1 409 Conflict
Content-Type: application/json

{
  "status": 409,
  "error":  "Conflict",
  "message": "Insufficient stock",
  "shortages": [
    { "product_id": "22222222-…-201", "requested": 5,  "available": 2 },
    { "product_id": "22222222-…-202", "requested": 10, "available": 0 }
  ]
}
```

`ApiError` gains an optional `shortages` field (omitted when null). The `SalesOrderHandler`'s
existing `AppException` catch already routes 409s through `ApiError.of(...)`; this slice extends
the writer to include `shortages` when the exception is an `InsufficientStockException`.

---

## InventoryReservation domain

```java
public final class InventoryReservation {

  public static InventoryReservation createActive(
      UUID id,
      UUID orgId,
      UUID productId,
      UUID salesOrderLineId,
      int  quantity,
      OffsetDateTime expiresAt,
      OffsetDateTime now);

  public static InventoryReservation rehydrate(…);   // covers ACTIVE / CONSUMED / RELEASED for later slices

  // mutators reserved for later slices:
  //   public void consume(OffsetDateTime now);
  //   public void release(String reason, OffsetDateTime now);
}
```

Same private-ctor + static-factory style as `SalesOrder` / `SalesOrderLine`.

---

## File layout

| Module | New / changed |
|---|---|
| `common` | **New**: `InsufficientStockException` (`extends ConflictException`, status 409), carrying `List<Shortage>` where `Shortage = (UUID productId, int requested, int available)`. |
| `domain` | **New**: `InventoryReservation.java`, `repository/InventoryReservationRepository.java`, `repository/InventoryReservationRepositoryFactory.java`. **Changed**: `repository/InventoryRepository.java` — add `lockForUpdate(orgId, productIds)`. |
| `repository` | **New**: `InventoryReservationRepositoryImpl.java`, `InventoryReservationRepositoryFactoryImpl.java`. **Changed**: `InventoryRepositoryImpl.java` — implement `lockForUpdate` with `ORDER BY product_id ASC FOR UPDATE`. |
| `service` | **New**: `ReservationService.java`. **Changed**: `SalesOrderService` — constructor accepts a `ReservationService`; `placeOnlineOrder` calls it post-line-insert; idempotent branch skips. |
| `api` | **Changed**: `ApiError` — optional `shortages` list. **Changed**: `SalesOrderHandler` writer — surface `shortages` when the exception is `InsufficientStockException`. **Changed**: `AppConfig` — wire `ReservationService` and pass it into `SalesOrderService`. |

No migrations.

---

## Authorization

Unchanged — same `STAFF+` requirement as the slice 1 POST endpoint.

---

## Acceptance criteria

- [ ] Placing a new order with sufficient stock returns `201` and:
  - One `inventory_reservation` row per `sales_order_line`, all `status = ACTIVE`, all with `expires_at = sales_order.expires_at`.
  - `inventory.reserved_qty` for each affected product is bumped by that product's **total** requested quantity; `inventory.version` incremented.
  - One `inventory_log` row per affected product with `reserved_delta = +total`, `stock_delta = 0`, `reason = RESERVED`, `order_id = order.id`.
- [ ] Placing an order where any line exceeds available stock returns `409` with a `shortages` array listing **every** short line — not just the first.
- [ ] On failure, **nothing** persists: `sales_order`, `sales_order_line`, `inventory_reservation`, `inventory_log` row counts are unchanged; `inventory.reserved_qty` and `inventory.version` unchanged. (Asserted by a per-table count snapshot before/after.)
- [ ] Two concurrent placements for the same product racing for the last *N* units: exactly one returns `201`, the other `409` with `available = 0` (or whatever the post-first-commit value is). Never two `201`s.
- [ ] A multi-line order with two lines for the same product: the inventory row is locked **once** and verified against the **sum** of the two line quantities. Two `inventory_reservation` rows are still created (one per line); one `inventory_log` row.
- [ ] A product with no `inventory` row → `409` with `available = 0` (no auto-init).
- [ ] Idempotent replay (same `Idempotency-Key` as a prior successful placement) returns the original order id + number; **no** new `inventory_reservation` rows are created; `inventory.reserved_qty` and `inventory.version` unchanged from the replay call alone.
- [ ] Lock order proof: invoking with `productIds = [B, A]` produces a `SELECT … FOR UPDATE … ORDER BY product_id ASC` ordered `A, B` (verifiable via jOOQ SQL logging or a tracing integration test).

---

## What this unblocks

| Next slice | Depends on this |
|---|---|
| **TTL expiry worker** | Scans `sales_order` for `PENDING_PAYMENT AND expires_at < now()`; releases the order's reservations (`ACTIVE` → `RELEASED`), decrements `inventory.reserved_qty`, writes `RELEASED` `inventory_log` rows. |
| **Cancel pre-PAID order** | Same mechanics as TTL expiry but triggered by user/admin action. |
| **Fulfillment SHIPPED** | Marks the linked reservations `CONSUMED`, decrements **both** `inventory.on_hand` AND `inventory.reserved_qty`, writes a `SOLD` `inventory_log` row. (`fulfillment_line.inventory_reservation_id` already exists in V20 for this lookup.) |
| **Online InstaPay reconcile → Order PAID** | Independent of reservations, but the reservations must already exist (this slice) before fulfillment can proceed. |
