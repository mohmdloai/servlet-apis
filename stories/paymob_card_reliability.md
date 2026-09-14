# Slice: Card payments that survive a dropped webhook

> Slice 3 of [`docs/paymob-card-epic.md`](../docs/paymob-card-epic.md), after
> [`paymob_card_checkout.md`](paymob_card_checkout.md) (V99). **Branch
> `192_feat/paymob-card-reliability` off `master`, no migration** — V99's `payment_intent` already
> carries `status` and `expires_at`, and `idx_payment_intent_pending` was created for this sweeper.

---

## Goal

Slice 2 settles a card payment when the webhook arrives. This slice settles it when the webhook
**doesn't** — and makes the residue visible to a human when nothing can settle it automatically.

Done means: a shopper pays successfully, the webhook is never delivered (the API was mid-deploy),
and within one sweep interval the order is `PAID` anyway, from Paymob's own record of the
transaction.

---

## Why this is not optional

A webhook is a single delivery attempt across the public internet into a process that restarts on
every deploy. Paymob retries, but retries are finite and a merchant's worst hour — a promotion, a
release, a VPS reboot — is exactly when both the delivery and its retries land on a closed port.

The failure mode is the bad one: **the shopper's card is charged and their order says unpaid.** They
have a bank SMS and we have nothing. Every support conversation that follows is a manual hunt
through the Paymob dashboard. The poller converts that from an incident into a log line.

This is the same argument `NotificationDeliverySweeperJob` already makes for email, on the same
JobRunr infrastructure, with the same `ORDER_SWEEPER_BACKGROUND_ENABLED` gate. Nothing here is new
machinery; it is the existing sweeper pattern pointed at a new work list.

---

## Scope

### In
- `PaymobInquiryJob` — recurring JobRunr job; reconciles `PENDING` intents from Paymob's
  transaction-inquiry API.
- `PaymobClient.inquire(intentionId | specialReference)`.
- Refund / void callbacks recorded as `direction = DEBIT` transactions (slice 2 acknowledged and
  dropped them).
- Admin read surface: card intents needing attention, on the existing transactions list.
- Env: `PAYMOB_INQUIRY_INTERVAL` (default `0 */2 * * * *`), `PAYMOB_INQUIRY_GRACE_MINUTES`
  (default `3`), `PAYMOB_INQUIRY_BATCH_LIMIT` (default `100`).

### Out (deferred)
- **No refund *initiation*.** This slice records refunds Paymob tells us about; it does not call
  Paymob's refund API. `refund.md:296` scopes that deliberately — *"Auto-execution of refunds via
  PSP API — future, when PSP integration lands. v1 admin executes manually and records the resulting
  Transaction."* Executing a refund from our side is a write to someone else's money and deserves
  its own slice, its own approval gate, and its own idempotency key.
- **No settlement/payout reconciliation.** Paymob's fees and payout batches are a different ledger.
  `transaction.md:229` already parked it: *"Fees / FX — out of scope for v1."*
- **No chargeback/dispute ingestion.** `payment.status` has `DISPUTED` and there is a
  `PaymentDisputeService`, but wiring a PSP dispute feed into it is its own slice.

---

## The sweeper

```
every PAYMOB_INQUIRY_INTERVAL, gated by ORDER_SWEEPER_BACKGROUND_ENABLED:

  SELECT … FROM payment_intent
   WHERE status = 'PENDING'
     AND created_at < now() − PAYMOB_INQUIRY_GRACE_MINUTES
   ORDER BY created_at
   LIMIT PAYMOB_INQUIRY_BATCH_LIMIT          -- idx_payment_intent_pending

  for each intent:
    org config absent/DISABLED → mark EXPIRED, log WARN, next
    Paymob inquiry             → no transaction yet, intent not expired → leave PENDING
                               → no transaction,     intent expired     → EXPIRED
                               → a settled transaction                  → SETTLE IT
                               → a failed transaction                   → FAILED
```

