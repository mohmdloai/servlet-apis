# Epic: Card payments via Paymob — per-org merchant accounts, beside InstaPay

> Canonical design for the PSP integration this system has named since day one.
> `sys-analysis/outbound/payment.md:56` listed `paymob` as a future provider; `transaction.md:43`
> reserved the enum value; `state-machines.md:225` already specified *"VERIFIED happens
> automatically on webhook receipt"*. This epic is that future, and it deliberately adds **no new
> payment concepts** — a card payment is a `payment_transaction` + a `payment` like every other.
>
> Slices: [`paymob_connect.md`](../stories/paymob_connect.md) →
> [`paymob_card_checkout.md`](../stories/paymob_card_checkout.md) →
> [`paymob_card_reliability.md`](../stories/paymob_card_reliability.md).
> Branches `190_feat` → `192_feat` off `master`; this doc is `189_docs/paymob-card`. The thin
> follow-up [`paymob_portal_pay.md`](../stories/paymob_portal_pay.md) (`194_feat`, story and code
> on one branch) adds the signed-in door.

---

## The two owner decisions

**1. Each org connects its own Paymob merchant account.** The platform never touches merchant
money. This is the same shape as `org.instapay_handle` (V52): the shopper's money goes to the
merchant's account, not to ours, and the merchant onboards with Paymob themselves. The rejected
alternative — platform-as-merchant, collecting and paying out — is a marketplace/PayFac model. It
would need a Paymob marketplace agreement, KYC on every merchant, a payout ledger, and it would make
this platform legally liable for merchant funds. No one asked for that, and the InstaPay precedent
already established which side of the line we are on.

The cost of the decision lands in three places, and they are the reason slice 1 exists at all:
per-org credentials must be **stored** (slice 1), the webhook must be **routed to the right org
before it can be verified** (§Webhook routing), and an org with no Paymob connection must degrade to
exactly today's behaviour rather than erroring.

**2. Card sits beside InstaPay, not instead of it.** The storefront offers both. A merchant with no
Paymob connection shows only InstaPay + cash — today's checkout, untouched. This is not a
compatibility shim: manual InstaPay is the cheaper rail (no PSP fee) and many Egyptian shoppers
prefer it, so it stays a first-class option indefinitely.

The interesting consequence is that **one order can be chased down both rails at once** — a shopper
files an InstaPay claim, gets impatient, and pays by card. That race is already handled by machinery
built for a different reason: `abandonSiblingsIfSettled` closes every other open claim on an order
in the same transaction that settles it (`state-machines.md` §"Enter ABANDONED"). The card webhook
settling an order ABANDONs the pending InstaPay claim; an InstaPay claim verified first leaves the
card webhook to land on a PAID order, which reconciles as ORPHAN and goes to the refund queue. Both
directions were designed before card existed. Neither needs new code.

---

## Why this is not a microservice, and not Spring

Recorded here because it was asked, and because the answer is structural rather than stylistic.

- **Money needs one transaction boundary.** `PaymentTransactionService.verify` locks the order
  `FOR UPDATE`, inserts the transaction, verifies it, calls `PaymentService.reconcileAndCreate`
  (which creates the `payment`, bumps `prepaid_amount` and flips the order to PAID), stamps the
  reconciliation outcome, and abandons sibling claims — **one `rootDsl.transactionResult`**.
  `split_tender.md` rule 6 states the invariant outright: *"One txn; any failure rolls back every
  tender."* A separate payments service replaces a Postgres transaction with a saga, an outbox and
  compensating writes, for no gain.
- **The idempotency key is already a DB constraint.** `UNIQUE (provider, provider_ref)` on
  `payment_transaction` (V22) is the hardest single problem in PSP integration — duplicate webhook
  delivery — and it is solved. A remote service would reimplement it *and still need the local one*.
- **Tenancy is compiler-enforced.** Every service method takes `orgId` first (CLAUDE.md §Key
  Patterns). A second service re-implements org resolution, `requireOrgAccess`, and per-org config,
  or calls back on every request.
