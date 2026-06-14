# Slice: Expire pending orders

> Online flow **post-TX-1 reversal**. Closes the open clock left running by
> [Reserve stock on placement](./reserve_stock_on_placement.md): when nobody pays inside
> `expires_at`, an order's reservations have to come back as free stock — automatically. Implements
> the *Strategy 1 — TTL on the SalesOrder* design in `sys-analysis/outbound/reservation.md` §Expiry
> ("a scheduled job moves PENDING_PAYMENT orders past `expires_at` to EXPIRED, which in turn
> releases their reservations").

---

## Goal

A scheduled job, ticking on a fixed cadence, finds every `sales_order` row where
`status = 'PENDING_PAYMENT' AND expires_at < now()` and, per order, atomically:

1. Flips the order to `EXPIRED` (`expired_at = now()`).
2. Marks every `ACTIVE` `inventory_reservation` for the order as `RELEASED`
   (`released_at = now()`, `released_reason = 'EXPIRED'`).
3. Decrements `inventory.reserved_qty` for each affected product by the sum of those releases.
4. Writes one `inventory_log` row per affected product
   (`stock_delta = 0`, `reserved_delta = -total`, `reason = RELEASED`,
    `order_id = order.id`, actor `SYSTEM/order-ttl-sweeper`).

**Done means:** an order whose `expires_at` is in the past is found by the sweeper on its next
tick, flipped to `EXPIRED`, and its stock returns to `available` for new placements.

---

## Why a slice before payment lands

Money has no part in this slice — but writing the expiry path *after* payment exists is much
harder. The races that matter (order being paid at the exact moment the sweeper fires; sweeper
seeing a row a sibling already processed; partial release on crash mid-flush) are easier to design
against in isolation than retro-fitted alongside payment reconciliation.

---

## Scope

### In
- **Scheduler**: JobRunr (`org.jobrunr:jobrunr`) wired into `AppConfig` / `AppBootstrap`. A single
  recurring job, id `order-ttl-sweeper`, cron `*/30 * * * * *` (every 30 s — configurable).
- **JobRunr storage**: backed by the same PostgreSQL datasource as the rest of the app; JobRunr
  manages its own tables (see "JobRunr storage" below).
- **Domain mutator**: new `InventoryReservation.release(String reason, OffsetDateTime now)` —
  guards `status == ACTIVE`, sets `released_at`, `released_reason`, `status = RELEASED`.
- **Repository extensions**:
  - `SalesOrderRepository.findExpiredPendingIds(int limit) : List<UUID>` — global across orgs,
    ordered by `expires_at ASC`, hits the V17 partial index `idx_so_pending`. **Returns IDs only,
    not entities**, for two reasons: (a) the sweeper passes the id to `expireOnePending(UUID)`
    which re-reads under lock anyway, so the projection has nothing to contribute; (b) any
    in-memory snapshot of the order is stale the moment we read it (a payment could land in the
    same millisecond), so projecting more than the id is a load-bearing lie. jOOQ shape:
    `ctx.select(SALES_ORDER.ID).from(SALES_ORDER).where(...).orderBy(SALES_ORDER.EXPIRES_AT.asc()).limit(:n).fetchInto(UUID.class)`.
  - `SalesOrderRepository.markExpiredIfPending(UUID orderId, OffsetDateTime now)` — atomic
    `UPDATE sales_order SET status='EXPIRED', expired_at=:now WHERE id=:id AND status='PENDING_PAYMENT'`,
    returning the affected row count (0 means a sibling beat us / order was paid first).
  - `InventoryReservationRepository.findActiveByOrderId(UUID orderId)` — load every ACTIVE
    reservation for an order, ordered `product_id ASC` for stable-lock discipline.
  - `InventoryReservationRepository.markReleased(Collection<UUID> ids, String reason, OffsetDateTime now)`
    — bulk update inside the per-order txn.
- **Reuses (not new)**: `InventoryRepository.lockForUpdate(UUID orgId, Collection<UUID> productIds)`
  — already in the codebase from slice 2. Issues `SELECT … FOR UPDATE ORDER BY product_id ASC` and
  returns `Map<UUID, Inventory>` keyed by product id. The per-order release txn uses **this exact
  method** for the locking read — we deliberately do **not** introduce a sibling `findForUpdate…`
  primitive. The lock-and-aggregate idiom on `inventory` is identical for reserve and release;
  splitting it across two methods would let one drift from the other.
- **Service**: new `OrderExpiryService` with two methods:
  - `int expireOnePending(UUID orderId)` — single-order expiry, runs in its **own** short
    transaction (`rootDsl.transactionResult(cfg -> { … })`); returns 1 if it flipped, 0 if the
    order was already terminal / paid (idempotent "noop"). Throws no exception in the noop case —
    sibling-already-won is normal. Throws on infrastructure failure (lock timeout, constraint
    violation); the caller must catch.
  - `Summary sweep(int batchLimit)` — finds expired candidates, calls `expireOnePending` per id;
    accumulates counts. Holds **no** transaction itself — one transaction per order. **Catches and
    logs every per-order exception** so a single poison order can't poison the whole batch (see
    "The poison-pill rule" below).