**The grace window matters.** Sweeping an intent seconds after creation races the shopper who is
still typing their card number, and — worse — races the webhook itself. Three minutes is long enough
that a normal payment has resolved one way or the other, and short enough that a shopper who
refreshes their order page sees `PAID` before they give up and open a support chat.

**Settling from an inquiry runs the identical path as the webhook.** It calls the same
`PaymobWebhookService` entry point with the inquiry's transaction payload, so it takes the same
`insertIfAbsent` on `(provider, provider_ref)`, the same `reconcileAndCreate`, the same
`abandonSiblingsIfSettled`. That is the crux of the design: **a webhook and a poll that describe the
same transaction must be indistinguishable downstream**, or the two paths drift and the rare one is
the buggy one. A webhook that arrives while the sweeper is mid-settle loses the insert race and
takes the idempotent-replay branch — the outcome does not depend on who got there first.

The inquiry response is a transaction object of the same shape as the callback body, so it is
**verified the same way** where a signature is present and otherwise trusted as an authenticated
API response over TLS (we made the call, with the merchant's secret key — there is no third party
to impersonate anyone).

---

## Refunds and voids as DEBIT

A callback with `has_parent_transaction: true` and `is_refunded` or `is_voided` is money going back
out. Slice 2 acknowledged these with a WARN rather than mis-record them as credits; this slice
records them properly:

- `direction = DEBIT`, `provider_ref = obj.id` (the refund's own transaction id — distinct from the
  parent's, so the dedupe key is naturally unique).
- Linked to the original payment via the parent transaction's id in `raw_payload`.
- The existing `refund` model (V26) and `RefundService` own what happens next.

A void arriving on an order this system has already flipped to `PAID` is the sharpest case: the
money is gone and the order says otherwise. It is recorded, the payment is flagged, and it lands in
the admin queue — this slice does **not** silently un-pay an order. Reversing a `PAID` order touches
fulfilment, invoices and stock, and that is a decision a human makes.

---

## Admin visibility

Three things a merchant must be able to see without a database client, on the existing
`GET /api/orgs/{orgId}/payment-transactions` surface and its filters:

1. **Card ORPHANs** — money captured that matched no open order (a double charge, a repriced order,
   a payment after expiry). Already listable; this slice ensures `paymob_card` rows carry enough in
   `raw_payload` to identify the shopper.
2. **Intents stuck `PENDING`** past their expiry with no transaction — usually an abandoned
   checkout, occasionally a misconfigured integration. A count, not a queue.
3. **Failed settlements** — an inquiry that found a settled transaction the reconcile refused
   (currency, amount). These are the ones that need a person today.

---

## Acceptance criteria

- [ ] A successful Paymob transaction whose webhook is **never delivered** → the sweeper settles the
      order `PAID` within one interval, producing byte-identical rows to the webhook path
      (`provider_ref`, `verified_by IS NULL`, `payment` amount, order state).
- [ ] The webhook arriving **after** the sweeper already settled → `200`, idempotent replay, no
      second transaction and no second payment.
- [ ] The sweeper running while a webhook settles the same intent → exactly one transaction, one
      payment (the insert race is decided by the unique constraint, not by ordering).
- [ ] An intent younger than `PAYMOB_INQUIRY_GRACE_MINUTES` is not inquired about.
- [ ] An intent past `expires_at` with no Paymob transaction → `EXPIRED`, order untouched, no
      transaction written.
- [ ] An intent whose org disconnected Paymob → `EXPIRED` + WARN, never an exception that stalls the
      batch (one bad intent must not stop the other ninety-nine).
- [ ] A refund callback → one `DEBIT` transaction linked to the original; the order's status is
      **not** changed.
- [ ] `ORDER_SWEEPER_BACKGROUND_ENABLED=false` → the job does not run; tests drive it directly.
- [ ] Paymob unreachable (timeout) during a sweep → the batch logs and ends; intents stay `PENDING`
      and are retried next interval; no intent is lost or wrongly expired.