- **Nothing is missing from the stack.** HTTP (Jakarta Servlet + embedded Tomcat), DB (jOOQ),
  scheduling (JobRunr — already runs the order-expiry and notification sweepers), secret storage
  (`SecretBox`), integration testing (Testcontainers). Spring Boot would add a second DI model, a
  second config system and a second lifecycle to a codebase whose composition root is one readable
  `AppConfig`. Kotlin would add a second toolchain to a five-module Java reactor with jOOQ codegen.

The whole integration is **one migration, one HTTP client, one handler, one JobRunr job**.

---

## The sequence

Paymob's current API is the **Intention API + Unified Checkout**, not the legacy
`auth token → order register → payment key` triple. One call instead of three, no auth-token
lifecycle, and — decisively for this system — `notification_url` and `redirection_url` are set
**per intention** rather than once in the merchant dashboard. That is what lets dev, staging and
prod coexist against the same merchant account, and it is what makes §Webhook routing possible.

```
 shopper                storefront            this API              Paymob
    │                       │                    │                    │
    │── checkout ──────────▶│── POST /checkout ─▶│                    │
    │                       │                    │ order PENDING_PAYMENT, stock reserved,
    │                       │                    │ expires_at (per-org TTL)
    │◀── order + methods ───│◀───────────────────│                    │
    │                       │                    │                    │
    │── "pay by card" ─────▶│── POST …/pay ─────▶│                    │
    │                       │                    │── POST /v1/intention/ ─────────▶│
    │                       │                    │   Authorization: Token <org secret>
    │                       │                    │   amount(piastres), EGP,
    │                       │                    │   payment_methods:[<org card integration id>],
    │                       │                    │   special_reference: <payment_intent.id>,
    │                       │                    │   notification_url: …/api/psp/paymob/{orgId}/webhook
    │                       │                    │   redirection_url: <storefront return>
    │                       │                    │◀── client_secret, intention id ──│
    │                       │                    │ INSERT payment_intent (PENDING)  │
    │◀── checkout_url ──────│◀───────────────────│                    │
    │                                                                 │
    │── pay on accept.paymob.com/unifiedcheckout/ (3DS here) ────────▶│
    │                                                                 │
    │                       │                    │◀═ WEBHOOK (authoritative) ══════│
    │                       │                    │   POST …/webhook?hmac=<sha512hex>
    │                       │                    │   {"type":"TRANSACTION","obj":{…}}
    │                       │                    │ verify HMAC → record → VERIFIED →
    │                       │                    │ reconcile → order PAID
    │◀── redirect (NOT authoritative) ───────────│◀── browser returns ─────────────│
    │                       │ polls order status │                    │
```

**The browser redirect never changes state.** It carries its own `hmac`, but it is a client-side
navigation the shopper can edit, drop, or never make (they close the tab; the card still charged).
The return page polls the order. The webhook is the only writer. This is not a theoretical concern —
it is the exact defect that makes a naive integration pay out on a typed URL.

### Step 4 in detail: what the webhook does with what we already have

| Webhook step | Mechanism |
|---|---|
| Verify HMAC-SHA512 over the **pinned** 20-field concatenation | new — see §HMAC |
| `type != "TRANSACTION"` → 200, no write | card-token callbacks sign a *different* field list |
| `obj.pending == true` → 200, no write | see §Pending and auth-only |
| Classify | `success && !error_occured && !is_voided && !is_refunded` → settled |
| Dedupe | `insertIfAbsent` on `UNIQUE (provider, provider_ref)` — **exists** (V22) |
| `provider` / `provider_ref` | `paymob_card` / `obj.id` (`transaction.md:101`: *"For PSPs, the PSP's txn id"*) |
| Verification | straight to VERIFIED, `verified_by = NULL` — **`state-machines.md:225` already specifies this** |
| Reconcile | `PaymentService.reconcileAndCreate` — **unchanged** |
| Sibling InstaPay claims | `abandonSiblingsIfSettled` — **unchanged** |
| Audit | `payment_transaction.raw_payload` — **exists**, V22 comments it *"PSP webhooks"* |

