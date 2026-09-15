# Slice: Pay an online order by card (Paymob intention + webhook)

> Slice 2 of [`docs/paymob-card-epic.md`](../docs/paymob-card-epic.md), after
> [`paymob_connect.md`](paymob_connect.md) (V98). **Branch `191_feat/paymob-card-checkout` off
> `master`, migration V99.**
>
> This is the slice where card payment starts working. It adds **one** new concept — the
> `payment_intent`, an attempt to collect — and reuses every payment concept that already exists.
> The settlement path below the words "a verified CREDIT transaction exists" is
> [`accept_online_payment.md`](accept_online_payment.md)'s, unchanged.

---

## Goal

A shopper holding an order-view magic link taps **Pay by card**, completes 3DS on Paymob's hosted
page, and the order is `PAID` before they get back — settled by the webhook, not by their browser.

```
POST /api/public/orders/{token}/pay        → { checkout_url }        (anonymous, the token is the capability)
POST /api/psp/paymob/{orgId}/webhook       → 200                     (anonymous, the HMAC is the capability)
```

Done means: an anonymous `curl` against a `PENDING_PAYMENT` order's token returns a Unified Checkout
URL; replaying Paymob's signed transaction callback against it produces exactly one
`payment_transaction` (`paymob_card`, VERIFIED), one `payment` (RECEIVED), and the order `PAID` —
and replaying it four more times changes nothing.

---

## What this reuses, and why that is the whole point

| Concern | Where it already lives |
|---|---|
| Duplicate webhook delivery | `UNIQUE (provider, provider_ref)` — V22, via `txnRepo.insertIfAbsent` |
| "webhook receipt *is* the verification" | `state-machines.md:225`, specified before this existed |
| Order lock, amount comparison, MATCHED/UNDER/OVER, `markPaid` | `PaymentService.reconcileAndCreate` |
| A card settling an order that has an open InstaPay claim | `abandonSiblingsIfSettled` |
| Raw callback body for audit | `payment_transaction.raw_payload` — V22 comments it *"PSP webhooks"* |
| The `PAID` email + magic link | `notifyOrderPaid`, on both `markPaid` branches |

The new code is: an HTTP client, a signature check, a routing rule, an amount cross-check, and a
table to remember what we asked for. Everything else is wiring.

---

## Scope

### In
- **V99**: `payment_intent`.
- `PaymobClient` (service) — `createIntention(...)`, JDK `HttpClient`, **explicit connect/read
  timeouts**, no retries on the create path (a retried intention is a second intention).
- `PaymobSignature` (common/crypto) — the pinned 20-field HMAC-SHA512 verifier.
- `PaymobWebhookService` — the sibling of `PaymentTransactionService.verify` for a system actor.
- `PublicOrderHandler` gains `POST …/{token}/pay`.
- `PspWebhookServlet` at `/api/psp/*`, JWT-bypassed, rate-limited.
- `reconcileAndCreate` gains a **webhook-mode currency outcome** (ORPHAN instead of 400).
- Env: `PSP_WEBHOOK_LIMIT` (default `600`), `PAYMOB_HTTP_TIMEOUT_MS` (default `10000`),
  `PAYMENT_INTENT_TTL_MINUTES` (default `20`), `PUBLIC_API_URL` (default `http://localhost:8080` —
  this server's public origin, minted into every intention's `notification_url`).

### Out (deferred)
- **No poller, no refund recording, no admin surface** — [`paymob_card_reliability.md`](paymob_card_reliability.md).
  A dropped webhook in this slice means a stuck intent a human must chase. That is a real gap, and
  it is why slice 3 must not lag.
- **No auth-then-capture.** `is_auth && !is_capture` is logged and not settled (epic §Pending and
  auth-only). Deposits and pre-orders are their own feature.
- **No saved cards / tokenization.** `type: "TOKEN"` callbacks are acknowledged and dropped.
- **No wallets, Apple Pay, BNPL** — one `card_integration_id`, one method.
- **No portal variant.** The magic-link token is the capability, and a logged-in portal customer has
  one for their own order; `POST /api/portal/orders/{id}/pay` is a thin follow-up, not a redesign.

---

## Data model — V99