- **Sweeper job class**: `OrderTtlSweeperJob` with a `@Job(name = "order-ttl-sweeper")`-annotated
  `run()` method; instantiated and registered in `AppConfig`. JobRunr's `BackgroundJobServer` calls
  `run()` on schedule and handles retries on transient failure.
- **Manual trigger**: `POST /api/admin/sweep` — `SYSTEM_ADMIN`-only endpoint that invokes
  `OrderExpiryService.sweep(batchLimit)` synchronously and returns the counts. Useful for ops and
  for the acceptance-criteria integration test.
- **Boot wiring**: `AppBootstrap.contextInitialized` starts the JobRunr `BackgroundJobServer`;
  `contextDestroyed` stops it cleanly before the connection pool closes.

### Out (explicitly deferred)
- **Cancel pre-PAID order** — same release mechanics, but triggered by user/admin instead of the
  clock. Separate slice once the cancel endpoint is designed.
- **Fulfillment SHIPPED** consuming reservations — also uses `release(...)` plumbing's sibling
  `consume(...)` but lives in the fulfillment slice.
- **Per-org sweep tuning** — v1 uses one global recurring job. Per-org schedules can come later if
  one tenant's order volume makes the global tick too coarse.
- **Reservation-clock independent of order clock** — we follow `reservation.md` Strategy 1: the
  reservation has no separate TTL; releasing it is purely a downstream effect of the order
  expiring. (Strategy 2 — `reservation.expires_at` independently — explicitly rejected there.)
- **Restock to `stock_qty`** — expiry returns reserved units to *available*, not to *on-hand*. Only
  `reserved_qty` moves; `stock_qty` is untouched. (See "Side-effect details".)
- **Notifying the customer** that their order expired — out of scope; the storefront can poll the
  order status.

---

## Schema

No new business-table migrations. **One new partial index** for the sweeper, plus the JobRunr
storage tables — all in a single migration (V29).

The reservation table (V19) already carries `released_at` / `released_reason TEXT` columns and the
CHECK constraint that enforces `(status='RELEASED' AND released_at IS NOT NULL)`. The
`stock_reason` enum (V5) already includes `RELEASED`. The `sales_order` table (V17) already has
`expired_at` and a partial index `idx_so_pending` on `(org_id, expires_at) WHERE status='PENDING_PAYMENT'`
— good for per-org lookups, but its leading `org_id` column means a cross-org `ORDER BY expires_at`
candidate query has to scan-and-sort, not use the index for ordering. The sweeper is global, so we
add a sweeper-friendly companion index below.

### V29 — `V29__Create_jobrunr_tables_and_sweeper_index.sql`

Two unrelated additions sharing one migration for sequencing convenience.

**1. Sweeper-friendly partial index** for `findExpiredPendingIds`:
```sql
CREATE INDEX idx_so_pending_global ON sales_order (expires_at)
    WHERE status = 'PENDING_PAYMENT';
```
Leading column is `expires_at`, so the cross-org `WHERE status='PENDING_PAYMENT' AND expires_at <
now() ORDER BY expires_at ASC LIMIT :n` can do an index-ordered scan and stop at `:n` rows — no
sort step, no full partial-index scan. The existing `idx_so_pending(org_id, expires_at)` stays for
per-org queries that may come later (per-org dashboards, per-org cancel flows).

**2. JobRunr storage tables.** JobRunr persists its recurring schedules and in-flight jobs in its
own SQL tables. We copy its canonical DDL into Flyway rather than letting JobRunr auto-create on
first boot, so the schema stays under Flyway's control and reproducible CI/test runs don't depend
on JobRunr's boot-time DDL. Tables: `jobrunr_jobs`, `jobrunr_recurring_jobs`,
`jobrunr_backgroundjobservers`, `jobrunr_metadata`, `jobrunr_jobs_stats`. **Pinned to JobRunr
7.2.2** — the DDL copied here must come from the v7.2.2 PostgreSQL initialization script.
Bumping the JobRunr version is a future slice and must update both the dependency *and* this
migration in lockstep.

JobRunr is configured in `AppConfig` with the relevant flag so it **does not** try to manage its
own schema:
```java
JobRunr.configure()
    .useStorageProvider(SqlStorageProviderFactory.using(dataSource,
        DatabaseOptions.SKIP_CREATE))
    …
```

---

## Sweeper architecture

### Bulk read / per-order write

