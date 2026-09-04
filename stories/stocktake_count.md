# Slice: Stocktake — the counted variance through the one adjust, on the one ledger

> The physical inventory count. Loyverse calls it *Inventory count*: walk the shelves, scan
> each item, type what is actually there, and the difference between the count and the system
> becomes a stock adjustment. The workflow the frontend builds
> (`frontst/stories/137_st_stocktake_session.md`) is scan → count → review → post; **this slice
> is the backend it posts against, and it is deliberately small** — the mandate is *reuse the
> barcode scanner, the adjustment-reason mechanism and the inventory audit log rather than
> creating separate mechanisms*. So there is no `stocktake` table, no `stocktake_line`, no
> second audit trail: a stocktake is a client-side session whose every variance lands as **one
> `inventory_log` row, written by the existing `adjust` action, under a reason that says what
> it was**. **Branch `174_feat/stocktake` off `master`, migration V93.**
>
> **Built 2026-09-04.** V93 + codegen; `StockReason.STOCKTAKE`; `adjust` locks first, takes
> `reason` + `Idempotency-Key`, guards below-reserved with a 409 that claims no key;
> `InventoryAdjustRequest` beside the shared qty body; perfdb hand-migrated V92→V93. Tests:
> `AdjustIdempotencyIT` 16 · `InventoryAdjustHandlerTest` 5 · `RestockIdempotencyIT` 7 (unchanged,
> re-run) · `InventoryReadsServiceIT` 7 · `InStoreSaleIT` 12 — all green; `service` 274 + `api`
> 301 unit tests green.

---

## Goal

Make the existing adjust action honest enough to be what a stocktake posts:

1. **A reason that says "counted".** `StockReason` gains `STOCKTAKE` and `adjust` accepts an
   optional `reason ∈ {ADJUSTMENT, STOCKTAKE}` (default `ADJUSTMENT`, so today's adjust form is
   byte-identical). The ledger row is the record — *"−2 · Stocktake · by Hana · 14:02"* — and a
   future shrinkage report is a `WHERE reason = 'STOCKTAKE'`, not a join.
2. **Exactly-once posting.** `adjust` takes the same optional `Idempotency-Key` `restock` has had
   since V50 — the column V50 added *for* "the deferred offline stock-take queue", never yet used
   by an adjustment. A session that posts twenty lines over a flaky shop Wi-Fi retries without
   double-correcting.