```sql
CREATE TABLE payment_intent (
    id                 UUID           PRIMARY KEY,
    org_id             UUID           NOT NULL REFERENCES org(id),
    sales_order_id     UUID           NOT NULL REFERENCES sales_order(id) ON DELETE CASCADE,

    provider           payment_provider NOT NULL,          -- 'paymob_card' today

    -- What we asked Paymob to collect, frozen at creation. The webhook is checked against THIS,
    -- not only against the order: an intent minted for an older, cheaper total must never settle a
    -- repriced order. (Epic §Money.)
    amount             NUMERIC(14,2)  NOT NULL CHECK (amount > 0),
    currency           CHAR(3)        NOT NULL DEFAULT 'EGP',

    -- Paymob's handles. `intention_id` is what an inquiry is made by (slice 3); `special_reference`
    -- is our id echoed back, and Paymob rejects a duplicate of it per merchant account — which is
    -- why it is this row's UUID and not the order number: a second attempt needs a second value.
    intention_id       TEXT,
    special_reference  TEXT           NOT NULL,

    status             TEXT           NOT NULL CHECK (status IN ('PENDING','SETTLED','FAILED','EXPIRED')),

    -- Set when a webhook attributes a transaction to this intent. Not a FK to payment_transaction:
    -- the transaction is recorded by provider_ref and may exist before we have matched it here.
    settled_txn_ref    TEXT,

    expires_at         TIMESTAMPTZ    NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),

    UNIQUE (org_id, special_reference)
);

-- The webhook's only lookup: given a special_reference, find the intent. Unique above covers it.
-- Slice 3's sweeper reads (status, expires_at); a partial index keeps it off the settled majority.
CREATE INDEX idx_payment_intent_pending ON payment_intent (expires_at)
    WHERE status = 'PENDING';
```

