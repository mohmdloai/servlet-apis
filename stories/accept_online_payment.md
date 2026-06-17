# Slice: Accept an online payment (manual InstaPay)

> Online flow **TX-3** per the outbound payment design.
> Closes the loop opened by [`place_online_order.md`](place_online_order.md): an order placed
> ONLINE sits in `PENDING_PAYMENT` until money arrives — this slice is how it gets there.

---

## Goal

An org **MANAGER** records a customer-claimed manual InstaPay transfer and verifies it in one admin
action. If the verified amount exactly covers the order's outstanding balance, a `payment` row is
created (`RECEIVED`, fully unallocated) and the order flips **`PENDING_PAYMENT → PAID`**.

`POST /api/orgs/{orgId}/payment-transactions`

Done means: customer sends InstaPay (out of band, with the order number in the transfer note), admin
verifies it, order moves `PENDING_PAYMENT → PAID`, and the `payment` row sits in `RECEIVED` with
`unallocated_amount == amount`.

---

## Scope

### In
- Endpoint: `POST /api/orgs/{orgId}/payment-transactions` — record **and** verify in one call.
- Idempotent recording: `INSERT … ON CONFLICT (provider, provider_ref) DO NOTHING` — a real-world
  money event is recorded at most once. Replaying the same `(provider, provider_ref)` returns the
  prior outcome (200), creating no second transaction or payment.
