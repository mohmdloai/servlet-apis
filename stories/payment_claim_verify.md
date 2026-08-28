# Slice: Verify the claim, don't re-record it — phase 1 (verify by id, and the guard)

> Branch **`167_feat/payment-claim-verify`** off `master`, migration **V90**. First of three phases
> of the design in `ststore/design/payment-claims.html` (canvas + `payment-claims.src/build.mjs`);
> phase 2 is `payment_claim_not_found.md` (branch `168_feat/…`, merges after this one), phase 3 is
> frontend-only polish plus a small `supersedes_claim_id` on the record path. Frontend twin:
> `frontst/stories/132_st_verify_claims.md`. Re-verify PR counters at PR time.

---

## The problem, verified in the code (2026-08-28)

A storefront shopper files a payment claim (`POST /api/public/orders/{token}/payment-claim`, portal
twin `/api/portal/orders/{orderNumber}/payment-claim`). `PaymentTransactionService.claim()` inserted
an **UNVERIFIED** `INSTAPAY_MANUAL` CREDIT `payment_transaction` with `amount = order outstanding`,
`claimed_by_customer_id`, `customer_note`, `proof_object_key` — and **discarded the order id** (the
order was looked up only to validate; `PaymentTransaction` had no order field).

On the admin side the only verify path was the free-form record, `POST
/api/orgs/{orgId}/payment-transactions` (provider, provider_ref, amount, order…), idempotent on
`(provider, provider_ref)`: typing the claim's exact reference verified the claim; **any other string
minted a second VERIFIED + MATCHED transaction**, the order went PAID through it, and the real claim
stayed UNVERIFIED forever (verifying it later = duplicate → OVERPAID / ORPHAN). The phantom.

Four more holes: `OrderExpiryService` flipped PENDING_PAYMENT → EXPIRED at `expires_at` with no
knowledge of claims (a shopper who paid at 23:50 on a 00:00 deadline lost the order and the stock
while the merchant slept); `customer_note` was on the row but not on `PaymentTransactionResponse`
(the one line that explains a name mismatch never reached the person deciding); the queue had no
urgency order; and the transaction detail offered no verify action at all.

## The approach — five rules

1. **The claim is the unit of work.** Verification is a decision about an existing row, by id. The
   reference is evidence shown to the manager, never an input.
2. **Flip the transcription.** The system hands the manager the reference; the manager confirms it
   exists in the bank app. Nothing is typed.
3. **Money stays honest.** No prefilled amount fields. "Found it" takes the claim's snapshot; "Different
   amount" sends the bank's figure; UNDER / OVER reconcile exactly as today. `provider_ref` is
   immutable — a wrong reference is NOT_FOUND + a new row, never an edit.
4. **Urgency is the order's clock.** A claim extends the order's hold (+48 h, once per row); the queue
   is sorted by the claimed order's `expires_at`.
5. **The backend closes the hole.** Recording a free-form transfer against an order with open claims is
   `409 CLAIM_PENDING` listing them, unless every claim id is explicitly acknowledged.

### Claim lifecycle (verification machine §D; reconciliation untouched)

```
[*] → UNVERIFIED            shopper files (order stored, hold extended)
UNVERIFIED → VERIFIED       Found it → reconcileAndCreate: MATCHED / UNDERPAID / OVERPAID / ORPHAN
UNVERIFIED → NOT_FOUND      Can't find it                                   (phase 2)
NOT_FOUND → VERIFIED        found after all (a second look)
NOT_FOUND → UNVERIFIED      shopper re-files the same reference             (phase 2)
UNVERIFIED|NOT_FOUND → ABANDONED   a sibling claim paid the order, or the order was cancelled /
                            expired — reached by the system, never by a button
```

`NOT_FOUND` and `ABANDONED` already existed in the PG enum (V22) and the Java enum — no enum change.

## What this phase ships

### Migration V90

```sql
ALTER TABLE payment_transaction
  ADD COLUMN claimed_sales_order_id UUID REFERENCES sales_order(id),
  ADD COLUMN not_found_reason       TEXT;
CREATE INDEX idx_txn_claim_open ON payment_transaction (claimed_sales_order_id)
  WHERE verification_status IN ('UNVERIFIED', 'NOT_FOUND');
```