```
┌───────────────────────────────────────────────────────────────────────────────┐
│ tick (every 30s, JobRunr)                                                     │
│   ┌────────────────────────────────────────────────────────────────────────┐  │
│   │ short read-only query — IDs only, hits idx_so_pending                  │  │
│   │   SELECT id FROM sales_order                                           │  │
│   │    WHERE status='PENDING_PAYMENT' AND expires_at < now()               │  │
│   │    ORDER BY expires_at ASC                                             │  │
│   │    LIMIT :batchLimit                                                   │  │
│   │   (returns List<UUID>; entities are NOT fetched here — anything richer │  │
│   │    would just be a stale snapshot by the time we open the per-order    │  │
│   │    txn below.)                                                         │  │
│   └────────────────────────────────────────────────────────────────────────┘  │
│   for each candidate id:                                                      │
│   ┌────────────────────────────────────────────────────────────────────────┐  │
│   │ short txn (one order) — all state is re-read here, under lock          │  │
│   │   1. UPDATE sales_order SET status='EXPIRED', expired_at=now()         │  │
│   │       WHERE id=:id AND status='PENDING_PAYMENT'    -- 0 rows = noop    │  │
│   │   2. SELECT … FROM inventory_reservation                               │  │
│   │       WHERE sales_order_line_id IN (lines of :id) AND status='ACTIVE'  │  │
│   │       ORDER BY product_id ASC                                          │  │
│   │   3. aggregate qty per product                                         │  │
│   │   4. InventoryRepository.lockForUpdate(orgId, productIds)              │  │
│   │       -- slice-2 primitive; issues SELECT … FOR UPDATE                 │  │
│   │       --                          ORDER BY product_id ASC              │  │
│   │   5. per product: adjustQuantities(orgId, pid, 0, -total, version)     │  │
│   │   6. UPDATE inventory_reservation … SET status='RELEASED', …           │  │
│   │   7. one inventory_log row per product                                 │  │
│   └────────────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────────┘
```

### Source of `orgId` inside the per-order txn

`lockForUpdate` in step 4 takes `(orgId, productIds)`, but the candidate finder in the outer
tick only returns bare `UUID` order ids. **Do not issue a separate `SELECT org_id FROM sales_order`
to recover it.** The reservation rows fetched in step 2 already carry `org_id` (V19 column) — read
it from the first row of that result and pass it to `lockForUpdate`. One round-trip saved per
order; the data is also load-bearing-consistent because it comes from the same locked-soon set.

### Design note: step 1 is a raw `UPDATE`, not a domain-aggregate flow

Slice 2 (`ReservationService`) goes through the `SalesOrder` aggregate's mutators. Step 1 here
deliberately does not — it executes a raw
`UPDATE sales_order SET status='EXPIRED', expired_at=:now WHERE id=:id AND status='PENDING_PAYMENT'`
instead of `SELECT FOR UPDATE → markExpired(now) → persist`.

The bypass is justified because the `PENDING_PAYMENT → EXPIRED` transition has no business
invariants that need the in-memory aggregate to evaluate — it's a terminal, structural state flip.
The `WHERE status='PENDING_PAYMENT'` clause **is** the concurrency guard (atomic, DB-enforced,
indistinguishable in effect from what `SalesOrder.markExpired` would assert in-memory). Loading the
aggregate would cost one extra round-trip per order across every tick for no correctness gain.
Cancel and fulfillment slices, which *do* have richer invariants, must still go through the
aggregate.

### Why step 5 (inventory) precedes step 6 (reservations released)

Both orderings are transactionally correct — if anything throws, the per-order txn rolls back and
both writes revert atomically, so post-commit state is identical either way. The order is
deliberate for **debuggability of in-flight transactions** (e.g., an oncall opening psql against a
hung sweeper):

- **Step 5 first, step 6 second** (what this slice does): a mid-txn observer sees `ACTIVE`
  reservations with `reserved_qty` already decremented. Visibly an in-flight write, no question.
- **Step 6 first, step 5 second** (rejected): a mid-txn observer sees `RELEASED` reservations with
  `reserved_qty` not yet decremented. Reads like a bug ("releases never decrement inventory!")
  even though the txn just hasn't committed yet — costs minutes of confused investigation.

In practice the txn is milliseconds and nobody will ever see the mid-state. But the rule costs
nothing to enforce and removes one false alarm from a future oncall's failure mode list. Future
slices (cancel, fulfillment) should follow the same convention: **mutate the canonical aggregate
(`inventory.reserved_qty`) before marking the child rows (`inventory_reservation.status`)**.

Two key principles, the same ones drilled into us by slice 2:

- **One transaction per order** — never one giant `UPDATE … WHERE expires_at < now()`. A bulk
  update would hold `FOR UPDATE` locks on every affected `inventory` row at once, throwing every
  in-flight `placeOnlineOrder` into a queue. Per-order txns keep each contention window in the
  millisecond range.
- **Stable lock order** — products are locked `ORDER BY product_id ASC` inside the per-order txn,
  matching the reservation slice's discipline. A sweeper expiring order X and a placement creating
  order Y that touch the same product set will never AB/BA-deadlock.

