# Slice: Verify the claim, don't re-record it — phase 2 (not found, and the shopper's status)

> Branch **`168_feat/payment-claim-not-found`** off `167_feat/payment-claim-verify` (merges after it),
> migration **V91**. Second of three phases of `ststore/design/payment-claims.html`; phase 1 is
> [`payment_claim_verify.md`](./payment_claim_verify.md). Frontend twin: `frontst/stories/133_st_claim_not_found_status.md`.

---

## What phase 1 left open

A manager who searched the bank app and found nothing under the shopper's reference had no verb —
the claim sat UNVERIFIED with nobody told. The shopper, meanwhile, saw the blank "Already paid?"
form again on every reload: `PublicOrderResponse` carried no claim, so the "recorded, we'll
confirm shortly" beat was React state and evaporated. And a shopper who fixed a typo re-filed the
same reference into an idempotent no-op that changed nothing.

## What this phase ships

### Migration V91

```sql
ALTER TABLE payment_transaction ADD COLUMN not_found_note TEXT;
```

The brief listed no migration for this phase. V90's `not_found_reason` is the reason **code**
(`NO_TRANSFER` / `DIFFERENT_ACCOUNT` / `OTHER`); the manager's free note for the shopper ("nothing
arrived under this reference between 11 and 13 Jul") is what the shopper actually reads on the
order page and in the email, and a code alone is not a sentence. A column is cleaner than encoding
the note into the code or into `raw_payload`; both are cleared when the claim is re-opened or verified.

### Domain

- `PaymentTransaction.markNotFound(reason, note, now)` — UNVERIFIED → NOT_FOUND (a NOT_FOUND claim
  is already answered; the service replays instead). `reopen(now)` — NOT_FOUND → UNVERIFIED, the
  answer cleared. `verify()` clears the note as it clears the reason.
- `NotificationType.PAYMENT_NOT_FOUND` (no migration — `notification.type` is TEXT). Template
  (EN/AR): "We couldn't find your transfer for {order}" · "The store checked reference {ref} and
  found no transfer with it [/ it looks like the transfer went to a different account]. Store's
  note: "…" Check the reference in your InstaPay app and send it again — your order is held until
  {held_until}." CTA "Check your reference". No WhatsApp template (no approved Meta template —
  `WhatsAppTemplates` maps it to null like the other non-utility types).
- `PaymentTransactionRepository.findLatestClaimByOrder(orderId)`.

### Endpoints

| Route | Role | Behaviour |
|---|---|---|
| `POST /payment-transactions/{id}/not-found` body `{reason, note?}` | MANAGER | `reason` ∈ `NO_TRANSFER`, `DIFFERENT_ACCOUNT`, `OTHER` (else 400). Locks the claimed order then the row. UNVERIFIED → NOT_FOUND with the reason + note; the order's hold re-armed to `max(expires_at, now + 6h)` (the reservation mirror moves with it); `PAYMENT_NOT_FOUND` to the claiming customer (feed + email, fresh order-view magic link) inside the same txn. **200** with the claim (+ `claimed_order`); an already-NOT_FOUND claim replays **200** with the answer on file (no second re-arm, no second notification); VERIFIED / ABANDONED → **409**. |
| `POST /api/public/orders/{token}/payment-claim` + portal twin | anon / customer | Re-filing the reference of a **NOT_FOUND** claim on the same order re-opens it: NOT_FOUND → UNVERIFIED, reason + note cleared, **200 `{recorded:false, reopened:true, …}`** — the same row, no second hold (the not-found already re-armed one). A pending claim re-filed is the plain replay (`reopened:false`); a new reference is a new row as before. |
| `GET /api/public/orders/{token}` + `GET /api/portal/orders/{orderNumber}` | anon / customer | Adds **`payment_claim`** — the latest claim on the order: `{status: PENDING\|CONFIRMED\|NOT_FOUND, reference, amount, currency, filed_at, reason?, note?}`. Customer-safe (no ids, no internal statuses); absent when no claim was filed, and when the latest one is ABANDONED (the order itself says PAID / EXPIRED / CANCELLED). The portal **list** rows stay lean. |
| `GET /payment-transactions[/{id}]` | VIEWER | Rows/detail add `not_found_note` beside `not_found_reason`. |

Sibling abandonment (listed for this phase in the brief) shipped in phase 1 with the sweeper's
primitive — see that story's deviations.

## Tests — `PaymentClaimNotFoundIT`

not-found → NOT_FOUND + reason + trimmed note persisted + hold re-armed to now + 6h + the
notification row (recipient = the claiming customer, body quotes the note and the reference,
title names the order) · a longer hold is never shortened · replay on an answered claim (no second
notification), bad reason 400, VERIFIED / ABANDONED 409 · re-filing re-opens without stacking the
hold (and a pending claim re-filed is a plain replay) · the customer-facing read: absent → PENDING
→ NOT_FOUND (reason + note) → CONFIRMED, and an ABANDONED latest claim is absent.
`PaymentTransactionHandlerAuthTest`: STAFF 403 on `/not-found`, MANAGER 200 with the note on the wire.

## Out of scope (phase 3)

`supersedes_claim_id` on the record path ("Record a different transfer" from the claim card
closing the old claim in the same submit); org knobs for the two hold windows.
