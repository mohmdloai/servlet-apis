# Slice 8: CreditNote + Refund (both paths)

> Outbound refund machinery per [`refund.md`](../sys-analysis/outbound/refund.md), the gross
> `paid_amount` rule locked in [`invoicing.md`](../sys-analysis/outbound/invoicing.md#field-rationale),
> and the [SalesInvoice](../sys-analysis/system/state-machines.md#c-salesinvoice) /
> [Payment](../sys-analysis/outbound/payment.md) state machines. This slice closes the loop the
> earlier slices deliberately left open ("No post-checkout return / void — CreditNote + Refund is a
> later slice", `in_store_sale.md` §Scope/Out).

---

## Goal

Return goods or refund an overpayment — give money back **only** with one of two authorizations,
each recorded as a DEBIT `PaymentTransaction`:

1. **CreditNote-backed** — for returns / cancellations / pricing errors / goodwill / dispute
   resolution. A `CreditNote` mirrors a `SalesInvoice`; the refund unwinds that invoice's
   `PaymentAllocation`s.
2. **Direct-from-Payment** — for overpayments / unallocated excess that was never billed. No
   CreditNote; the money comes straight off `payment.unallocated_amount`.

Done means: an admin can issue a `CreditNote` (`ISSUED`, gapless `CN-YYYY-NNNN`), create a `Refund`
(`PENDING`), and execute it (`EXECUTED`) — `RefundService.execute(refundId)` writes the DEBIT
`PaymentTransaction`, links it via `refund.payment_transaction_id`, and moves the source aggregates,
**all or nothing**. The original `SalesInvoice` stays **PAID** (gross rule); the `CreditNote` is the
public credit record.

---

## Why this slice is two paths over one `execute`

Both flavors converge on `RefundService.execute(txDsl, orgId, refundId)`: lock the `PENDING` refund,
create the DEBIT transaction, link it, move the source, stamp `EXECUTED`. They diverge only in **what
"move the source" means** — and the `refund` row already records which by the exactly-one-of CHECK
(`credit_note_id` xor `payment_id`):

| Step | CreditNote-backed (`credit_note_id` set) | Direct-from-Payment (`payment_id` set) |
|---|---|---|
| Authorization | An `ISSUED` `CreditNote` against a `SalesInvoice` | `payment.unallocated_amount` on a real `Payment` |
| Validation | `SUM(refund_allocation) ≤ original payment_allocation` per allocation | `refund.amount ≤ payment.unallocated_amount` |
| `refund_allocation` rows | one or more, FIFO over the invoice's allocations, `SUM = refund.amount` | **none** |
| Invoice | untouched — stays **PAID**, `paid_amount` unchanged (gross rule) | not involved |
| Payment cache | `refunded_amount += refund.amount` | `unallocated_amount -= refund.amount` |
| Payment status | `PARTIALLY_REFUNDED` / `REFUNDED` | `ALLOCATED` / `RECEIVED` / `REFUNDED` (see rules) |
| CreditNote | `→ SETTLED` when cumulative executed refunds `≥ credit_note.total` | n/a |

---

## The refund execution transaction (all in one `txDsl`)

`RefundService.execute(...)` opens `rootDsl.transactionResult(...)` (service owns the boundary;
repos receive the `DSLContext` — established pattern). Steps:

1. **Lock the refund** `FOR UPDATE` (`findByIdForUpdate(orgId, refundId)`). Reject if not `PENDING`
   (idempotency / double-execute guard → `409`).
2. **Create the DEBIT `PaymentTransaction`** — `provider = refund.method`,
   `direction = DEBIT`, `amount = refund.amount`, created **VERIFIED** on the spot
   (`verified_by = actor`, `verified_at = now`), `provider_ref` from the request or synthesised
   (`<method>-<refund_id>`). The `(provider, provider_ref) UNIQUE` constraint is the double-execute
   backstop.
3. **Branch on source:**
   - **CreditNote path** — load the `CreditNote` (must be `ISSUED`), resolve its
     `sales_invoice_id`'s `PaymentAllocation`s locked `FOR UPDATE` ordered `received_at ASC, id ASC`
     (FIFO, deterministic tiebreak). Walk them, writing `RefundAllocation(payment_allocation_id,
     amount)` rows until `refund.amount` is covered, each capped at
     `original_allocation.amount − already-refunded-against-it`. For each touched `Payment`:
     `refunded_amount += unwound`, recompute status. If cumulative executed refunds for the
     CreditNote `≥ credit_note.total` → CreditNote `→ SETTLED`.
   - **Direct path** — load the `Payment` `FOR UPDATE`, assert
     `refund.amount ≤ payment.unallocated_amount`, `unallocated_amount -= refund.amount`, recompute
     status. No `refund_allocation` rows.
4. **Link + stamp** — `refund.payment_transaction_id = txn.id`, `refund.status = EXECUTED`,
   `executed_at = now`.

Invoice is **never** touched on either path (gross rule). Order is not touched.

### Payment status recompute (single rule, both paths)
Given the new caches `unallocated_amount` and `refunded_amount` and `allocated = amount −
unallocated_amount − refunded_amount`:
- `refunded_amount == amount` → **REFUNDED**
- `0 < refunded_amount < amount` → **PARTIALLY_REFUNDED**
- else fall back to the existing allocation rule (`unallocated == 0 && allocated > 0` → `ALLOCATED`;
  `0 < unallocated < amount` → `PARTIALLY_ALLOCATED`; `unallocated == amount` → `RECEIVED`).

---

## Scope

### In
- **Migrations** (the two tables already exist as `V25`/`V26`):
  - `V31__Create_credit_note_number_counter.sql` — `(org_id, year, next_val)` PK `(org_id, year)`,
    a clone of `invoice_number_counter` (V30) for gapless `CN-YYYY-NNNN`.
  - `V32__Add_payment_refunded_amount.sql` — `ALTER TABLE payment ADD COLUMN refunded_amount
    NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0)`; cache mirroring
    `invoice.paid_amount`.
  - `V33__Add_org_refund_approval_threshold.sql` — `ALTER TABLE org ADD COLUMN
    refund_approval_threshold NUMERIC(14,2) NOT NULL DEFAULT 500 CHECK (refund_approval_threshold >=
    0)`; the per-org boundary above which an OWNER (not just MANAGER) must authorize returning money.
- **Per-org threshold config**: `Org` gains `refundApprovalThreshold`; `OrgService` loads it and the
  existing OWNER-gated `PUT /api/orgs/{orgId}` accepts an optional `refund_approval_threshold` in the
  body so owners self-serve the knob (read back on `GET /api/orgs/{orgId}`). No new endpoint.
- **Domain**: `CreditNote` (DRAFT→ISSUED→SETTLED|VOID; `createDraft`/`rehydrate`/`issue`/`settle`/
  `void`), `CreditNoteLine`, `Refund` (PENDING→EXECUTED|CANCELLED; `createPending`/`rehydrate`/
  `execute`/`cancel`), `RefundAllocation`. Enums `CreditNoteReason`, `CreditNoteStatus`,
  `RefundStatus`. `Payment` gains `recordRefund(amount, now)` and `refundedAmount` field + the status
  recompute above.
- **Repository**: `CreditNoteRepository` (`insert(note, lines)`, `findById`, `findByInvoiceId`,
  `updateStatus`, `claimCreditNoteNumber(orgId, year)`), `RefundRepository` (`insert`, `findById`,
  `findByIdForUpdate`, `updateExecution`, `sumExecutedByCreditNote`), `RefundAllocationRepository`
  (`insert`, `sumByPaymentAllocation(allocationId)`). `PaymentAllocationRepository` gains
  `findByInvoiceIdForUpdate(orgId, salesInvoiceId)`. `PaymentRepository.updateAllocationState`
  extended to persist `refunded_amount`.
- **Service**:
  - `CreditNoteService.issue(orgId, cmd, actor)` — validate against the target `SalesInvoice`
    (must exist, same org, `ISSUED`/`PAID`; lines ≤ what was billed for product+qty), freeze totals,
    claim the gapless number, insert `ISSUED`. Own `rootDsl.transactionResult`.
  - `CreditNoteService.void(orgId, id, actor)` — `ISSUED → VOID`, allowed **only if no EXECUTED
    refund** references it.
  - `RefundService.create(orgId, cmd, actor)` — build a `PENDING` refund; enforce exactly-one-source,
    `amount > 0`, `method ∈ {CASH, INSTAPAY_MANUAL, INSTAPAY_IN_STORE}`, and the upper bound for the
    chosen path (CreditNote: `amount ≤ credit_note.total − already-refunded`; Payment:
    `amount ≤ payment.unallocated_amount`).
  - `RefundService.execute(orgId, refundId, actor)` — the two-path transaction above.
  - `RefundService.cancel(orgId, refundId, reason, actor)` — `PENDING → CANCELLED`; CreditNote stays
    `ISSUED` (per spec).
- **API / Handlers** wired into `OrgServlet` dispatch + `AppConfig`:
  - `CreditNoteHandler` — `POST /credit-notes` (issue), `GET /credit-notes/{id}`,
    `POST /credit-notes/{id}/void`.
  - `RefundHandler` — `POST /refunds` (create PENDING), `GET /refunds/{id}`,
    `POST /refunds/{id}/execute`, `POST /refunds/{id}/cancel`.

### Out (deferred)
- **Store credit / customer wallet** — v1 refunds cash/transfer only (`refund.md` §What's not here).
- **Auto-execution via PSP API** — admin executes manually and records the resulting transaction.
- **`last_error` retry field on refund** — execution failure handling beyond "stays PENDING" is a
  follow-up; v1 surfaces the error and leaves the refund `PENDING` for re-execute/cancel.
- **Multi-currency / partial tax-only correction** — out of scope.

---

## Concurrency & idempotency
- The whole execute is one `rootDsl.transactionResult(...)`: any failure rolls back the DEBIT txn,
  the `refund_allocation` rows, and every cache move — no half-executed refund.
- `refund` locked `FOR UPDATE`; status guard rejects a second `execute` on an already-`EXECUTED`
  refund (`409`) before any side effect.
- `payment_transaction (provider, provider_ref) UNIQUE` and `refund.payment_transaction_id UNIQUE`
  are the backstops: a retried execute that re-uses a ref fails the constraint rather than issuing a
  second DEBIT.
- `PaymentAllocation`s locked `FOR UPDATE` in `received_at ASC, id ASC` order (matches the
  auto-allocation lock discipline) so concurrent refunds against the same invoice serialise.
- CreditNote numbering claims its counter row with a row-level lock (same `claimInvoiceNumber`
  pattern) — gapless per-org per-year.

---

## API contract (all org-scoped, JSON snake_case)

### Issue CreditNote — `POST /api/orgs/{orgId}/credit-notes` → `201`
```json
{
  "sales_invoice_id": "<uuid>",
  "reason": "RETURN",
  "reason_note": "customer returned 1 unit, unopened",
  "lines": [ { "product_id": "<uuid>", "description": "...", "quantity": 1,
              "unit_price": "100.00", "tax_rate": "0.14" } ]
}
```
Response: `{ "id", "credit_note_number": "CN-2026-0001", "status": "ISSUED", "sales_invoice_id",
"subtotal", "tax_total", "total", "lines": [...] }`.

### Create Refund (PENDING) — `POST /api/orgs/{orgId}/refunds` → `201`
```json
{ "credit_note_id": "<uuid>", "amount": "114.00", "method": "INSTAPAY_MANUAL", "notes": "..." }
```
or the direct path:
```json
{ "payment_id": "<uuid>", "amount": "100.00", "method": "CASH" }
```
Exactly one of `credit_note_id` / `payment_id`. Response: `{ "id", "status": "PENDING", "amount",
"method", "credit_note_id"|"payment_id" }`.

### Execute Refund — `POST /api/orgs/{orgId}/refunds/{id}/execute` → `200`
Body optional `{ "provider_ref": "IP-778899-RETURN" }`. Response: `{ "id", "status": "EXECUTED",
"payment_transaction_id", "executed_at", "amount" }`.

### Cancel Refund — `POST /api/orgs/{orgId}/refunds/{id}/cancel` → `200`
Body `{ "reason": "customer changed mind" }`.

### Void CreditNote — `POST /api/orgs/{orgId}/credit-notes/{id}/void` → `200`

### Errors
| Status | Cause |
|---|---|
| `400` | both/neither of `credit_note_id`/`payment_id`; `amount ≤ 0`; bad `method`; invalid/empty CreditNote lines; CreditNote total exceeds what the invoice billed |
| `403` | caller lacks the required role in `:orgId`; or amount exceeds `org.refund_approval_threshold` and caller is not OWNER (see Authorization) |
| `404` | unknown invoice / credit note / payment / refund in this org |
| `409` | execute a non-`PENDING` refund; cancel a non-`PENDING` refund; void a CreditNote with an EXECUTED refund; refund amount exceeds available (`> credit_note.total − refunded`, `> payment.unallocated_amount`, or `> original allocation`) |

---

## Authorization (`AuthzHelper.requireOrgAccess`; system ADMIN bypasses)

Base role gates, **plus** a per-org amount threshold (`org.refund_approval_threshold`, default
500 EGP) that escalates the money-authorizing action to **OWNER** when the amount exceeds it:

- **Issue CreditNote** — **MANAGER+** when `credit_note.total ≤ threshold`; **OWNER** above. (This
  is the authorization for CreditNote-backed refunds, so the gate lives here, per `refund.md`.)
- **Create direct Refund** (`payment_id`, no CreditNote) — **MANAGER+** when `amount ≤ threshold`;
  **OWNER** above. Closes the gap where a large overpayment refund would otherwise skip the OWNER
  gate (no CreditNote issuance to catch it).
- **Create / execute CreditNote-backed Refund** (`credit_note_id`) — **MANAGER+** flat; the amount
  was already OWNER-gated at CreditNote issuance, so no double gate.
- **Cancel Refund** — **MANAGER+**.
- **Void CreditNote** — **OWNER**.
- **Read CreditNote / Refund** — **VIEWER+**.
- **Set `refund_approval_threshold`** (via `PUT /api/orgs/{orgId}`) — **OWNER** (existing org-update
  gate).

The threshold is read inside the same transaction that issues the CreditNote / creates the direct
Refund, so a concurrent threshold change can't race the authorization decision (`403` is raised
before any side effect, against the committed org row).

---

## Tests

`api/src/test/java/.../refund/CreditNoteRefundIT.java` (TestContainers Postgres, drives the services):
- **CreditNote-backed full return**: issue invoice (reuse the slice-6/7 path) → issue
  `CreditNote(RETURN)` `CN-2026-0001` `ISSUED` → create `Refund(credit_note_id)` `PENDING` → execute
  → `EXECUTED` with a DEBIT `PaymentTransaction` (`direction=DEBIT`, `VERIFIED`) linked; exactly the
  expected `refund_allocation` rows summing to `refund.amount`; original `Payment`
  **REFUNDED**, `refunded_amount == amount`; **invoice stays PAID, `paid_amount` unchanged**
  (gross rule asserted explicitly); CreditNote **SETTLED**.
- **CreditNote-backed partial**: refund < credit_note.total → Payment **PARTIALLY_REFUNDED**;
  CreditNote stays **ISSUED** (not yet SETTLED); a second refund settling the remainder flips it to
  **SETTLED**.
- **Direct-from-Payment overpayment**: Payment with `unallocated_amount = 100` → direct
  `Refund(payment_id, amount=100)` execute → `unallocated_amount = 0`, **no** `refund_allocation`
  rows, DEBIT txn linked; Payment **ALLOCATED** (had allocations) — and the orphan-payment variant
  (no allocations) → **REFUNDED**.
- **Over-refund rejected**: CreditNote path `SUM(refund_allocation) > original allocation` and direct
  path `amount > unallocated_amount` both → `409`, nothing written.
- **Exactly-one-source**: both ids set or neither → `400`.
- **Double execute**: second `execute` on an `EXECUTED` refund → `409`, no second DEBIT txn
  (constraint-backed).
- **Void guards**: void CreditNote with an EXECUTED refund → `409`; void an unused `ISSUED`
  CreditNote → `VOID`.
- **Authz**: STAFF issuing a CreditNote → `403`; MANAGER void → `403` (OWNER only).
- **Threshold**: with `refund_approval_threshold = 500`, MANAGER issues a `CN` of `400` → ok, of
  `600` → `403`; MANAGER creates a direct Refund of `600` → `403`, OWNER → ok; a CreditNote-backed
  Refund of `600` created by MANAGER → ok (already OWNER-gated at issuance). OWNER raises the
  threshold via `PUT /api/orgs/{orgId}` and the `600` CN then succeeds for MANAGER.
- **Regression**: `DeliverInvoiceIT`, `InStoreSaleIT`, `AcceptOnlinePaymentIT` still pass —
  `paid_amount` / allocation behaviour unchanged by the `refunded_amount` addition.

---

## Acceptance criteria

- [ ] **AC1 — Two authorizations, never bare money.** A `Refund` row always has exactly one of
  `credit_note_id` / `payment_id` (DB CHECK + service validation); there is no code path that creates
  a DEBIT transaction without one.
- [ ] **AC2 — One execute, two branches.** `RefundService.execute(refundId)` handles both paths;
  CreditNote-backed produces `refund_allocation` rows (`SUM = refund.amount`, each `≤` its original
  `payment_allocation`), direct-from-Payment produces **zero** rows and decrements
  `payment.unallocated_amount`.
- [ ] **AC3 — DEBIT pairing.** Every `EXECUTED` refund has a unique linked DEBIT `PaymentTransaction`
  (`refund.payment_transaction_id`), `VERIFIED`, `amount = refund.amount`,
  `provider = refund.method`.
- [ ] **AC4 — Gross invoice rule.** A CreditNote-backed refund **does not** change the source
  invoice's `paid_amount` or move it off `PAID`; the CreditNote is the credit record.
- [ ] **AC5 — Payment status.** Source `Payment` moves to `PARTIALLY_REFUNDED` / `REFUNDED` per the
  single recompute rule; `refunded_amount` cache reflects executed refunds.
- [ ] **AC6 — CreditNote lifecycle.** Issued gapless per-org per-year `CN-YYYY-NNNN`; `→ SETTLED`
  exactly when cumulative executed refunds `≥ total`; voidable **only** with no EXECUTED refund.
- [ ] **AC7 — Atomic + idempotent.** Execute is one transaction (rollback leaves no DEBIT txn, no
  allocation rows, no cache drift); re-executing an `EXECUTED` refund is rejected `409` with no second
  transaction.
- [ ] **AC8 — Bounds enforced.** Over-refund (beyond credit-note total, beyond an original
  allocation, or beyond unallocated) is rejected before any side effect.
- [ ] **AC9 — Endpoints + authz.** `CreditNoteHandler` and `RefundHandler` are wired into `OrgServlet`
  / `AppConfig` with the role gates above; all endpoints are org-scoped and validate `orgId` access.
- [ ] **AC10 — Coverage.** `CreditNoteRefundIT` covers both paths, partial + full, over-refund,
  double-execute, void guards, and authz; the prior slices' ITs still pass.
- [ ] **AC11 — Per-org approval threshold.** `org.refund_approval_threshold` (default 500, OWNER-
  settable via `PUT /api/orgs/{orgId}`) escalates to OWNER: CreditNote issuance above the threshold
  and direct-from-Payment refund creation above the threshold both require OWNER; the gate is checked
  against the committed org row before any side effect, and CreditNote-backed refunds are not
  double-gated.
