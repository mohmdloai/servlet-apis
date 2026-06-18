# Slice: Ship a fulfillment (SHIPPED transition)

> Outbound flow **TX-5** per the [fulfillment design](../sys-analysis/outbound/fulfillment.md).
> Continues the chain from [`accept_online_payment.md`](accept_online_payment.md): an order that
> reached `PAID` now physically ships — this is where stock actually leaves the warehouse.

---

## Goal

Staff create a `Fulfillment` covering a subset of a **PAID** order's lines, then mark it **SHIPPED**.
Shipping is the single most consequential transition in the outbound flow: it is where reservations
become real stock movements.

- `POST /api/orgs/{orgId}/fulfillments` — create a PENDING fulfillment from a paid order's lines.
- `POST /api/orgs/{orgId}/fulfillments/{id}/ship` — mark it SHIPPED.

Done means: I can ship part of a paid order; `inventory.on_hand` drops in real time.

---

## Scope

### In
- New domain: `Fulfillment`, `FulfillmentLine`, `FulfillmentStatus` enum. `InventoryReservation`
  gains a `consume(now)` mutator (ACTIVE → CONSUMED).
- New repositories: `FulfillmentRepository` (+ factory). Two new methods on
  `InventoryReservationRepository` (`findByIds`, `markConsumed`).
- New service `FulfillmentService` with `create(...)` and `ship(...)`, each owning its own DB
  transaction.
- New handler `FulfillmentHandler` registered under `"fulfillments"`, routing `POST /fulfillments`
  and `POST /fulfillments/{id}/ship`. Both require **STAFF** (system ADMIN bypasses).

### SHIPPED side effects (all in one transaction)
For each fulfillment line:
1. Write a `-stock` row to `inventory_log` (`reason = SOLD`, `order_id = sales_order.id`,
   `stock_delta = reserved_delta = -qty`).
2. Mark the linked `inventory_reservation` (via `fulfillment_line.inventory_reservation_id`) as
   **CONSUMED**.
3. Decrement `inventory.stock_qty` and `inventory.reserved_qty` by the same amount.

Net effect: `available (= stock_qty - reserved_qty)` is unchanged, but `on_hand` drops — the goods
have left the building. The order moves **PAID → FULFILLING** on its first shipment.

### Out (deferred)
- **Whole-line shipping only.** Each fulfillment line consumes one order line's single ACTIVE
  reservation in full, so "ship part of the order" means a subset of *lines* — the canonical
  partial-fulfillment shape in the design doc. Partial-quantity-within-a-line (the short-ship edge
  case) is a later slice.
- **No DELIVERED / CANCELLED / FAILED transitions.** No SalesInvoice issuance, no payment
  allocation, no `FULFILLED` aggregate roll-up (those hang off DELIVERED). The order stays
  `FULFILLING` even after the last line ships.
- **No in-store flow** (synchronous DELIVERED at checkout).
- **No carrier API / tracking webhooks** — `carrier` / `tracking_number` are free-text, optional.

---

## Concurrency

- `ship` locks the **order** `FOR UPDATE`, so concurrent shipments of the same order serialize — the
  loser re-reads the now-CONSUMED reservation and is rejected (409) rather than double-decrementing.
- The fulfillment row itself is also locked `FOR UPDATE`; a double `POST /ship` on one fulfillment
  fails the PENDING guard (409).
- Inventory rows are locked in `product_id ASC` order (the project-wide deadlock-safety rule),
  matching reservation/placement lock discipline.
- `markConsumed` filters on `status='ACTIVE'`; if the affected count ≠ expected, the whole ship
  rolls back rather than shipping phantom stock.

---

## Over-fulfillment guard

At create time, `SUM(fulfillment_line.quantity)` across non-CANCELLED fulfillments of a line, plus
the requested quantity, must not exceed `sales_order_line.quantity`. A line whose reservation is
already CONSUMED has no ACTIVE reservation to claim, so re-fulfilling it is rejected (400).

---

## Tests

`api/src/test/java/.../fulfillment/FulfillmentShipIT.java` (TestContainers Postgres, drives the
service directly):
- ship a full line → `on_hand` −qty, `reserved` −qty, reservation CONSUMED, order FULFILLING, one
  SOLD `inventory_log` row with the right deltas.
- ship one line of two → only that product decrements; the other stays reserved; order FULFILLING;
  ship the rest → still FULFILLING.
- re-ship an already-SHIPPED fulfillment → 409, stock decremented only once.
- create a fulfillment for an unpaid (`PENDING_PAYMENT`) order → 400.
- re-fulfill an already-shipped line → 400 (no ACTIVE reservation left).