The only genuinely new code is HMAC verification, the intention client, org routing, and the
amount cross-check. Everything downstream of "a verified CREDIT transaction exists" is already
written, already tested, and already in production for InstaPay.

---

## HMAC

Paymob signs the transaction callback with HMAC-SHA512 over the concatenation — no separators — of
exactly these twenty fields, **in this order**:

```
amount_cents, created_at, currency, error_occured, has_parent_transaction, id, integration_id,
is_3d_secure, is_auth, is_capture, is_refunded, is_standalone_payment, is_voided, order.id,
owner, pending, source_data.pan, source_data.sub_type, source_data.type, success
```

hex-encoded lowercase, delivered as the `?hmac=` **query parameter**. Booleans stringify lowercase
(`true`/`false`); a JSON `null` or absent field contributes the **empty string**, not `"None"` or
`"null"`.

**Pin the list as a constant; never derive it by sorting the payload's keys.** The sorted order of
these particular key names happens to equal the canonical order, which makes a sort-based
implementation pass every test and then break silently the day Paymob adds a field or renames one.
A signature check that is accidentally correct is not a signature check.

Compare in constant time. A failed comparison is a **400** — a forgery must not be retried.

---

## Webhook routing — the per-org consequence

With per-org merchant accounts, every org has its own HMAC secret, so the request must be attributed
to an org *before* it can be verified. The payload cannot be trusted to do that: nothing in it is
authenticated until the HMAC checks out.

**The URL carries the org.** Because `notification_url` is set per intention, we mint
`…/api/psp/paymob/{orgId}/webhook` when we create the intention. The handler resolves the org's
config from the path, verifies the HMAC with **that org's** secret, and only then reads the body.

The `{orgId}` in the path is attacker-supplied, and that is fine: pointing org A's URL at org B's
payload fails the HMAC, and replaying org A's genuine payload at org A's URL is absorbed by
`UNIQUE (provider, provider_ref)`. The path segment selects *which key to check against*; it grants
nothing on its own.

