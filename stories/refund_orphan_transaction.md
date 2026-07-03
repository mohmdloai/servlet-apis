# Slice: Refund an orphan transaction (the orphan queue's other exit)

> Closes the loop opened by [`accept_online_payment.md`](accept_online_payment.md): a VERIFIED
> manual-InstaPay arrival that matches no order lands **ORPHAN**. State machine E
> ([`state-machines.md`](../sys-analysis/system/state-machines.md)) gives the orphan queue two
> resolutions — *link manually* (shipped as `POST /payment-transactions/{id}/resolve`) and
> *refund* ([`refund.md` §Orphan transaction](../sys-analysis/outbound/refund.md),
> [`payment.md` §Orphan transactions](../sys-analysis/outbound/payment.md)). This slice ships the
> second one.

---

## Goal

An org **MANAGER** refunds a VERIFIED ORPHAN transaction that genuinely matches no order — wrong
reference, duplicate payment, payment for an already-expired order. Before this slice such money
was stuck permanently: it never became a `payment` (so a direct refund had no `payment_id` to
target) and no CreditNote was possible (nothing was ever billed), so the admin's only real option
was an off-books InstaPay transfer that left the daily money-in/money-out report wrong forever.

`POST /api/orgs/{orgId}/payment-transactions/{id}/refund`

Done means: one call promotes the transaction into a **standalone Payment** (`sales_order_id`
NULL, fully unallocated) **plus** a **PENDING direct refund** for the full amount, atomically.
The admin then performs the real reverse transfer and executes via the ordinary
`POST /refunds/{id}/execute` (two-step lifecycle) — the VERIFIED **DEBIT** transaction lands on
the books, the payment drains to `REFUNDED`, and the orphan leaves the queue.

---

## Why this slice is mostly composition

The schema anticipated this path from day one and the refund engine already shipped; the slice is
the missing bridge between them:

| Piece | Already existed | Added here |
|---|---|---|
| Order-less payment rows | `payment.sales_order_id` nullable "for orphan/unmatched Payments (rare admin path)" + `idx_payment_unmatched` (V23) | `Payment.createUnmatched(...)` domain factory (first writer of that shape) |
| PENDING direct refund + OWNER threshold gate | `RefundService.createDirectPendingInTx` (order-cancel slice) | reused verbatim — orphan refunds inherit the same approval bar |
| Execute / cancel lifecycle | `RefundService.execute` / `cancel` (two-step refund slice); `executeDirect` touches only the Payment, so a NULL order needs no changes | nothing |
| Replay marker | `resolveOrphan` already treats "a Payment exists for this txn" as *dispositioned* | `RefundRepository.findByPaymentId` + `RefundService.findOpenDirectByPaymentInTx` |
| Orchestration | — | `PaymentTransactionService.refundOrphan(...)` + the `/refund` route |

---

## The transaction (all in one `txDsl`)

1. Lock the transaction (`findByIdForUpdate`); 404 if unknown.
2. **Replay / conflict checks** via the 1:1 payment:
   - payment exists with `sales_order_id` set → **409** (that money was matched; it exits via
     order cancel or CreditNote, never this path).
   - payment exists standalone with an open (PENDING/EXECUTED) direct refund → **200 replay**
     returning that refund.
   - payment exists standalone but every refund was CANCELLED → re-open: fresh PENDING refund on
     the **same** payment (no duplicate promotion).
3. Guards for first disposition: direction `CREDIT` (400), `verification_status = VERIFIED`
   (409), `reconciliation_status = ORPHAN` (409).
4. `Payment.createUnmatched(...)`: `sales_order_id` NULL, `unallocated_amount = amount`,
   `customer_id` from `claimed_by_customer_id` when known, status `RECEIVED`.
5. `RefundService.createDirectPendingInTx(...)`: PENDING, full amount, method = request `method`
   or the transaction's own provider (money goes back the way it came). The org's
   `refund_approval_threshold` applies exactly as on any direct refund — above it, a non-OWNER
   caller gets 403 **and the whole promotion rolls back** (a standalone payment must never
   survive without its refund obligation).

**Deliberately not touched**: `reconciliation_status` stays **ORPHAN** — the transaction truly
never matched an order, and that history is the truth. Its *disposition* marker is the 1:1
Payment now bound to it (the same marker `resolveOrphan` replays on); the orphan-queue query
excludes transactions that already have a payment (`transaction.md` §Operational queries). This
avoids widening the `payment_reconciliation_status` Postgres enum for a fact the join already
expresses. From here the money's lifecycle lives on the Payment/Refund aggregates.

---

## Scope