- Verification: `UNVERIFIED → VERIFIED`, stamping `verified_by` (the admin's `app_user.id`) +
  `verified_at`.
- Reconciliation against the target order (resolved by `sales_order_id` **or** `order_number`).
- On **MATCHED**: create `payment` (`RECEIVED`, `unallocated_amount = amount`, `sales_order_id` set),
  bump `sales_order.prepaid_amount`, and flip the order to `PAID`.
- New domain: `Payment`, `PaymentTransaction` (+ five payment enums).
- New repositories: `PaymentTransactionRepository`, `PaymentRepository`; three new methods on
  `SalesOrderRepository` (`findByIdForUpdate`, `findByOrderNumberForUpdate`, `updatePaymentState`).
- New services: `PaymentTransactionService.verify(...)`, `PaymentService.reconcileAndCreate(...)`.
- New handler `PaymentTransactionHandler` registered under `"payment-transactions"`.

### Out (deferred)
- **No `payment_allocation`** — online allocation happens at delivery/invoicing (a later slice). The
  payment stays fully unallocated until then.
- **No Payment for non-MATCHED outcomes** — `UNDERPAID` / `OVERPAID` / `ORPHAN` only stamp
  `payment_transaction.reconciliation_status` for admin follow-up. No `payment`, no `prepaid` change,
  order stays `PENDING_PAYMENT`. (Split / partial payments are a later slice.)
- No refunds, credit notes, disputes.
- No customer-facing self-submission — no customer auth exists yet (see
  [`../docs/customer-portal-future.md`](../docs/customer-portal-future.md)); the admin enters the
  claim.
- No `cash` / `instapay_in_store` channel specifics (the provider enum supports them; only the
  manual online path is exercised here).

### Known follow-ups (backlog)
- **No admin queue for VERIFIED + `UNDERPAID`/`OVERPAID` transactions.** `idx_txn_orphan` (V22) only
  indexes `reconciliation_status='ORPHAN'`, so a verified-but-mismatched transfer is recorded yet
  invisible to any "needs attention" query. Deferred deliberately — the under/over *handling* lands
  in the partial-payments slice, and that slice should add the partial index (e.g. on
  `reconciliation_status IN ('UNDERPAID','OVERPAID')`) together with the query that consumes it,
  rather than shipping a reader-less index now.
- **Currency mismatch returns 400 (rolls back the claim).** Intentional for admin-entered data (a
  typo is correctable); if a webhook/auto-feed ever drives this endpoint, reclassify the mismatch as
  a recorded ORPHAN so the event isn't lost. See the note in `PaymentService.reconcileAndCreate`.

---

## API contract

### Request
```
POST /api/orgs/{orgId}/payment-transactions
Content-Type: application/json
```
```json
{
  "provider":             "INSTAPAY_MANUAL",
  "provider_ref":         "IPN-2026-0001-ABC",
  "amount":               "250.00",
  "currency":             "EGP",
  "order_number":         "SO-2026-00042",
  "sales_order_id":       "<uuid>",
  "claimed_by_customer_id":"<uuid>",
  "customer_note":        "paid via InstaPay",
  "verification_proof":   "screenshot-url / ref",
  "occurred_at":          "<ISO-8601>"
}
```
- `provider` accepts the enum name (`INSTAPAY_MANUAL`) or the DB literal (`instapay_manual`).
- `currency` defaults to `EGP` when omitted.
- Provide **either** `order_number` or `sales_order_id` (id wins if both present). Neither → ORPHAN.

### Response — `201 Created` (first processing) / `200 OK` (idempotent replay)
```json
{
  "id": "<txn uuid>",
  "org_id": "<uuid>",
  "provider": "INSTAPAY_MANUAL",
  "provider_ref": "IPN-2026-0001-ABC",
  "direction": "CREDIT",
  "amount": "250.00",
  "currency": "EGP",
  "verification_status": "VERIFIED",
  "verified_by": "<admin uuid>",
  "verified_at": "<ISO-8601>",
  "reconciliation_status": "MATCHED",
  "occurred_at": "<ISO-8601>",
  "recorded_at": "<ISO-8601>",
  "payment": {
    "id": "<uuid>",
    "sales_order_id": "<uuid>",
    "amount": "250.00",
    "unallocated_amount": "250.00",
    "status": "RECEIVED"
  },
  "order": {
    "id": "<uuid>",
    "order_number": "SO-2026-00042",
    "status": "PAID",
    "grand_total": "250.00",
    "prepaid_amount": "250.00"
  }
}
```
`payment` and `order` are omitted when not applicable (e.g. ORPHAN with no order).

### Reconciliation outcomes (verified CREDIT amount vs. order, order locked `FOR UPDATE`)
| Outcome | Condition | Payment? | Order |
|---|---|---|---|
| `ORPHAN`    | no/blank ref, order not found, or order not `PENDING_PAYMENT` | no  | unchanged |
| `UNDERPAID` | `amount < grand_total − prepaid_amount` | no | stays `PENDING_PAYMENT` |
| `OVERPAID`  | `amount > grand_total − prepaid_amount` | no | stays `PENDING_PAYMENT` |
| `MATCHED`   | `amount == grand_total − prepaid_amount` | **yes** (`RECEIVED`) | → `PAID` |

The online PAID condition `LEAST(prepaid_amount, grand_total) = grand_total` is enforced in the
domain by `SalesOrder.markPaid` (requires `prepaid >= grand_total`); on MATCHED `prepaid` becomes
exactly `grand_total`.

### Errors
| Status | Cause |
|---|---|
| `400` | missing/blank `provider`/`provider_ref`, `amount <= 0`, malformed body, currency mismatch (txn vs order) |
| `403` | caller lacks MANAGER (or system ADMIN) in `:orgId` |
| `405` | non-POST, or any `/payment-transactions/...` sub-path (not in this slice) |

---

## Concurrency

Reconciliation locks the order row (`SELECT … FOR UPDATE`) before reading its status/totals, which
serializes it against the TTL expiry sweeper's `markExpiredIfPending` (slice 3). Whichever
transaction takes the lock first wins; the loser observes the committed state — so an order can never
be both `EXPIRED` and `PAID`. Recording is idempotent on the global `(provider, provider_ref)`
natural key, so retries never double-charge.

---

## Authorization

- `AuthzHelper.requireOrgAccess(orgId, MANAGER)` — system ADMIN bypasses.

---

## Acceptance criteria

- [ ] Verify an exact-amount transfer against a `PENDING_PAYMENT` order → `201`,
      `reconciliation_status = MATCHED`, order `PAID`, one `payment` row `RECEIVED` with
      `unallocated_amount == amount == grand_total`, txn `VERIFIED`.
- [ ] `amount < grand_total` → `UNDERPAID`; `amount > grand_total` → `OVERPAID`; both leave the order
      `PENDING_PAYMENT` and create no `payment`.
- [ ] Unknown `order_number` (and no `sales_order_id`) → `ORPHAN`, no `payment`.
- [ ] Replaying the same `(provider, provider_ref)` → exactly one `payment_transaction` and one
      `payment`; second call returns the same outcome with `200`.
- [ ] Caller without MANAGER in `:orgId` → `403`.