Rejected alternatives: routing by `obj.integration_id` (it is in the signed field list, so it is
sound — but it needs a lookup table keyed on a number the merchant controls, and it fails outright
if two orgs ever share an integration id); and one global callback URL with a platform-level secret
(impossible — the secret belongs to the merchant's account).

A webhook for an org with no config, or a disabled one, is a **400**: unverifiable input is not a
200. It is logged at WARN with the org id and the transaction id, and nothing is written.

---

## Money

Paymob amounts are integer **piastres**; ours are `NUMERIC(14,2)` EGP. The conversion is exact
(`grand_total.movePointRight(2)`, rejected unless integral) and it happens in one place.

**The webhook's `amount_cents` is checked against the `payment_intent` we created, not only against
the order.** Checking the order alone is insufficient: an intent minted for an older, cheaper total
would settle a repriced order. A mismatch is recorded as **ORPHAN** — never silently accepted, never
dropped. Money that arrived must always leave a row.

**The signed binding is mandatory** (`stories/paymob_webhook_binding.md`). The intent is *found* by
unsigned fields (`order.merchant_order_id`, `extras.intent_id`) and *bound* by the signed `order.id`
against the `paymob_order_id` remembered at intention time. Either side absent is an ORPHAN, never
a fall-through to amount + currency — identical amounts are not rare in a shop — and an intention
Paymob answers without an `intention_order_id` is refused at mint time (502, no row).

`accept_online_payment.md` §"Known follow-ups" anticipated the other half of this exactly:

> **Currency mismatch returns 400 (rolls back the claim).** Intentional for admin-entered data (a
> typo is correctable); if a webhook/auto-feed ever drives this endpoint, reclassify the mismatch as
> a recorded ORPHAN so the event isn't lost.

That day is here. Slice 2 gives `reconcileAndCreate` a webhook-mode currency outcome (ORPHAN) while
leaving the admin path's 400 exactly as it is — the two callers want opposite things for the same
reason: an admin can fix a typo, a webhook cannot be asked to try again.

---

## Pending and auth-only

**Pending.** A 3DS challenge in flight produces a callback with `pending: true` and the *same*
`obj.id` the final callback will carry. Recording it would burn the `provider_ref`: the final
callback's `insertIfAbsent` would find the pending row and return it unchanged, and the order would
never settle. So a pending callback is **200 with no write** — the final one is what counts.

**Auth-only.** `is_auth && !is_capture` means the merchant's integration authorises without
capturing. v1 requires a **capture-on-sale** integration; an auth-only callback is logged at WARN
and not settled (the money has not moved). Separate authorise/capture is a real feature — deposits,
pre-orders — and it belongs in its own slice with its own state, not smuggled in as a silent
`if`.

**Refunds and voids.** A callback with `has_parent_transaction: true` and `is_refunded`/`is_voided`
is money leaving, i.e. `direction = DEBIT`. Slice 3 records these against the existing `refund`
model (V26). Slice 2 acknowledges them with 200 and a WARN rather than mis-recording them as
credits.

---

## Retry, response codes, and the poller

Paymob retries on non-2xx. The response code is therefore a control signal, not a status report:

| Outcome | Code | Why |
|---|---|---|
| Settled, or idempotent replay of a settled event | `200` | done; stop retrying |
| Verified but deliberately ignored (pending, `TOKEN`, auth-only, refund) | `200` | a retry would not change the decision |
| Verified but unrecordable (no `obj.id`, non-positive `amount_cents`) | `200` | authentic and nonsensical; a retry delivers the same body |
| Bad/absent HMAC, unknown or disabled org, malformed body | `400` | unverifiable; retrying a forgery helps no one |
| Transient failure (DB down, lock timeout) | `500` | **let it retry** — this is the one case retries exist for |

A 500 that the retries never resolve is still a lost payment, which is why slice 3 exists: a JobRunr
job sweeps `payment_intent` rows still PENDING past a grace window and settles them from Paymob's
transaction-inquiry API. Webhooks are the fast path; the poller is the one that makes the system
*correct* across a deploy, an outage, or a dropped delivery. `per_org_order_ttl.md` already sized the
order hold for this — *"Minutes, not hours — the spec's own future (PSP webhooks: 'minutes')"*.

---

## Slices

| Slice | Branch | Migration | Contents |
|---|---|---|---|
| **1 — connect** | `190_feat/paymob-connect` | **V98** | `payment_provider` += `paymob_card`; `org_paymob_config` (credentials, `SecretBox`-encrypted); connect / status / disconnect endpoints; storefront advertises `card` as an available method. No payment can be taken yet. |
| **2 — checkout + webhook** | `191_feat/paymob-card-checkout` | **V99** | `payment_intent`; `POST /api/public/orders/{token}/pay`; `POST /api/psp/paymob/{orgId}/webhook`; webhook-mode currency outcome on `reconcileAndCreate`. **This is where card payment starts working.** |
| **3 — reliability** | `192_feat/paymob-card-reliability` | none | Inquiry poller (JobRunr), refund/void DEBIT recording, admin visibility for stuck intents and card ORPHANs. |
| **3b — portal pay** | `194_feat/paymob-portal-pay` | none | The signed-in door: `POST /api/portal/orders/{n}/pay` (session = capability, ownership first) + `ReturnTarget` on `PaymentIntentService.pay` so Paymob returns a logged-in customer to their account order page, not the magic-link tracker. `stories/paymob_portal_pay.md`; the frontend pair (`frontst` story 161) moves the card choice onto the checkout form. |
| **4 — in-store card** | *unwritten* | enum value | Card at the counter needs a **physical terminal**; it is `instapay_in_store`'s twin — the cashier runs the terminal and keys the approval code as `provider_ref`, VERIFIED on the spot. Drops into `split_tender.md` with no new machinery. Deliberately not slice 1: it shares a name with online card and nothing else. |

Slices 1 and 2 are the minimum that takes a card payment. Slice 3 is the minimum that makes it
trustworthy, and should not lag far behind — a system that only settles when the webhook arrives is
a system that quietly loses orders on its next deploy.