Nullable on purpose: `payment.sales_order_id` stays "where the money went"; the claim column answers
"which order did the shopper say" and survives that being wrong. **Only a row born as a shopper claim
sets it** — an admin's free-form record and an in-store tender leave it NULL (`createClaimed` takes it
as an explicit argument; `PaymentService.recordInStorePayment` and the record path pass `null`).

### Domain

- `PaymentTransaction`: `claimedSalesOrderId`, `notFoundReason`, `rawPayload` (audit JSON text,
  persisted as the existing `raw_payload` JSONB); `amount` / `occurredAt` are no longer final —
  `applyBankDetails(amount?, occurredAt?, proof?, now)` restates them while the claim is still open;
  `verify()` now accepts **NOT_FOUND as well as UNVERIFIED** (a second look must not need the shopper
  to re-file) and clears the reason; `abandon(now)`; `isOpenClaim()`.
- `SalesOrder.extendHold(until, now)` — monotonic `expires_at = max(current, until)`, PENDING_PAYMENT
  only, returns whether it moved.
- `OrgHealth.claimsToVerify`.
- Constants on the service: `CLAIM_HOLD = 48h`, `NOT_FOUND_GRACE = 6h` (org knobs later).

### Endpoints

| Route | Role | Behaviour |
|---|---|---|
| `POST /payment-transactions/{id}/verify` body `{amount?, occurred_at?, verification_proof?}` (body optional) | MANAGER | Locks the claimed order, then the row (`findByIdForUpdate`). UNVERIFIED or NOT_FOUND → VERIFIED, then `reconcileAndCreate` against `claimed_sales_order_id` — same MATCHED / UNDERPAID / OVERPAID outcomes, same PAID flip, same ORDER_PAID mail. `amount` overrides the claim's snapshot when given. When the order goes **PAID**, every other open claim on it → ABANDONED in the same txn. Already VERIFIED → **200** replay with the existing payment (+ `verified_by_name`). ABANDONED → **409**. Order no longer PENDING_PAYMENT → VERIFIED + ORPHAN with the order in the response (the client says why; orphan exits apply). **201** on the verify. Response = `PaymentTransactionResponse` with the claim context. |
| `POST /payment-transactions` (existing record) + `acknowledge_claim_ids?: uuid[]` | MANAGER | When this call mints a **new** row and the named order has UNVERIFIED claims → **409** `{kind: "CLAIM_PENDING", message, claims: [{id, provider_ref, amount, filed_at, has_proof}]}` and the whole transaction rolls back (no phantom row). With every open claim id acknowledged the record proceeds and the ids land in the row's `raw_payload` (`{"acknowledged_claim_ids":[…]}`). NOT_FOUND claims do not block (the manager already looked for those). Same reference as a claim → today's behaviour (verifies the claim). The reference of an ABANDONED claim → 409. The unmatched / orphan path (no order named) is untouched. |
| `GET /payment-transactions?verification_status=UNVERIFIED` (existing filter) | VIEWER | Every row now carries `customer {id, name, phone}`, `claimed_order {id, order_number, status, grand_total, prepaid_amount, expires_at}`, `claimed_sales_order_id`, `customer_note`, `has_proof` (primitive, always on the wire), `not_found_reason`. Two batch loads per page (customers, orders), never N+1. **Ordering for UNVERIFIED / NOT_FOUND views: `claimed_order.expires_at ASC NULLS LAST, recorded_at ASC, id ASC`** — the queue is sorted by what it can lose. Other filters keep the queue-vs-ledger convention. |
| `GET /payment-transactions?sales_order_id=` | VIEWER | New filter on `claimed_sales_order_id`; composes with `verification_status`. Backs the order page's "customer says they paid" card. Malformed → 400. |
| `GET /payment-transactions/{id}` | VIEWER | Adds `customer`, `claimed_order`, `customer_note`, `not_found_reason`, `verified_by_name` (display name, else email). `proof_url` stays detail-only. |
| `POST /api/public/orders/{token}/payment-claim` + portal twin (existing) | anon / customer | Store the order id; `extendHold(now + 48h)` **once per claim row** (the V19 reservation mirror moves with it); a replay of the same reference extends nothing. A claim on an order that is not PENDING_PAYMENT is still recorded (verify later says ORPHAN with the status). |
| `GET /health` (existing) | MANAGER | Adds `claims_to_verify` (UNVERIFIED CREDIT count). `AdminOrgDetailResponse.Health` carries it too (same shape rule). |

