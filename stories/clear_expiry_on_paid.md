# Fix: clear the payment deadline when the order is paid (the "Expired but still held" lie)

> Reported by the owner from the admin UI: a paid order awaiting packing shows its stock holds as
> **"Expired" and "held" at the same time**. The data never corrupts — the sweeper's flip is
> guarded (`markExpiredIfPending`: `WHERE status='PENDING_PAYMENT'`), so a PAID order is never
> expired and its ACTIVE reservations correctly keep the stock — but every read serves a **dead
> deadline**, and the frontend renders it: `OrderStockHolds` / `StockHolds` hand any ACTIVE
> hold's `expires_at` to `ExpiryCountdown`, which renders a danger **"Expired"** badge once the
> moment passes. The operational risk is a merchant reading "Expired" as "stock freed" and
> re-selling or not packing stock that is firmly committed to a paid order.

---

## Root cause

Two columns hold the payment-hold deadline and **neither is cleared when payment arrives**:

- `sales_order.expires_at` — set at reserved ONLINE/PHONE placement. `SalesOrder.markPaid` sets
  only status/prepaid/updatedAt, and `updatePaymentState` never writes `EXPIRES_AT`, so a PAID
  order carries the stale deadline forever.
- `inventory_reservation.expires_at` — V19's comment defines it as *"mirrors
  SalesOrder.expires_at while ACTIVE"*, but it is stamped once at placement
  (`InventoryReservation.createActive`) and no code path ever revisits it. The mirror contract
  breaks at the first order transition that keeps holds alive: PENDING_PAYMENT → PAID.

No business logic reads either value after the paid flip (the sweeper candidate query is
status-guarded; `expired-pending-orders` on the platform plane likewise) — the columns are
display-only from that point, which is why the bug is an honesty defect, not a stock defect.

## The fix — maintain the mirror at the flip, in the same transaction

1. **Domain:** `SalesOrder.markPaid` nulls `expiresAt` — a paid order has no payment-hold window;
   the deadline ceases to exist as a fact, so the row stops asserting it. The DRAFT → PAID
   in-store fast path already carries null (never reserved); `addPrepayment` (UNDERPAID)
   deliberately does **not** clear it — a partially-paid order is still expirable, per the
   documented v1 behavior, and its countdown is true.
2. **Persistence:** `updatePaymentState` gains `.set(EXPIRES_AT, order.getExpiresAt())`. Safe for
   all three callers: PaymentService's branches write the freshly-nulled (PAID) or untouched
   (UNDERPAID) value; FulfillmentService's PAID → FULFILLING flip and the in-store persist
   round-trip an already-null value (hydration maps `expires_at`, verified).
3. **The reservation mirror:** new `InventoryReservationRepository.clearExpiryForOrder(orderId)`
   — `UPDATE inventory_reservation SET expires_at = NULL WHERE status='ACTIVE' AND expires_at IS
   NOT NULL AND sales_order_line_id IN (order's lines)` — called by `PaymentService` on the
   MATCHED and OVERPAID branches right after `updatePaymentState`, inside the reconcile
   transaction, so the order and its holds can never disagree. `PaymentService` gains the
   **narrow** `InventoryReservationRepositoryFactory` (not `ReservationService` — the clear moves
   no stock and writes no inventory/log rows; the lifecycle service stays the owner of the moves
   that do).
4. **V84 backfill (data-only, no schema change):** null `sales_order.expires_at` on
   PAID/FULFILLING/FULFILLED/CLOSED rows, and `inventory_reservation.expires_at` on ACTIVE holds
   whose order is off PENDING_PAYMENT — heals every in-flight paid-but-unpacked order on deploy.
   EXPIRED and CANCELLED orders keep their historical deadline on purpose (it is the fact that
   fired / the fact that never got the chance; their holds are RELEASED atomically by those
   flips, so no ACTIVE-hold surface ever renders it).

**Frontend needs no change**: both hold widgets already render the countdown only when
`expires_at` is present; once the wire stops carrying the lie the badge disappears, and
`ExpiryCountdown`'s hit-zero `router.refresh()` (built for the genuine pending-expiry race) stops
firing pointlessly on paid orders' pages. The e2e mock's paid-order hold fixtures are synced in a
frontst companion so the harness tells the same truth.

## Errors

None new — no endpoint, DTO, or status-code change. `SalesOrderResponse.expires_at` is
null-omitted, so PAID orders simply stop carrying it.

## Tests

- **The reported scenario, end-to-end** (IT): place a reserved online order (deadline + mirrored
  holds), reconcile an exact cover → order PAID with `expires_at` NULL **and** its holds ACTIVE
  with `expires_at` NULL; then run the sweeper past the original deadline → the paid order is
  untouched and its holds stay ACTIVE (the ground-truth invariant `reserved_qty == Σ ACTIVE`
  asserted throughout).
- **OVERPAID branch** (IT): same clearing behavior.
- **UNDERPAID contrast** (IT): a partial payment keeps the deadline on both tables, and the
  sweeper still expires + releases it — the deliberate v1 behavior, pinned so this fix can't
  silently widen.
- The domain behaviors (`markPaid` nulls `expiresAt`, `addPrepayment` leaves it) are pinned by
  the same IT's DB assertions — there is no domain test module to house a unit twin.
- V84's backfill is two idempotent UPDATEs over statuses the new write-path can no longer
  produce; exercised as a no-op by every IT's Flyway run, not separately seeded.

**Gates (run 2026-08-15):** `PaidOrderDeadlineIT` 3/3 · touched-path api ITs **142/142** (expiry
× 3 classes, payments × 7, orders × 2, checkouts × 2, notifications × 2, in-store) · service
243/243 + repository suites green · full `test-compile` green (the constructor gained the narrow
factory; 22 IT construction sites updated mechanically + the `OrderExpiryServiceIT` poison-fake
implements the new method by delegation) · spotless applied.

## Out of scope / recorded

- **Dropping `inventory_reservation.expires_at` for a live join** was considered and rejected
  here: it eliminates the mirror class of bug but costs a schema change + codegen + reshaping two
  read queries and the row DTO sourcing, for a column that is now correctly maintained at its one
  write site. If a second mirror-drift bug ever appears, that is the escalation.
- **Expiry of UNDERPAID orders** (money held, order expired, no automatic refund surfaced)
  remains the documented v1 behavior — a separate product decision, not smuggled into this fix.
- `ExpiryCountdown`'s refresh-on-zero stays — with truthful data it fires only in the genuine
  pending-expiry race it was built for.