**Why a table at all, rather than deriving everything from the order.** Three facts have nowhere
else to live: the amount *as quoted* (the order can be repriced), which attempt a callback belongs
to (a shopper who retries three times generates three intentions), and whether an attempt is still
outstanding (slice 3's sweeper needs a work list). Deriving any of them from `sales_order` means
guessing.

**Why `ON DELETE CASCADE` on the order but not on the transaction.** An intent is an *attempt to
collect* and has no meaning without its order. A `payment_transaction` is money that actually moved
and outlives everything.

---

## `POST /api/public/orders/{token}/pay`

Anonymous. The order-view magic token is the capability — the same one that already authorises
reading the order (`GET /api/public/orders/{token}`). No new auth concept.

1. Resolve the token → order + org. Not `PENDING_PAYMENT` → **409**.
2. Org has no ACTIVE `org_paymob_config` → **409** *"this shop does not accept card payments"*.
   (The storefront already knows this from slice 1 and should not offer the button; the check is
   the server not trusting that.)
3. Compute `amount = grand_total − prepaid_amount`. `≤ 0` → 409.
4. **Reuse an unexpired PENDING intent for the same order and amount** rather than minting a second
   one. A shopper who taps twice, or reloads the return page, gets the same checkout URL. A new
   intent is minted when the amount changed or the old one expired.
5. `POST {region host}/v1/intention/` with `Authorization: Token {decrypted secret key}`:
   `amount` in **piastres**, `currency`, `payment_methods: [card_integration_id]`, `items`,
   `billing_data` (`phone_number` is Paymob-required — falls back to the order's delivery contact,
   then a placeholder), `special_reference = intent.id`,
   `notification_url = {PUBLIC_API_URL}/api/psp/paymob/{orgId}/webhook`,
   `redirection_url = {PUBLIC_BASE_URL}/{locale}/{orgSlug}/orders/{token}`,
   `extras = {org_id, sales_order_id, intent_id}`.
6. Persist the intent (PENDING, `expires_at = now + PAYMENT_INTENT_TTL_MINUTES`), and return:

```json
{ "checkout_url": "https://accept.paymob.com/unifiedcheckout/?publicKey=egy_pk_...&clientSecret=...",
  "expires_at":   "2026-09-14T08:20:00Z" }
```

The intent row is written **before** the response is sent and in the same transaction as nothing
else — if Paymob answers and we then fail to persist, the shopper could pay against an intention we
have no record of. The webhook would find no intent, and would record the money as an ORPHAN rather
than lose it; the `raw_payload` carries Paymob's `special_reference` for a human to follow. Losing
money is not on the table; the worst case is manual matching.

**The intent's TTL is shorter than the order's.** `per_org_order_ttl.md` sized the order hold in
minutes *because of this slice* (*"the spec's own future (PSP webhooks: 'minutes')"*). An intent
that outlives its order's reservation would send a shopper to a checkout page for stock that has
already been released.

---

## `POST /api/psp/paymob/{orgId}/webhook`

Anonymous, JWT-bypassed (`/api/psp/*` joins the bypass list beside `/api/public/*`), rate-limited on
a wide `rl:psp-webhook` bucket. The HMAC is the capability; `{orgId}` only selects which key to
check against (epic §Webhook routing).

```
 1. Resolve org config by {orgId}          absent / DISABLED   → 400 (log WARN)
 2. Read raw body (capped)                 unparseable         → 400
 3. Verify HMAC-SHA512 over the pinned     mismatch / absent   → 400  ← never retry a forgery
    20 fields with the org's hmac secret
 4. type != "TRANSACTION"                                      → 200, no write
 5. obj.pending == true                                        → 200, no write  ← see epic
    obj.is_auth && !obj.is_capture                             → 200, WARN
    obj.has_parent_transaction (refund/void)                   → 200, WARN (slice 3 records these)
    obj.id absent, or amount_cents <= 0                        → 200, WARN, no write (unrecordable)
 6. Resolve intent by special_reference     absent             → record as ORPHAN, 200
 7. obj.order.id (SIGNED) != intent.paymob_order_id,
    or either side absent                                      → record as ORPHAN, 200
    amount_cents != intent.amount×100
    or currency != intent.currency                             → record as ORPHAN, 200
 8. settled → one transaction, reconcile, respond              → 200
```

Step 8 is the sibling of `PaymentTransactionService.verify`, in one `rootDsl.transactionResult`:

```
insertIfAbsent(paymob_card, obj.id)           -- the dedupe; raw_payload = the whole body
  └─ already present and VERIFIED → 200, idempotent replay, no further writes
txn.verify(verifiedBy = null, now)            -- a signed webhook is the verification
paymentService.reconcileAndCreate(txDsl, orgId, txn, OrderRef(intent.salesOrderId, null))
txn.applyReconciliation(outcome, now); txnRepo.update(txn)
abandonSiblingsIfSettled(...)                 -- closes the open InstaPay claim, if any
intent → SETTLED, settled_txn_ref = obj.id
```

`verified_by` is `NULL`: the column is `UUID REFERENCES app_user(id)` and no user verified this. A
synthetic system user was considered and rejected — it would put a fake human in the audit trail of
every card payment, and `verified_by IS NULL AND provider = 'paymob_card'` already reads
unambiguously as "the gateway did it".

**A failed card (`success: false`) is not an error.** The transaction is recorded (money did not
move, but the *attempt* did, and the ledger should say so), the intent goes `FAILED`, the order stays
`PENDING_PAYMENT`, and the shopper may try again — minting a fresh intent, since this one is spent.

### The currency change to `reconcileAndCreate`

Today a currency mismatch throws `ValidationException` and rolls the whole call back, which is
correct for the admin path — a typo is correctable and nothing should persist. It is wrong for a
webhook, which cannot be asked to retype anything and whose event must never be dropped.
`accept_online_payment.md` §"Known follow-ups" called this shot in advance:

> if a webhook/auto-feed ever drives this endpoint, reclassify the mismatch as a recorded ORPHAN so
> the event isn't lost

So `reconcileAndCreate` takes the outcome as a parameter (an enum, not a boolean — `THROW` for the
admin path, `ORPHAN` for the machine path) and the admin path's behaviour is **bit-identical**. Step
7 above makes this almost unreachable in practice; it is the backstop for the case where Paymob and
the order disagree for a reason we did not predict.

---

## Concurrency

The webhook and the order-expiry sweeper race by construction: a shopper who pays at the moment
their hold lapses. `reconcileAndCreate` already locks the order `FOR UPDATE` before reading its
status, which serialises the two — whichever commits first wins and the loser sees committed state,
so an order can never be both `EXPIRED` and `PAID` (`accept_online_payment.md` §Concurrency, the
same guarantee for the same reason).

Two webhooks for the same transaction arriving concurrently serialise on the
`UNIQUE (provider, provider_ref)` insert: one inserts, the other's `insertIfAbsent` returns the
existing row and takes the idempotent-replay branch.

Two *different* successful transactions against one order — a genuine double charge, e.g. the
shopper paid twice on two devices — settle in sequence: the first flips the order `PAID`, the second
finds it no longer `PENDING_PAYMENT` and is recorded ORPHAN. The money is captured, visible, and in
the refund queue. This is the pre-existing ORPHAN path doing exactly what it was built for.

---

## Testing

Unit (`PaymobSignatureTest`) — the pinned field order against a **known-good Paymob sample**, plus:
booleans lowercase; a `null` `source_data.pan` contributing `""` (not `"None"`); a tampered
`amount_cents` failing; a mutated field *order* failing (the test that catches a sort-based
implementation).

IT (`PaymobWebhookIT`, Testcontainers) with a stub Paymob:
the happy path end-to-end; five identical deliveries → one transaction, one payment, one `PAID`;
bad HMAC → 400 and nothing written; unknown org → 400; `pending` → 200 and nothing written;
`TOKEN` type → 200 and nothing written; amount mismatch → ORPHAN, order still `PENDING_PAYMENT`;
webhook against an already-`PAID` order → ORPHAN; a card settling an order with an open InstaPay
claim → claim `ABANDONED`; `success:false` → intent `FAILED`, order untouched.

IT (`PaymobPayIT`): pay on a non-`PENDING_PAYMENT` order → 409; on an org with no config → 409;
two rapid calls → **one** intent and the same `checkout_url`; an expired intent → a new one.

---

## Acceptance criteria

- [x] Anonymous `POST …/{token}/pay` on a `PENDING_PAYMENT` order of a Paymob-connected org → `200`
      with a `checkout_url` containing the org's `public_key` and the returned `clientSecret`; one
      `payment_intent` row, `PENDING`.
- [x] A signed success callback → order `PAID`; exactly one `payment_transaction`
      (`provider = paymob_card`, `provider_ref = obj.id`, `VERIFIED`, `verified_by IS NULL`,
      `raw_payload` = the delivered body); one `payment` `RECEIVED`; intent `SETTLED`.
- [x] The same callback delivered **five** times → still one transaction, one payment, one `PAID`;
      every response `200`.
- [x] Tampered `amount_cents` (HMAC recomputed over the tampered body with the wrong key) → `400`,
      nothing written.
- [x] A callback whose `amount_cents` disagrees with the intent → transaction recorded, outcome
      `ORPHAN`, order still `PENDING_PAYMENT`, intent **not** `SETTLED`.
- [x] `pending: true` and `type: "TOKEN"` → `200`, zero rows written (asserted, not assumed — a
      recorded pending row would poison the final callback's dedupe).
- [x] Callback for `{orgId}` with no config, or `DISABLED` → `400`, nothing written.
- [x] A card payment settling an order that has an `UNVERIFIED` InstaPay claim → that claim is
      `ABANDONED` in the same transaction.
- [x] A second successful callback for a different transaction on a now-`PAID` order → `ORPHAN`,
      order unchanged, both transactions visible in the ledger.
- [x] Admin `POST /payment-transactions` with a currency mismatch still returns **400** and rolls
      back — the webhook change did not alter the admin path.
- [x] Two `…/pay` calls inside the TTL → one intent row, identical `checkout_url`.
- [x] The order-expiry sweeper and a settling webhook, run concurrently against one order, never
      produce both `EXPIRED` and `PAID`.

---

## Built (2026-09-14, branch `191_feat/paymob-card-checkout`, V99)

Every criterion above is IT-covered (`PaymobWebhookIT` 20 scenarios, `PaymobPayIT` 8, plus
`PaymobSignatureTest`, `PaymobCallbackTest`, `PspWebhookServletTest`). Where the build departs from
the text above, this is why:

- **V99 carries three columns the sketch did not.** `paymob_order_id` — the Paymob-side order the
  intention created, which is the ONE identifier of ours that appears in the callback's *signed*
  field list (`obj.order.id`); the webhook binds a callback to its intent through it, because
  `order.merchant_order_id` (Paymob's echo of `special_reference`) is unsigned. A signed mismatch is
  a recorded ORPHAN — and so is an absent handle on either side, since
  `stories/paymob_webhook_binding.md` made the binding mandatory. `client_secret` — without it the "two taps inside the TTL → identical
  `checkout_url`" criterion cannot hold (the URL is `host + public_key + client_secret`); it is the
  per-intention browser secret, not a merchant credential, and is stored in the clear on purpose.
  `idx_payment_intent_order` — the reuse lookup reads by order and Postgres does not index FKs.
- **`type != "TRANSACTION"` is answered before the HMAC check**, not after. A `TOKEN` callback signs a
  *different* field list, so checking the transaction signature on it fails it — a 400 would only
  make Paymob retry a delivery we will never use. It writes nothing, so there is nothing to protect.
- **A declined attempt (`success: false`) is recorded as an `ABANDONED` row** (closed by the system,
  never by a button) with `raw_payload`, `verified_by NULL` and — deliberately — `claimed_sales_order_id
  NULL`: a closed card attempt must not become the order's "latest claim" and hide a live InstaPay
  claim from the shopper's status page. The intent goes `FAILED`. A retry on Paymob's hosted page
  (same intention, a new `obj.id`) settles it: `FAILED → SETTLED` is a legal transition, because
  money moved and the order — not the intent's history — decides whether it settles.
- **`PaymentTransaction.verifyByGateway(proof, now)`** is the domain method behind `verified_by IS
  NULL` (the existing `verify` requires a user).
- **A callback that matches no intent is still recorded** (VERIFIED, ORPHAN, `claimed_sales_order_id
  NULL`) — money that arrived must always leave a row.
- **Missing `PAYMOB_CREDENTIAL_KEY`**: the webhook throws (→ 500, so Paymob keeps retrying until
  the operator sets it — a 400 would make it give up on a real payment); `…/pay` answers 409, the
  same shape as connect's refusal.
- **Rate buckets**: `/api/psp/*` is on its own wide `rl:psp-webhook` (`PSP_WEBHOOK_LIMIT`); `POST
  …/{token}/pay` joins the strict `rl:payment-claim` bucket (an outbound Paymob call per request).
- **The intention carries `expiration` = the intent TTL in seconds**, so Paymob's checkout page dies
  with our intent, and **one `items` line for the outstanding amount** — the order's lines do not sum
  to it (tax, shipping, discount, a prior partial), and the hosted page shows the total either way.
- **Card is rejected at the counter**: `PAYMOB_CARD` on an in-store tender is a 400 at both guards
  (`SalesOrderService.validateInStoreInputs`, `PaymentService.recordInStorePayment`) — slice 4 gets
  its own value. The admin record path (`POST /payment-transactions`) accepts it as a manual escape
  hatch; a later webhook for the same `obj.id` replays idempotently.
- **`accept_online_payment.md`'s currency follow-up is closed**: `reconcileAndCreate` takes a
  `CurrencyMismatch` enum (`THROW` for the admin path — bit-identical, pinned by
  `OrderPaidNotificationIT` and `PaymobWebhookIT` — `ORPHAN` for the webhook).
- Deploy: `deploy/docker-compose.prod.yml` passes `PAYMOB_CREDENTIAL_KEY` and sets `PUBLIC_API_URL`
  to `https://api.${DOMAIN}`; `env.prod.example` documents the key.

### Verified against the real Paymob sandbox (2026-09-14)

Run locally against `accept.paymob.com` test mode through an ngrok tunnel (`PUBLIC_API_URL`),
org `cairo-home-goods`, order SO-2026-00001 (50.00 EGP): five hosted-page attempts, five
webhooks through the tunnel, every one HMAC-verified with the merchant's real secret. Four
declines landed as closed `ABANDONED` rows with Paymob's verdict preserved in `raw_payload`
(`TIMED_OUT` ×2, `AUTHENTICATION_NOT_SUPPORTED`, `Do not honour`), each intent `FAILED`, the
order untouched; the fifth (`Approved`) reconciled `MATCHED` → order `PAID`, one `payment`
`RECEIVED`, intent `SETTLED`, hold deadline cleared, `ORDER_PAID` dispatched, public view
`payment_claim: CONFIRMED`. Two things the run taught, both fixed on the branch:

- **Paymob's `obj.created_at` is the merchant's local time with no offset** (`14:27:50` beside the
  MIGS block's `11:27Z`). Reading it as UTC put `occurred_at` three hours late.
  `PaymobCallback.createdAt(ZoneId)` now takes the region's zone (`PaymobHosts.zoneForRegion`,
  `EGYPT → Africa/Cairo`).
- **A reconnect must retire the org's live intents.** With a live `PENDING` intent minted under
  integration A, reconnecting with integration B left `…/pay` handing back A's checkout URL (its
  client secret belongs to the old credentials). `OrgPaymobService.connect` and `disconnect` now
  call `PaymentIntentRepository.expireLiveForOrg` in the same transaction; a settlement that still
  arrives for a retired intent settles (money moved), the reuse path is what closes.

Sandbox facts worth not rediscovering: the MIGS simulator's verdict is keyed on the **expiry
date** you type — Paymob's documented test card is `5123456789012346`, **`01/39`**, CVV `123`,
"Test Account" (other expiries produce `TIMED_OUT` / `Do not honour`); the Visa test card is not
3DS-enrolled there (`AUTHENTICATION_NOT_SUPPORTED`); a test account allows **one** MIGS
integration per currency; the dashboard's per-integration callback URLs are ignored when the
intention carries its own (ours always does).