### Sweeper / cancel

`OrderExpiryService` — the rule for *when* an order expires is untouched (a claim moved the deadline at
filing time, which is the only way a claim ever influences the sweep); after the flip + release it
closes the order's open claims ABANDONED. `OrderCancellationService.cancel` does the same after
`updateCancelledState`. Both take `PaymentTransactionRepositoryFactory` now.

### Lock order

Order **then** claim row, everywhere: the sweeper (`markExpiredIfPending` then `abandonOpenClaims`),
cancel (`findByIdForUpdate` order → abandon), the record path (reconcile locks the order, then
`update` on the row) and `verifyClaim` (explicit `lockOrder` on the claimed order **before**
`findByIdForUpdate` on the claim). Taking the claim first would be an AB/BA deadlock against the
sweeper.

## Deviations from the brief, with reasons

- **Sibling abandonment landed in phase 1**, not phase 2: it is the same repository primitive the
  sweeper and cancel need (`abandonOpenClaims`), and shipping the PAID flip without it would leave
  ABANDONED unreachable from the one place the design says it is reached.
- **Siblings are abandoned only when the order goes PAID (MATCHED / OVERPAID), not on UNDERPAID.**
  The brief says "other open claims on that order → ABANDONED" unconditionally. On UNDERPAID the
  order is still owed money and the second claim may be the shopper's top-up transfer; abandoning it
  would make its reference unrecordable forever (the record path refuses an ABANDONED reference, and
  ABANDONED is terminal). On ORPHAN the order was not payable and the sweeper / cancel already closed
  its claims.
- **`markNotFound(by, reason, now)`** is phase 2's; this phase adds `abandon`, `applyBankDetails` and
  the relaxed `verify` only. There is no `not_found_by` column, so the actor will be logged (and, in
  phase 2, written to `raw_payload`) rather than invented as a field.
- `verified_by_name` rides the **verify response** as well as the detail (the brief lists it on the
  detail); the client that just pressed "Found it" needs it for "verified by you" without a second read.

## Tests — `PaymentClaimVerifyIT` (Testcontainers, the real repositories)

claim stores the order + extends the hold once (order **and** the reservation mirror; replay does not
stack; a longer hold is never shortened; an EXPIRED order extends nothing) · verify by id → MATCHED /
PAID / hold cleared / verifier named · amount override → UNDERPAID / OVERPAID (persisted) · replay
200 with the first verifier standing · ABANDONED → 409 · NOT_FOUND → VERIFIED clears the reason ·
PAID abandons the other open claims · UNDERPAID keeps them open · EXPIRED order → ORPHAN carrying
the order · record path: 409 CLAIM_PENDING (row rolled back, claim untouched, `has_proof` carried),
partial acknowledgement still 409, full acknowledgement proceeds + audits + abandons, same reference
verifies the claim, NOT_FOUND does not block, orphan path untouched, ABANDONED reference → 409 ·
list: UNVERIFIED ordered by the order's clock NULLS LAST and enriched, `?sales_order_id=` composes
with status · detail carries customer / claimed order / verifier · expiry and cancel abandon open
claims · health counts. `PaymentTransactionHandlerAuthTest`: STAFF / VIEWER 403 on `/verify`,
MANAGER 201 with an empty body, replay 200.

Gate: `mvn -o -q spotless:apply && mvn -o install -DskipTests && mvn -o test -pl api -Dtest=…`
(see the phase report).

## Out of scope (phase 2 / 3)

`POST /{id}/not-found` + reason + `PAYMENT_NOT_FOUND` notification + the 6 h re-arm · `payment_claim`
on the public + portal order reads · re-open on re-file · `supersedes_claim_id` on the record path ·
org knobs for the two hold windows.