### In
- New domain factory `Payment.createUnmatched(...)` (standalone, order-less, RECEIVED).
- `RefundRepository.findByPaymentId(orgId, paymentId)` (+ impl, oldest-first).
- `RefundService.findOpenDirectByPaymentInTx(...)` — first non-CANCELLED refund on a payment.
- `PaymentTransactionService.refundOrphan(...)` (+ `OrphanRefundResult`); constructor gains
  `RefundService` (AppConfig builds RefundService before PaymentTransactionService).
- Route `POST /payment-transactions/{id}/refund` in `PaymentTransactionHandler`; DTOs
  `RefundOrphanRequest` / `OrphanRefundResponse`.

### Out (deferred)
- **Partial orphan refunds** — always the full transaction amount. Nothing was billed, so there
  is no defensible split; keeping part of unowed money is not a v1 operation.
- **An orphan-queue read endpoint** — the queue is still SQL/dashboard territory
  (`transaction.md` §Operational queries). When it ships it must use the payment-exists
  exclusion documented there.
- **Batch disposition** — one transaction per call.

---

## Concurrency & idempotency

- The transaction row is locked `FOR UPDATE` for the whole disposition; concurrent
  `/refund` + `/resolve` calls serialise on it, and whichever commits first wins — the loser
  sees the payment and gets a replay (its own kind) or a 409 (the other kind).
- Replay is keyed on the 1:1 `payment.payment_transaction_id` (UNIQUE): retrying `/refund`
  returns the existing open refund (200), creating nothing.
- Executing the refund is protected downstream by `(provider, provider_ref)` UNIQUE on the
  DEBIT transaction — the standard double-execute backstop.

---

## API contract

### Request
```
POST /api/orgs/{orgId}/payment-transactions/{id}/refund
Content-Type: application/json
```
```json
{
  "method": "INSTAPAY_MANUAL",
  "notes":  "duplicate payment, customer called"
}
```
- Body optional (empty body is valid). `method` accepts enum name or DB literal; defaults to the
  transaction's provider. `notes` defaults to a standard orphan-refund note.

### Response — `201 Created` (first disposition) / `200 OK` (idempotent replay)
```json
{
  "transaction": {
    "id": "<txn uuid>",
    "provider": "INSTAPAY_MANUAL",
    "verification_status": "VERIFIED",
    "reconciliation_status": "ORPHAN",
    "payment": {
      "id": "<uuid>",
      "amount": "250.00",
      "unallocated_amount": "250.00",
      "status": "RECEIVED"
    }
  },
  "refund": {
    "id": "<uuid>",
    "payment_id": "<uuid>",
    "amount": "250.00",
    "method": "INSTAPAY_MANUAL",
    "status": "PENDING"
  }
}
```
(`transaction` reuses the verify response shape; `payment.sales_order_id` is absent — NULL.)

### Errors
| Status | Cause |
|---|---|
| `400` | transaction is a DEBIT; unknown `method` |
| `403` | caller lacks MANAGER; amount > `refund_approval_threshold` and caller is not OWNER/system ADMIN (whole call rolls back) |
| `404` | transaction not found in `:orgId` |
| `409` | transaction not VERIFIED; not ORPHAN (e.g. MATCHED/UNDERPAID/OVERPAID); already matched to an order |

---

## Authorization
- `AuthzHelper.requireOrgAccess(orgId, MANAGER)` — same bar as record-and-verify / resolve.
- OWNER (or system ADMIN) required above the org's `refund_approval_threshold` (V33, default
  500.00) — enforced in `createDirectPendingInTx`, no separate gate here.

---

## Tests

`api/src/test/java/.../payment/OrphanRefundIT.java` (TestContainers Postgres, drives the services):
- happy path: standalone payment (order NULL, RECEIVED, fully unallocated, 1:1 with txn) + PENDING
  refund, method defaulted to `instapay_manual`, **no DEBIT yet**, txn stays ORPHAN + VERIFIED.
- execute-through: refund EXECUTED with linked DEBIT txn; payment drained (`unallocated = 0`,
  `REFUNDED`); exactly one VERIFIED DEBIT on the books.
- replay: second call → 200-shape result, same refund id, still one payment / one refund.
- explicit `method` + `notes` override the defaults.
- MATCHED transaction → 409, no refund created.
- unknown transaction → 404.
- above threshold (600 > default 500): non-OWNER → 403 **and no payment row survives**
  (atomic rollback); OWNER flag → succeeds for the full 600.
- cancelled refund re-opens: new PENDING refund, **same** payment, no duplicate promotion.
- `OrphanResolutionIT.verifiedOrphan_noMatchingOrder_matchFailsCleanly_refundIsTheExit` replaces
  the old test that empirically pinned the gap.