### Batch limit and tick frequency

- `batchLimit` defaults to **200** per tick; configurable via env `ORDER_SWEEPER_BATCH_LIMIT`.
- Tick interval default `30s`; configurable via env `ORDER_SWEEPER_INTERVAL` (cron string passed to
  JobRunr's `@Recurring`).
- Worst-case latency for an expired order to actually flip: `tick_interval + per-order-txn-time`
  ≈ ~30s + <100ms. Good enough for a "your hold expired" UX.

### What runs the timer

`JobRunr` rather than a hand-rolled `ScheduledExecutorService`. The reasons:

- **Crash-safe**: if the JVM dies mid-tick, JobRunr retries the same job on the next bootable
  instance — recurring schedules survive in `jobrunr_recurring_jobs`.
- **Cluster-safe**: when we eventually run more than one API node, JobRunr's `BackgroundJobServer`
  takes a DB-level lease per recurring job — only one node runs the sweeper at a time. (Our
  per-order `UPDATE … WHERE status='PENDING_PAYMENT'` is *also* idempotent against double-fire,
  but the lease cuts the noisy-noop log volume in half.)
- **Observable**: the JobRunr dashboard (port 8000 by default; we'll wire it behind admin auth in a
  separate slice) shows last run, next run, success/failure history per recurring job.

---

## Side-effect details — what moves and what doesn't

| Column / table                | Change                                                |
|------------------------------|-------------------------------------------------------|
| `sales_order.status`         | `PENDING_PAYMENT` → `EXPIRED`                         |
| `sales_order.expired_at`     | `null` → `now()`                                      |
| `sales_order.updated_at`     | `now()`                                               |
| `inventory_reservation.status`        | `ACTIVE` → `RELEASED` (per row, for the order)         |
| `inventory_reservation.released_at`   | `now()`                                               |
| `inventory_reservation.released_reason` | `'EXPIRED'`                                         |
| `inventory.reserved_qty`     | `reserved_qty - sum(released)` per product            |
| `inventory.version`          | `version + 1` per affected row                        |
| `inventory.stock_qty`        | **unchanged** — only on-hand the warehouse holds      |
| `inventory_log` (new row per product) | `stock_delta=0`, `reserved_delta=-total`, `stock_after=inventory.stock_qty (unchanged)`, `reserved_after=inventory.reserved_qty (post-decrement)`, `reason=RELEASED`, `order_id=order.id`, `actor_id='order-ttl-sweeper'`, `actor_type=SYSTEM` |

**Why `stock_delta = 0`**: nothing physical happened in the warehouse. The customer never paid, no
units were shipped or restocked. We're merely undoing the *intent* to ship — the *reservation* —
which lived only on the books.

---

## Service contracts

```java
public final class OrderExpiryService {

  public OrderExpiryService(
      DSLContext rootDsl,
      SalesOrderRepositoryFactory salesOrderRepoFactory,
      InventoryRepositoryFactory inventoryRepoFactory,
      InventoryReservationRepositoryFactory reservationRepoFactory,
      InventoryLogRepositoryFactory inventoryLogRepoFactory) { … }

  /**
   * Expire orders whose expires_at is in the past, one short transaction per order.
   * Stops at batchLimit; the next tick picks up the rest. Catches and logs per-order failures so
   * one bad order can't poison the whole sweep.
   *
   * <p><b>This method MUST NOT open a transaction.</b> See "The poison-pill rule" below.
   */
  public Summary sweep(int batchLimit);

  /**
   * Expire a single order. Idempotent: returns 0 if the order was already paid/cancelled/expired
   * by the time the txn opened. Opens its own transaction via {@code rootDsl.transactionResult};
   * never reuses the caller's.
   *
   * <p><b>Unified timestamp discipline:</b> a single
   * {@code OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC)} is captured at the start of
   * the transaction block and threaded through every write — {@code sales_order.expired_at},
   * {@code inventory_reservation.released_at}, {@code inventory.updated_at}, and
   * {@code inventory_log.created_at} all carry the same instant. Do not call {@code now()}
   * (Java or SQL) twice inside one expiry: a future oncall correlating logs must be able to
   * group every write from one sweeper expiry by exact-equal timestamp.
   */
  public int expireOnePending(UUID orderId);

  public record Summary(int candidatesScanned, int ordersExpired,
                        int reservationsReleased, int productsAffected,
                        int orderFailures) {}
}
```

### The poison-pill rule — `sweep()` MUST NOT be transactional

The single most dangerous edit a future contributor can make to this code is to wrap `sweep()` in
a transaction — either by inlining `rootDsl.transactionResult(cfg -> { … })` around the for-loop,
or by extracting the loop into a helper that someone else later puts inside one. Both produce the
same disaster:

1. **Poison-pill batch loss.** If order #142 in a batch of 200 throws (NPE on a malformed line, a
   transient constraint violation, a row lock the sweeper can't acquire in time), the exception
   bubbles up to the outer `transactionResult` callback. **The whole batch rolls back.** Orders 1
   through 141, which processed perfectly, revert from `EXPIRED` back to `PENDING_PAYMENT`. The
   next tick re-finds all 200 candidates, re-fails on #142, re-rolls back. The sweeper grinds
   forever and the expiry system stops working. The signal in logs is a steady cadence of "swept
   142 orders" followed by 200 orders still pending — silent unless someone correlates two
   metrics.
2. **Contention spike.** A 200-order batch now holds `FOR UPDATE` locks on every inventory row
   those orders touch, for the **entire** loop (seconds, not milliseconds). Every live
   `placeOnlineOrder` that wants a locked product blocks until the sweep finishes. Customer
   checkout latency jumps from a few ms to seconds; some time out. The thing that was supposed to
   be invisible becomes the loudest performance problem of the day.

**The mandatory shape of `sweep()`:**

```java
public Summary sweep(int batchLimit) {
  // Read-only id projection — NO transaction wrapper here either; jOOQ runs this on a
  // single connection from the pool, autocommit. Returns List<UUID>, never entities.
  SalesOrderRepository repo = salesOrderRepoFactory.create(rootDsl);
  List<UUID> candidates = repo.findExpiredPendingIds(batchLimit);

  int expired = 0;
  int reservationsReleased = 0;
  int productsAffected = 0;
  int failures = 0;

  for (UUID orderId : candidates) {
    try {
      // Each call opens and commits its own short txn. A failure inside throws back here.
      PerOrderResult r = expireOnePendingInternal(orderId);
      expired               += r.flipped();
      reservationsReleased  += r.reservationsReleased();
      productsAffected      += r.productsAffected();
    } catch (RuntimeException ex) {
      // Catch is load-bearing: it isolates the failure to this one order so the loop continues
      // and the 199 healthy candidates still get processed. The bad order will be picked up by
      // the next tick (its row is unchanged in the DB) and either succeed or fail again, but
      // never block its peers.
      failures++;
      log.warn("Sweeper failed to expire order {} — skipping; will retry next tick",
               orderId, ex);
    }
  }

  return new Summary(candidates.size(), expired, reservationsReleased, productsAffected, failures);
}
```

Three rules a reviewer should bounce a PR for:

- `sweep()` body opens a `transactionResult(...)` block at any level.
- The `try { … } catch (RuntimeException) { log; continue; }` around the per-order call is
  weakened (re-thrown, narrowed to a specific exception class, or removed).
- `expireOnePending(UUID)` is restructured to *not* open its own transaction (e.g., changed to
  take a `DSLContext txDsl` parameter so the caller "could" share one).

Each one of those edits looks innocuous on its own and quietly destroys the poison-pill isolation
this slice depends on. The acceptance criterion "**Sweeper crash mid-batch**" below proves the
isolation works; if it ever starts failing, the most likely cause is one of the three.

```java
public final class OrderTtlSweeperJob {

  public OrderTtlSweeperJob(OrderExpiryService expiryService, int batchLimit) { … }

  // Wired into JobRunr as @Recurring(id = "order-ttl-sweeper", cron = "${ORDER_SWEEPER_INTERVAL:-*/30 * * * * *}")
  @Job(name = "order-ttl-sweeper")
  public void run() {
    OrderExpiryService.Summary summary = expiryService.sweep(batchLimit);
    log.info("Sweeper tick: {}", summary);
  }
}
```

The `ActorContext` for every `inventory_log` row written by this slice is
`ActorContext.system("order-ttl-sweeper")` — the existing factory in `domain.ActorContext`.

---

## Domain — `InventoryReservation.release(...)`

Slice 2 created `InventoryReservation` with `createActive(...)` and `rehydrate(...)` only; the
mutator was deferred. We add it here:

```java
public void release(String reason, OffsetDateTime now) {
  if (this.status != ReservationStatus.ACTIVE) {
    throw new InvalidReservationTransitionException(
        "cannot release reservation " + id + " in status " + status);
  }
  Objects.requireNonNull(reason, "reason");
  Objects.requireNonNull(now, "now");
  this.status = ReservationStatus.RELEASED;
  this.releasedAt = now;
  this.releasedReason = reason;
}
```

`reason` is the free-text column V19 declares (`'CANCELLED' | 'EXPIRED' | 'ADMIN'`). This slice
only ever passes `"EXPIRED"`; future slices supply `"CANCELLED"` and `"ADMIN"`.

---

## Concurrency model and races

### Race A — sweeper vs. payment

Order X has `expires_at = now() - 1s`. A customer's payment is verified at the same tick. Two
outcomes:

- **Payment wins** (its txn commits first): order goes `PENDING_PAYMENT → PAID`. The sweeper's
  candidate-list query had returned X's id a moment earlier, but inside the per-order txn,
  `UPDATE sales_order SET status='EXPIRED' WHERE id=:id AND status='PENDING_PAYMENT'` affects
  **0 rows**. The sweeper logs "noop" and continues. No reservations are touched. ✓
- **Sweeper wins**: order goes `PENDING_PAYMENT → EXPIRED`, reservations released. The payment
  verification then encounters the order in `EXPIRED` and fails the transition (the
  `requireStatus` invariant on `SalesOrder.markPaid`). Refund of the InstaPay payment (if it
  cleared) becomes a manual issue handled by the future payment-reconciliation slice. ✓

The guard column is `status` itself; both writers race-update on the same row, and Postgres'
row-level lock plus the `WHERE status='…'` clause gives us a clean winner-take-all.

### Race B — two sweeper instances (cluster)

JobRunr's recurring-job lease means only one node runs `order-ttl-sweeper` at a time. Even without
the lease, the per-order `UPDATE … WHERE status='PENDING_PAYMENT'` is idempotent; a sibling that
already flipped the row leaves the second sweeper with `0 rows updated` → noop.

### Race C — reservation already CONSUMED

Cannot happen while the order is still `PENDING_PAYMENT` (fulfillment only consumes after
`FULFILLING`). We still defend: `findActiveByOrderId` filters `status='ACTIVE'` and the bulk
`markReleased` does the same. If a row was somehow consumed by parallel admin action, it stays
consumed and isn't re-released.

### Stable lock order

Per-order txn locks `inventory` rows `ORDER BY product_id ASC FOR UPDATE` — same rule as
`ReservationService`. A live `placeOnlineOrder` that wants the same product set acquires locks in
the same order. No AB/BA deadlock.

---

## HTTP surface

### `POST /api/admin/sweep` — manual trigger (new, SYSTEM_ADMIN-only)

```
POST /api/admin/sweep?batchLimit=200
Authorization: Bearer <admin>

→ 200 OK
{
  "candidates_scanned": 14,
  "orders_expired": 14,
  "reservations_released": 23,
  "products_affected": 9,
  "order_failures": 0
}
```

Synchronous; intended for ops and for the integration test in this slice. The org-scoped HTTP
contract surface is otherwise unchanged.

---

## File layout

| Module       | New / changed                                                                                                                                                                                                              |
|--------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `repository` | **New**: `V29__Create_jobrunr_tables_and_sweeper_index.sql` — adds the sweeper-friendly partial index `idx_so_pending_global ON sales_order (expires_at) WHERE status='PENDING_PAYMENT'` **and** JobRunr's canonical DDL pinned to v7.2.2. |
| `common`     | **New**: `InvalidReservationTransitionException extends AppException` (status 409).                                                                                                                                        |
| `domain`     | **Changed**: `InventoryReservation` — add `release(String reason, OffsetDateTime now)`. **Changed**: `SalesOrderRepository` — add `findExpiredPendingIds(int limit) : List<UUID>` (IDs only — see Scope rationale), `markExpiredIfPending(UUID, OffsetDateTime)`. **Changed**: `InventoryReservationRepository` — add `findActiveByOrderId(UUID)`, `markReleased(Collection<UUID>, String, OffsetDateTime)`. **Reuses (no change)**: `InventoryRepository.lockForUpdate(...)` from slice 2 for the per-order locking read. |
| `repository` | **Changed**: jOOQ implementations of the four new methods above. `findExpiredPendingIds` projects `SALES_ORDER.ID` only and hits the V17 partial index `idx_so_pending`.                                                  |
| `service`    | **New**: `OrderExpiryService` (rootDsl + four factories). Reuses the per-product aggregation pattern **and the existing `InventoryRepository.lockForUpdate`** from `ReservationService`. **Changed**: `AppConfig` wires it. |
| `api`        | **New**: `OrderTtlSweeperJob` (instantiated and registered with JobRunr in `AppConfig`). **New**: `AdminSweepServlet` + `AdminSweepHandler` mounted at `/api/admin/sweep` (SYSTEM_ADMIN, via `AuthzHelper.requireAdmin`). **Changed**: `AppBootstrap` starts/stops JobRunr's `BackgroundJobServer` either side of `AppConfig`'s lifecycle. **Changed**: parent `pom.xml` adds `<dependency><groupId>org.jobrunr</groupId><artifactId>jobrunr</artifactId><version>7.2.2</version></dependency>` — pin matches the DDL copied into V29. |

---

## Authorization

- The sweeper job is system-internal; no `SecurityContext`. It tags audit rows with
  `ActorContext.system("order-ttl-sweeper")`.
- The manual `POST /api/admin/sweep` endpoint requires **SYSTEM_ADMIN** — verified via the existing
  `AuthzHelper.requireAdmin(req)`. No `orgId` in the path; it's a global operation.

---

## Ground truth — `SUM(active reservations) = inventory.reserved_qty`

Among the acceptance criteria below, **one is load-bearing in a way the others are not**:

```
for every (org_id, product_id):
    SUM(inventory_reservation.quantity)
      WHERE org_id = :o AND product_id = :p AND status = 'ACTIVE'
  ==
    inventory.reserved_qty
      WHERE org_id = :o AND product_id = :p
```

This is **the** correctness invariant of the slice — not a sanity check, the ground truth. A
single SQL query catches every failure mode simultaneously, including ones the row-level criteria
below would silently miss:

| Failure mode | What row assertions might show | What the invariant shows |
|---|---|---|
| Partial release (sweeper released the reservation but skipped the inventory write) | reservations: RELEASED ✓ | `SUM(active) < reserved_qty` → fail |
| Double release (sweeper ran twice on the same order before sibling-noop took effect) | reservations: RELEASED ✓ (same row twice flipped to same value) | `SUM(active) > reserved_qty - 2·qty` → fail |
| Lost inventory update (race condition stole the decrement) | reservations: RELEASED ✓, log row written ✓ | `SUM(active) < reserved_qty` → fail |
| Half-committed txn (reservation released but inventory not yet decremented, observed mid-flight) | both look "in progress" individually | invariant violated at the snapshot → fail |
| Test expectation drift (test asserts wrong row, both invariant and reality disagree with it) | test passes — false green | invariant catches the lie → fail |

If individual row assertions pass but the invariant fails, **the row assertions are lying** —
they're asserting on the wrong rows, or asserting before commit, or asserting against a snapshot
that the txn already moved past. If the invariant passes and individual row assertions fail, the
**test expectations are stale**. The invariant is never the wrong question.

### Test-harness rule

Every integration test in this slice — happy path, multi-line same-SKU, Race A pay-vs-sweep,
sibling-already-won, poison-pill, lock-window isolation, manual `/api/admin/sweep`, JobRunr
restart — asserts this invariant across the full test DB as an **`@AfterEach` post-condition**,
not as an opt-in line item. Implement it **once**, as a base-test-class or a shared JUnit
extension, so a future contributor adding a new scenario cannot forget to check it. The acceptance
criteria below explicitly *do not* repeat it per-test; it is implicit on all of them by virtue of
the harness rule.

If the invariant ever fails:

1. Stop the failing test (do not auto-skip, do not retry).
2. Dump the violating `(org_id, product_id)`'s `inventory` row and every `inventory_reservation`
   row for it.
3. Diagnose from there. Adjust the code, never the invariant.

---

## Acceptance criteria

- [ ] An order with `status='PENDING_PAYMENT' AND expires_at = now() - 1m` flips to `EXPIRED` on
  the next sweeper tick (verifiable via `POST /api/admin/sweep` in the integration test —
  synchronous, deterministic).
- [ ] After expiry, the order's `expired_at` column is non-null and equals the sweeper's `now()`.
- [ ] Each previously-`ACTIVE` `inventory_reservation` for that order is now `RELEASED` with
  `released_at = now()` and `released_reason = 'EXPIRED'`. No reservations for other orders are
  touched.
- [ ] For each product the order reserved, `inventory.reserved_qty` decreased by exactly the sum
  of that product's released reservations; `inventory.stock_qty` is unchanged; `inventory.version`
  incremented **exactly once by this transaction** (not "version+1 globally" — a sibling txn could
  validly bump the row between sweeper ticks; the load-bearing claim is that *this* txn produces
  exactly one increment on that row). Within this slice the row is held under `FOR UPDATE` from
  step 4 through commit, so no interleaving is possible and the two phrasings are equivalent
  here — but the wording is the one cancel/fulfillment slices should copy-paste, and those slices
  may not always hold the same lock.
- [ ] One `inventory_log` row per affected product, `reason = RELEASED`, `order_id = order.id`,
  `stock_delta = 0`, `reserved_delta = -total`, `actor_id = 'order-ttl-sweeper'`,
  `actor_type = SYSTEM`. Stock-after equals the unchanged stock_qty; reserved-after equals the
  post-decrement value.
- [ ] **Multi-line same-SKU rule** (mirrors slice 2 Trap 1): an order with two lines for the same
  product produces **one** `inventory_log` row and **one** `inventory.reserved_qty` decrement, but
  **two** reservation rows are updated. Verified by row counts.
- [ ] An order with `expires_at < now()` whose `status` is already `PAID` / `CANCELLED` /
  `EXPIRED` is **not** touched by the sweeper. (Sibling-already-won path, asserted by row counts.)
- [ ] **Ghost-order safety**: an order whose `status='PENDING_PAYMENT'` and `expires_at < now()`
  but which has **zero** `ACTIVE` reservations (e.g., reservations were already released by an
  upstream admin action) flips cleanly: step 1's UPDATE flips `status` to `EXPIRED` and sets
  `expired_at`, step 2 returns an empty list, steps 3–7 are naturally skipped, and the per-order
  txn commits with no inventory rows locked, no `reserved_qty` decrement, and **no** `inventory_log`
  row written. `Summary.ordersExpired` counts this as a success; `reservationsReleased` and
  `productsAffected` do not increment for this order.
- [ ] **Race A**: a payment-verification path that flips the same order to `PAID` between the
  sweeper's candidate-SELECT and its per-order UPDATE results in the sweeper's UPDATE affecting
  0 rows. Order stays `PAID`; reservations stay `ACTIVE`. No `inventory_log` row written for this
  order by the sweeper. (Integration test stages the race deterministically.)
- [ ] **Concurrent sweep + placement on same product**: a `placeOnlineOrder` for product P running
  during a sweep that's also releasing P never deadlocks. Both call the same slice-2
  `InventoryRepository.lockForUpdate(...)` primitive, so locks are acquired in `product_id ASC`
  order on both sides.
- [ ] **Lost-update guard (sweeper-vs-sweeper)**: two concurrent sweeper invocations releasing two
  different expired orders that touch the same product P — e.g., the JobRunr-scheduled tick and a
  manual `POST /api/admin/sweep` firing in the same instant — finish with `inventory.reserved_qty`
  decremented by exactly `X.qty + Y.qty`, never one decrement "stolen" by the other. (Without
  `FOR UPDATE` discipline this is the classic read-compute-write race; the test stages it
  explicitly. The admin-cancel-vs-sweeper variant of this race is asserted in the cancel slice,
  where the cancel path exists.)
- [ ] **Cross-cutting invariant — implemented as test-harness `@AfterEach`, not as a per-scenario
  line item** (see "Ground truth" above). Every test in this slice — happy path, multi-line
  same-SKU, sibling-already-won, Race A pay-vs-sweep, poison-pill isolation, lock-window
  isolation, manual `/admin/sweep`, JobRunr restart, concurrent-placement-during-sweep — asserts
  `SUM(inventory_reservation.quantity WHERE status='ACTIVE') == inventory.reserved_qty` per
  `(org_id, product_id)` after the scenario completes. Implemented exactly once via a shared
  base class or JUnit extension, so adding a new scenario cannot bypass it. A test passing its
  own row assertions but failing this check counts as a failing test; the row assertions get
  rewritten, never the invariant.
- [ ] **Poison-pill isolation (load-bearing)**: in a batch of e.g. 5 candidates, inject a
  guaranteed-failure into `expireOnePending(order_3)` (e.g., temporarily corrupt the order's
  inventory reference so the per-order txn throws). After `sweep()` returns:
  - orders 1, 2, 4, 5 are `EXPIRED` and their reservations `RELEASED` (committed);
  - order 3 is unchanged, still `PENDING_PAYMENT`, reservations still `ACTIVE` (its txn rolled
    back atomically — no partial release, no status flip without log row, no log row without
    reservation update);
  - `Summary.orderFailures == 1`, `Summary.ordersExpired == 4`;
  - a `WARN`-level log line names order 3 and includes the exception cause.
  This test fails if `sweep()` is ever wrapped in a transaction or the per-order catch is
  removed — both of which would cause orders 1, 2 to also be `PENDING_PAYMENT` at the end.
- [ ] **Lock-window isolation**: while a `sweep()` batch is mid-iteration on orders 1..200, a
  concurrent `placeOnlineOrder` for a product **not** touched by the currently-processing order is
  not blocked. (Proves per-order txn boundaries don't accidentally serialize unrelated work — i.e.,
  there is no outer transaction holding locks across the batch.)
- [ ] **JobRunr restart**: stop and re-start the API; the `order-ttl-sweeper` recurring schedule
  resumes from the `jobrunr_recurring_jobs` row without duplicate registration and without
  re-firing in-flight expiry attempts.
- [ ] **Manual endpoint authorization**: `POST /api/admin/sweep` without `SYSTEM_ADMIN` returns
  `403`; with the role returns `200` and the `Summary` JSON.

---

## What this unblocks

| Next slice                          | Depends on this                                                                                                                                                                                                       |
|------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Cancel pre-PAID order**          | Re-uses `InventoryReservation.release(...)` and the per-product aggregation pattern; the trigger is a user/admin action instead of the sweeper.                                                                       |
| **Online payment InstaPay reconcile → Order PAID** | The PAID transition has to coexist with the sweeper's EXPIRED transition; Race A in this slice is the design-test that proves the coexistence works.                                                            |
| **Fulfillment SHIPPED**            | Adds the sibling `consume(...)` mutator on `InventoryReservation`; the per-order short-txn discipline established here is the template.                                                                                |
| **JobRunr dashboard behind admin auth** | The sweeper is the first scheduled job in the system; its existence motivates exposing the JobRunr dashboard under `/admin/jobs` once we have an admin UI route for it.                                          |