3. **A cause-naming refusal instead of a 500.** A count below the units held by open orders (or
   below zero) hits the `inventory_available_non_negative` CHECK today and surfaces as a raw
   `DataAccessException`. The client floors such a count at the reserved quantity before it ever
   posts (the adjust sheet's `computeAdjustment`, reused), so this fires only when the holds grew
   *between scan and post*; under the row lock the service now says *why* — **409 naming the
   reserved quantity** — and writes nothing, so the client can re-read, re-floor and re-post.

Done means: a stocktake that counted 30 where the system said 32 posts `adjust {qty:-2,
reason:"STOCKTAKE"}` with a key, the ledger shows one `STOCKTAKE` row, a retry of the same call
writes nothing and returns the same stock, and a count that undercuts the reserved units is a
409 the operator can act on.

---

## What exists, what is missing

- `POST /api/orgs/{orgId}/inventory/{productId}/adjust {qty}` (STAFF) — a signed delta, written
  under the hard-coded `StockReason.ADJUSTMENT` ("Manual Adjustment"). `InventoryService.adjust`
  reads the row without a lock, applies the delta under the optimistic `version`, appends the
  ledger row. No key, no reason choice, no guard ahead of the DB CHECK.
- `inventory_log` (V9 → V50) carries `stock_delta`, `stock_after`, `reason`, actor,
  `impersonator_id` and — since V50 — `idempotency_key` with the partial unique
  `(org_id, idempotency_key)`. V50's own comment: *"the seam the deferred offline stock-take
   queue replays against"*. Only `restock` writes it.
- `StockReason` (PG enum `stock_reason`, jOOQ-mapped by literal): seven values, the last added by
  V37 with `ALTER TYPE … ADD VALUE IF NOT EXISTS` — the precedent this migration copies.
- `inventory.stock_qty >= reserved_qty` (V7 CHECK) and both columns `non_negative_int`. A stock
  count that undercuts the reserved units cannot be represented, and today the caller learns
  that as a 500.
- The frontend adjust sheet (frontst story 07) already does the **counted → delta** arithmetic
  for one product and floors the decrement at `available_qty` client-side "so the guard should
  not normally fire". A stocktake posts many products from a snapshot taken minutes earlier; the
  guard *will* fire, and it has to say something.
- `GET /products?barcode=` (story `product_barcode_lookup.md`), `GET /inventory/{productId}`,
  `GET /inventory?q=` (story `inventory_reads.md`) — every read the count screen needs already
  exists. **This slice adds no read.**
- `inventory_reads.md` deferred `inventory_log.note` as "a future mutation-side change". This
  slice is a mutation-side change on `adjust` and still does **not** add it — see *Out*.

Missing, therefore: a reason value, the key on `adjust`, and the guard. Nothing else.

---

## Why this shape

### A stocktake is a session on the client and a delta on the server — not a document

The obvious design is Loyverse's: a `stocktake` header (started, completed, by whom), N
`stocktake_line` rows (expected, counted, difference), and a *complete* step that applies the
lines. That is three new tables, a second lifecycle, and a second place a stock movement is
recorded — exactly what the mandate rules out, and for a shop whose ledger already records
every movement with actor and running balance, it is a second copy of the truth.

What a count *needs* from the server is one thing: apply this variance once, under this reason,
by this person. The existing `adjust` does the first and third; this slice adds the second and
the *once*. The session itself — which products were scanned, what was counted, what the
system said when it was scanned — lives on the phone until it is posted (frontend story 137).
The server never learns that a line matched; a match is not a movement, and the ledger is a
movement ledger.

### The variance is a delta, because deltas commute with a shop that is still open

The count says *"I counted 12"*. The system said 10 when the item was scanned. Between scanning
and posting, a cashier sells one: the shelf now holds 11, the system says 9. Posting **set to 12**
is wrong (shelf has 11). Posting **the delta +2** gives 11 — right, because the discrepancy the
count found (two units the system did not know about) is independent of the legitimate sale
that happened after. This is the same commutativity that made `restock` the one op safe to queue
offline (`16_st_barcode_scanner_sale.md` §Out), and it is why the stocktake posts through
`adjust` with a client-computed delta — **computed against the stock at scan time, never
re-read at post time** — rather than through a new set-to-count action.

The cost of a delta is the double-count: two people counting the same shelf independently both
post +2 and the system lands at 14. That is a process hazard, not a data-model one (two people
with clipboards have it too), and it is mitigated where it belongs: one session per pass on the
frontend, and the product's ledger showing a `STOCKTAKE` row minutes old. A set-with-check
variant (`{counted, expected}` → 409 when stock moved) would catch the double-count and break
on every sale made during the count — the wrong trade for a shop that counts during quiet
hours, not closed ones.

### The reason lives on `adjust`, not on a new action

`STOCKTAKE` is a new *word* in an existing vocabulary, not a new mechanism. The alternatives were
weighed:

- **Keep `ADJUSTMENT`, add a note** ("stocktake 2026-09-04"): free text is not a filter, and the
  ledger reader still cannot tell "we counted the shelf" from "the manager fixed a typo". The
  note stays deferred (below).
- **A `POST …/count` action:** a new route that would call the same service method with the
  same delta and write the same row — an alias with its own auth line, its own parse, its own
  test. `adjust {reason}` is one more field on one existing body.

`adjust` accepts **only** `ADJUSTMENT` and `STOCKTAKE`. `RESTOCK` has its own action (positive,
idempotent, with its own semantics), and the five order-linked reasons are written by the flows
that own their `order_id` — an adjust under `SOLD` would be a lie the ledger cannot detect. The
allowed set is a constant in the service, and an unknown or disallowed value is a 400 naming the
two.

### The key is the one V50 made, used the way V50 said

`restock`'s idempotency (V50) is *lock the row FOR UPDATE → the ledger insert with the key is the
claim (`ON CONFLICT DO NOTHING`) → on conflict, fingerprint the prior row and either replay or
409*. `adjust` adopts it verbatim, with **reason** added to the fingerprint: the same key with a
different delta, product *or reason* is a client bug surfaced as 409, never swallowed. A replay
returns the locked current row, which already reflects the committed delta. No key means today's
every-call-applies behaviour, exactly as on `restock`.

Adopting the lock has a side effect worth stating: `adjust` no longer relies on the optimistic
`version` alone. It locks first, like every other mutation that has been touched since V50, and
keeps the `version` predicate on the UPDATE as belt-and-braces. Concurrent adjusts of one product
now serialise instead of one of them losing a 409 race.

### The guard is a 409 under the lock, and it writes nothing

`stock_after = stock_qty + delta` is known before anything is written. If it is below
`reserved_qty` — or below zero, which the same comparison covers since `reserved_qty >= 0` —
the service throws `ConflictException` with the number the operator needs:

> `Stock cannot go below the reserved quantity: 3 units are held by open orders (counted stock
> would be 2). Cancel or fulfil those orders first.`

This is checked **after** the lock and **before** the idempotent insert, so a refused line claims
no key (a later retry with the same key, after the manager released the holds, applies cleanly)
and the DB CHECK stays as the backstop it always was. The plain adjust gets the same guard for
free — the "409/500-family" story 07 described becomes a 409 with a sentence.

Why the client floors rather than refuses — and why the floor is not a lie worth refusing: two
units on the shelf and three held for orders means the shop has oversold. Leaving stock at the old
figure lets the counter keep selling units that do not exist; setting it to the reserved floor
stops that, leaves `available = 0`, and the one remaining wrong number (the held units) is exactly
the one the holds panel already shows. The 409 exists for the race the floor cannot see — a hold
placed after the snapshot — and its message names the number the client needs to re-floor.

---

## Contract

### `POST /api/orgs/{orgId}/inventory/{productId}/adjust` — extended

STAFF (unchanged). Header **`Idempotency-Key`** optional. Body:

```json
{ "qty": -2, "reason": "STOCKTAKE" }
```

- `qty` — signed delta (unchanged).
- `reason` — optional; `ADJUSTMENT` (default) | `STOCKTAKE`. Case-sensitive enum name. Any
  other value, including `RESTOCK` or an order-linked reason → **400** `"reason must be one of:
  ADJUSTMENT, STOCKTAKE"`.

Responses:

- **200** `InventoryResponse` (unchanged shape) — the row after the delta; on a keyed replay,
  the row as it stands (the delta was applied by the first call).
- **404** no inventory row (untracked product — the client initialises instead).
- **409** `"Stock cannot go below the reserved quantity: …"` — nothing written, no key claimed.
- **409** `"Idempotency-Key reused with different parameters: {key}"` — nothing written.
- **409** version conflict (unchanged; unreachable in practice once the row is locked).

The ledger row: `stock_delta = qty`, `reserved_delta = 0`, `reason` as sent, `order_id NULL`,
`idempotency_key` as sent (NULL without the header), actor from the security context.

### `GET /api/orgs/{orgId}/inventory/{productId}/log` — one more value

`reason` may now be `STOCKTAKE`. `order_id` / `sales_order_number` are `null` for it, as for
`RESTOCK` and `ADJUSTMENT`. No shape change.

### Migration V93

```sql
ALTER TYPE stock_reason ADD VALUE IF NOT EXISTS 'STOCKTAKE';
```

Nothing else: no table, no column, no index (the V50 partial unique already covers the key), no
backfill (no existing row was a stocktake — the app never had one).

---

## Out (deferred, each with its shape)

- **`inventory_log.note`** — the column `inventory_reads.md` deferred. A stocktake line's *why*
  ("2 damaged, binned") is real information and this is the mutation-side slice that could carry
  it. It stays out because it is a change to every ledger read and to the adjust sheet, not to
  the stocktake; when it ships it is `note VARCHAR(500) NULL` + an optional `note` on this same
  body + the field on `InventoryLogRow`, and the stocktake session gains a per-line note field.
- **A stocktake summary read** ("what did the last count find across the shop?"). Today the
  ledger is per product; a cross-product read filtered by reason and time window
  (`GET /inventory/log?reason=STOCKTAKE&from=&to=`) is the natural shape and needs an index on
  `(org_id, reason, created_at)`. Not needed to post a count; the session summary is on the
  client at post time.
- **A count that verifies without moving** (a zero-delta row as "this shelf was checked"). The
  ledger is a movement ledger; a verification log is a different table with a different question.
  If "last counted" ever matters on the overview, it is a `product.last_counted_at` stamp, not a
  ledger row.
- **Set-with-check** (`{counted, expected}`) — rejected above; recorded so it is not re-proposed
  without the double-count vs open-shop trade being re-argued.
- **Blind counts / count sheets / multi-user sessions** — process features on a document model
  this slice deliberately does not build.

---

## Tests

- `AdjustIdempotencyIT` (Testcontainers, drives `InventoryService` like `RestockIdempotencyIT`):
  `STOCKTAKE` reason lands on the ledger row · same key applies once, replay returns current and
  writes no second row · different keys apply independently · no key applies every call
  (regression) · blank key = absent · same key + different qty / different reason / different
  product → 409, original stands · key scoped per org · **below-reserved → 409 naming the
  reserved count, nothing written, key not claimed, and the same key then applies once the hold
  is released** · below-zero on an unreserved row → 409 · a positive `STOCKTAKE` delta (found
  more than the system knew) applies · a reason outside the allowed set → `ValidationException`.
- `InventoryAdjustHandlerTest` (Mockito, the `InventoryReadsHandlerAuthTest` harness): `reason`
  absent → service called with `ADJUSTMENT` · `STOCKTAKE` parsed and passed · `RESTOCK` / garbage →
  400, service never called · `Idempotency-Key` header threaded through · VIEWER → 403 · the other
  four actions untouched.
- The existing `RestockIdempotencyIT` stays green (the shared repository methods are unchanged).

---

## Definition of done

- [x] V93 applied, codegen re-run, `StockReason.STOCKTAKE` on the domain enum.
- [x] `adjust` locks FOR UPDATE, takes `reason` + `Idempotency-Key`, guards below-reserved with a
      409, mirrors `restock`'s claim/replay/fingerprint.
- [x] Handler parses `reason` (400 outside the pair), threads the header; `InventoryAdjustRequest`
      beside the shared `InventoryQtyRequest` so the other four actions read nothing new.
- [x] Tests above green; the whole `api` module green.
- [x] CLAUDE.md: the `adjust` line and the log's reason list.
- [x] perfdb hand-migrated to V93 (an enum add — the standing procedure in `tools/seed/README.md`).
