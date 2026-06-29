# Slice: Replace a failed fulfillment (re-ship the goods)

> Outbound flow **TX-9** per the [fulfillment design](../sys-analysis/outbound/fulfillment.md#failed).
> Second arm of the FAILED-resolution fork begun in
> [`fail_fulfillment.md`](fail_fulfillment.md): instead of refunding a shipment that never
> arrived, the admin **re-ships** the same goods.

---

## Goal

A SHIPPED fulfillment failed (lost / refused / returned to sender). Rather than refund the
customer, a MANAGER **replaces** it: a brand-new Fulfillment is created for the same order lines and
runs the normal `PENDING → SHIPPED → DELIVERED` lifecycle. The order stayed `FULFILLING` and the
prepayment is still unallocated, so when the replacement delivers it issues an invoice and consumes
that prepayment exactly like a first-time delivery — **no money moves in this slice**.

- `POST /api/orgs/{orgId}/fulfillments/{id}/replace` — create a PENDING replacement Fulfillment for
  the failed one's lines (MANAGER). Returns the new fulfillment (**201**).

Done means: a failed fulfillment can be re-shipped from current stock, and at delivery its invoice
is funded by the prepayment that was never refunded — sibling fulfillments and the order roll-up are
untouched.

---

## Why this isn't just `POST /fulfillments` again

The original reservations were **consumed** at the failed fulfillment's ship, so there is nothing
for `create()` to link. The replacement therefore **re-reserves from current `available` stock**
first (reusing `ReservationService.reserveForOrder`), then builds the new fulfillment over those
fresh reservations. Two consequences fall out:

1. **Stock must be present.** Re-reservation requires `available ≥ qty`. The failed units left the
   warehouse at ship; unless they physically returned (`POST /fulfillments/{id}/return` restored
   `on_hand`) or fresh stock exists, re-reservation fails `409` and the admin must restock first.
2. **The over-fulfillment guard must ignore the failed fulfillment.** `sumFulfilledQtyByOrderLine`
   counted everything except CANCELLED; a FAILED fulfillment never delivered, so its quantity must
   no longer block re-fulfilling its lines. This slice changes the guard to exclude FAILED too.

---

## Resolution is single-use (refund XOR replace)

A failed fulfillment may be resolved **once**. Refunding then replacing would deliver an invoice
with no prepayment left to allocate; replacing then refunding would double-spend the prepayment a
live replacement is about to consume. A nullable `fulfillment.resolution` (`REFUNDED` | `REPLACED`)
records the choice: both `refundFailed` and `replace` require it to be `NULL` (and the status to be
`FAILED`), and stamp it on success. This also makes refund idempotent — a second refund of the same
fulfillment is now rejected rather than relying on the execute-time balance backstop.

`POST /return` (restock-on-return) is **orthogonal** — the physical goods may come back whether the
fulfillment was refunded or replaced — so it is not gated on `resolution`.

---

## Scope

### In
- **Schema (V38)**: `fulfillment.resolution text CHECK (resolution IN ('REFUNDED','REPLACED'))`
  (nullable) and `fulfillment.replaces_fulfillment_id uuid REFERENCES fulfillment(id)` (nullable,
  audit lineage from the replacement back to the failed one).
- **Domain**: `FulfillmentResolution` enum; `Fulfillment.resolve(resolution, now)` (guards
  `FAILED` + `resolution IS NULL`); `createReplacementPending(...)` factory carrying
  `replaces_fulfillment_id`.
- **Repository**: persist `resolution` (mutable) and `replaces_fulfillment_id` (set at insert);
  `sumFulfilledQtyByOrderLine` excludes FAILED as well as CANCELLED.
- **Service**: extract `create()`'s body into a `createInTx(...)` collaborator; add
  `replaceFailed(orgId, failedFulfillmentId, carrier?, tracking?, notes?, actor)` — guards, locks
  the failed fulfillment, re-reserves its lines via `ReservationService`, marks the failed one
  `REPLACED`, and builds the PENDING replacement via `createInTx` (linked back via
  `replaces_fulfillment_id`). `refundFailed` gains the `resolution IS NULL` guard + stamps
  `REFUNDED`. `ReservationService` is injected into `FulfillmentService`.
- **API**: `POST /fulfillments/{id}/replace` (MANAGER); optional body `{carrier?, tracking_number?,
  notes?}`; returns the new fulfillment.

### Out (deferred)
- **Partial replacement** (replace some failed lines, refund others) — a failed fulfillment is
  replaced or refunded as a whole.
- **Auto-restock-then-replace** — the admin records the physical return (`/return`) separately if
  the same units come back; `replace` does not assume they did, it reserves from whatever stock
  exists now.
- **Order closure of un-resolved failures** — that's the deferred partial-delivery-cancel slice.

---

## Concurrency
- `replace` locks the failed fulfillment `FOR UPDATE` (status + resolution guard), then `createInTx`
  locks the order `FOR UPDATE` — same fulfillment-then-order order as `ship`, so the two serialize
  without deadlock.
- Re-reservation locks inventory rows in `product_id ASC` (project-wide rule, shared with placement
  and ship).
- A double `replace` loses the resolution guard on the second attempt (the first stamped
  `REPLACED`) → `409`.

---

## Acceptance criteria

1. **Re-ship happy path.** Given a fulfillment in `FAILED` with stock available, `replace` creates a
   new `PENDING` fulfillment for the same order lines, linked via `replaces_fulfillment_id`, with
   fresh ACTIVE reservations; the failed one's `resolution` becomes `REPLACED`.
2. **Replacement delivers and is funded.** Shipping then delivering the replacement issues an
   invoice for the replaced lines' value and pays it in full from the prepayment that was never
   refunded; the order rolls up to `FULFILLED`/`CLOSED` as normal.
3. **Funding isolation (multi-fulfillment).** On an order with a sibling fulfillment still in
   flight, replacing the failed one neither refunds nor disturbs the sibling's prepayment.
4. **Insufficient stock.** If `available < qty` for any line, `replace` fails `409` and nothing is
   written (no reservation, no fulfillment, no resolution change).
5. **Single-use resolution.** `replace` on an already-`REFUNDED` fulfillment → `409`; `refundFailed`
   on an already-`REPLACED` fulfillment → `409`; a second `replace` → `409`.
6. **Status guard.** `replace` on a non-`FAILED` fulfillment (PENDING/SHIPPED/DELIVERED) → `409`.
7. **Over-fulfillment.** The failed fulfillment's quantity no longer blocks re-fulfilling its lines
   (guard excludes FAILED).
8. **Restock-on-return still works** after a replace (orthogonal; not gated on `resolution`).
9. **AuthZ.** `replace` requires MANAGER; STAFF/VIEWER → `403` and the service is never called.

---

## Tests

`api/src/test/java/.../fulfillment/ReplaceFailedFulfillmentIT.java` (TestContainers):
- fail → replace → new PENDING fulfillment, `replaces_fulfillment_id` set, failed one `REPLACED`,
  fresh ACTIVE reservation (AC 1).
- replace → ship → deliver → invoice issued + PAID from surviving prepayment; order CLOSED (AC 2).
- two-fulfillment order: fail+replace one, sibling's prepayment intact and still deliverable (AC 3).
- replace with no stock available → 409, no writes (AC 4).
- refund-then-replace → 409; replace-then-refund → 409; double replace → 409 (AC 5).
- replace a PENDING / SHIPPED / DELIVERED (not FAILED) fulfillment → 409 (AC 6).
- restock-on-return after a replace still restocks, orthogonal to `resolution` (AC 8).

`FailedFulfillmentHandlerAuthTest`: `replace` forbidden for STAFF, allowed for MANAGER (201) (AC 9);
malformed JSON body → 400 (not 500).
