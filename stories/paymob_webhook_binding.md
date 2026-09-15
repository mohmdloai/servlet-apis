# Fix: the webhook's signed binding could fall through to amount alone

> Review of `PaymobWebhookService` after #194 landed. The intent is *found* by unsigned fields
> (`order.merchant_order_id`, `extras.intent_id`) and *bound* by the signed `order.id` — correct
> structure, but the binding was conditional on both sides being non-null, and when either was
> null the check degraded, silently, to amount + currency. Plus two nits: a verified-but-nonsensical
> body answered 400, and the org config was read outside the write transaction. Branch
> `195_fix/paymob-webhook-binding`.

## What happened

`record()` compared `intent.paymob_order_id` with the callback's `obj.order.id` only when **both**
were present:

```java
} else if (intent.getPaymobOrderId() != null
    && cb.paymobOrderId() != null
    && !intent.getPaymobOrderId().equals(cb.paymobOrderId())) {
```

`paymob_order_id` is nullable in V99 and was filled from `textOrNull(root.path("intention_order_id"))`
— so null was representable on our side, and `order.id` is in Paymob's signed list with an absent
value contributing `""`, so a body without it still verifies. In either case the callback settled the
intent on amount and currency alone. Not exploitable today: TLS means the unsigned fields cannot be
tampered with in transit. But it was the one place a defence weakened silently rather than loudly,
and identical amounts are not rare in a shop.

The two nits:

1. `process()` answered `REJECTED` → 400 for "transaction has no id" and "invalid amount". Both run
   *after* HMAC verification, so the body is authentic, just nonsensical — and a 400 asks Paymob to
   retry something that can never succeed. The class doc defines `REJECTED` as *unverifiable*, which
   these are not.
2. `handle()` read the org config on `rootDsl` and `process()` opened the write transaction later, so
   the key that verified the body and the rows the body wrote were not one unit of work. Harmless
   under READ COMMITTED; untidy.

## Fix

**The binding is mandatory, at both ends.**

- `JdkPaymobClient.parse` refuses an intention answer without `intention_order_id` the way it already
  refuses one without `client_secret`: `UpstreamFailureException` → 502 to the shopper, no
  `payment_intent` row, a second tap mints afresh. A checkout we could not bind is never opened.
- `PaymobWebhookService.record` orphans on an absent handle on **either** side — an intent row with
  no `paymob_order_id` (one minted before this rule) or a callback with no signed `order.id` — with
  its own `orphanReason`, before the amount and currency checks. Money that arrived still leaves a
  VERIFIED row; a human matches it. The intent is not settled.

**A verified body nothing can be recorded from is `IGNORED` (200), not `REJECTED` (400).** `obj.id`
and `amount_cents` are both signed, so an absent id or a non-positive amount is Paymob's own
nonsense; a retry delivers the same body and reaches the same decision. Logged at WARN. The poller's
`IGNORED` branch (`leftPending++`) already fits: the intent waits for its own deadline.
`REJECTED` keeps its one meaning — unverifiable, nothing written, never retry a forgery.

**One transaction per delivery.** `handle()` now opens `rootDsl.transactionResult` first and does the
config read, the parse, the HMAC and the writes inside it; `settleFromInquiry` opens its own (the
poller's loop runs outside any). Nothing in the handler calls out, so the connection is held for a
parse, an HMAC and the writes only. A rejected or ignored delivery commits nothing.

Not changed, deliberately: the declined path (8a) still marks the intent `FAILED` from the unsigned
resolution alone. A wrong `FAILED` costs nothing — `FAILED → SETTLED` is a legal transition and the
order decides whether money settles — and binding it would only add a branch to a path that writes
no money.

No migration. `paymob_order_id` stays nullable for the rows that predate the rule; the webhook is
what refuses to settle them.

## Tests

Four new scenarios, each proven to fail against the previous code (production classes reverted, tests
kept: the two absent-handle callbacks came back `SETTLED`, the unrecordable body `REJECTED`, and the
client accepted the answer without an order id):

- `PaymobWebhookIT.callbackWithoutASignedPaymobOrder_recordedOrphan_neverBoundByAmountAlone` —
  `order.id` removed, signature still valid → `ORPHAN`, order `PENDING_PAYMENT`, intent `PENDING`.
- `PaymobWebhookIT.intentWithoutAPaymobOrderId_recordedOrphan_neverBoundByAmountAlone` — the row's
  `paymob_order_id` nulled, the callback matches every unsigned field and the exact amount → `ORPHAN`,
  no payment.
- `PaymobWebhookIT.verifiedBodyNothingCanBeRecordedFrom_is200_nothingWritten` — no `obj.id`, then
  `amount_cents: 0` → `IGNORED`, `rejected() == false`, zero rows.
- `PaymobPayIT.jdkClient_postsTheIntention_…` gains a third phase: the stub answers 201 without
  `intention_order_id` → `UpstreamFailureException` 502, no intent row.

Gates (`DOCKER_HOST` + `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`, colima):

- `mvn -o verify -pl api -Dit.test=PaymobWebhookIT,PaymobPayIT,PaymobInquiryIT`: 27 + 12 + 14 green.
- `PspWebhookServletTest` 6 and `PaymobCallbackTest` 7 green; spotless applied.
