# Kill the silence between PAID and FULFILLED — order-lifecycle notifications

> **Slice A of two.** This one adds the missing *events* on the channels that already exist
> (`in_app` + `email`). **Slice B** — a WhatsApp channel, per-merchant WABA — is specified at the
> bottom under §Slice B, and is deliberately not started here.

## The problem, stated precisely

A shopper who pays gets one email and then nothing until the box arrives. That is not a figure of
speech — it is exactly what the producer table said before this slice:

| Moment | Customer hears |
|---|---|
| Order placed | `ORDER_PLACED` ✅ |
| Payment verified (MATCHED / OVERPAID) | `ORDER_PAID` ✅ |
| **Payment arrived but fell short (UNDERPAID)** | **nothing** |
| **Shipment leaves the warehouse (`ship`)** | **nothing** |
| **Each individual delivery** | **nothing** |
| **Order cancelled** | **nothing** |
| Order fully delivered | `REVIEW_REQUESTED` ✅ |

Between `ORDER_PAID` and `REVIEW_REQUESTED` there was not one message. The sharpest of those gaps
is not the shipping one — it is **UNDERPAID**: the shopper moved real money, the order stays
`PENDING_PAYMENT`, and from their side that is indistinguishable from a failed transfer. They have
no way to learn they are short except to contact the merchant, which is the support ticket this
slice exists to prevent.

## What ships

Three new `NotificationType` values, each raised inside the business transaction that causes it, to
the customer, on both existing legs (durable `in_app` feed row + `email`), preference-suppressible
like every other type.

| Type | Producer | Edge | Payload |
|---|---|---|---|
| `ORDER_SHIPPED` | `FulfillmentService.ship` | `PENDING → SHIPPED` | `order_number`, `carrier?`, `tracking_number?` |
| `ORDER_CANCELLED` | `OrderCancellationService.cancel` | after the refund obligations are created | `order_number`, `refund_total?`, `currency?` |
| `PAYMENT_NEEDS_ATTENTION` | `PaymentService.reconcileAndCreate` | the UNDERPAID branch | `order_number`, `amount`, `outstanding`, `currency` |

**No migration.** `notification.type` is open TEXT precisely so a new event costs nothing at the
schema level (V44's design note). The only compiler-forced change is a new `case` in
`NotificationTemplates.render` — the switch is exhaustive, so a type without a template cannot be
merged.

Each producer mints a **fresh order-view magic link** in its own transaction, exactly as placement
and `ORDER_PAID` do. Reusing an earlier token is not an option: they are stored hashed, so the raw
value cannot be reconstructed.

## The four decisions, and why

### 1. `ORDER_SHIPPED`, not `OUT_FOR_DELIVERY` — for now

The original ask named both. **There is exactly one transition here.** `ship()` moves
`PENDING → SHIPPED` and nothing else, and `stories/rider_self_delivery.md` already (a) reserves the
name `OUT_FOR_DELIVERY` for the rider-pickup edge and (b) explicitly *declined* to add a distinct
status ("reuse `SHIPPED` — the frontend relabels by carrier"). Emitting both names from one edge
would be two words for one fact, and it would collide with the rider epic the day it lands.

`OUT_FOR_DELIVERY` therefore stays unbuilt and unclaimed. When the rider epic ships, its
rider-pickup path is where it belongs, and the two will be genuinely different events.

### 2. `ORDER_SHIPPED` fires **per fulfillment**, not per order

A split order really does put a second box on the road, and the shopper is owed that news. This is
the deliberate opposite of `REVIEW_REQUESTED`, which fires once per *order* on the roll-up edge —
because "rate what you got" is answerable only once everything has arrived, while "a box is coming"
is true once per box. Pinned by `splitOrder_firesOncePerShipment_notOncePerOrder`.

### 3. `PAYMENT_NEEDS_ATTENTION` fires on UNDERPAID **only**

Considered and rejected for this slice:

- **Order approaching TTL expiry** — the highest-value nudge commercially, but it needs a new
  recurring sweeper *and* a `nudged_at` marker so a 10-second tick does not re-send forever. That
  is its own slice with its own failure modes; bolting it on here would put a new background job in
  a notifications change.
- **Order expired** — a real event, but it belongs with the expiry sweeper above, and it is
  arguably `ORDER_CANCELLED`'s sibling rather than this type.
- **Payment DISPUTED** — a merchant-initiated flag. Nothing about it is news the shopper can act
  on, and telling them a dispute was opened against their payment invites a conversation the
  merchant did not ask to start.
- **OVERPAID** — that order is `PAID`; `ORDER_PAID` already covers it, and the excess-refund
  conversation is the admin's.

Firing **per recorded partial** is deliberate: a second top-up that still falls short raises it
again with the new, smaller remainder, which is the only number the shopper needs.

### 4. The money copy never overstates

A cancel creates refunds **PENDING** — it records an obligation and moves nothing (`refund.md`'s
two-step lifecycle). So the template says a refund *is being processed* and never that it has been
sent. A cancel with no prepayment (the common pre-PAID case) gets **no money sentence at all**
rather than "a refund of 0.00" — a shopper who never paid should not be told about a refund.
Pinned by `cancelWithNoPrepayment_firesWithoutAnyRefundSentence` and
`cancelWithPartialPrepayment_namesTheRefundTotalAsBeingProcessed`.

Likewise `carrier`/`tracking_number` are optional columns, so the template writes one sentence per
*present* field. An absent optional never renders — no "handed to null", no empty
"Tracking number:" tail. Pinned by `ship_withoutCarrierOrTracking_omitsBothSentences`.

## Transaction safety — the invariant that matters

Every producer runs inside its caller's business transaction, so **the notification exists iff the
business event commits**. Two directions, both tested:

- A cancel refused by the aggregate approval gate throws before any refund is created, the whole
  transaction rolls back, and the shopper is **never told their order was cancelled**
  (`cancelRefusedByTheApprovalGate_staysSilent`). This is the failure mode that matters: an email
  saying "your order was cancelled" about an order that is still live is worse than silence.
- A double `ship` is rejected by the `SHIPPED` guard and does not re-notify
  (`doubleShip_isRefused_andDoesNotReNotify`).

`notify()` itself still never throws (D3) — a customer with no email address is a *suppressed
channel*, not a failed shipment. All three producers additionally skip a customer-less order
(`customer_id` NULL is reachable on PHONE orders), mirroring `notifyOrderPaid`.

## Frontend

Small and additive, because the portal feed is type-agnostic by design (title/body are rendered
server-side; `type` only picks an icon).

- Three icons in `NotificationsList`: truck / circle-slash / triangle-alert.
- The order deep-link label is now a function of type: `PAYMENT_NEEDS_ATTENTION` says **"Complete
  payment"** rather than "View order", because the shopper owes an action and the link should name
  it. `ORDER_SHIPPED`/`ORDER_CANCELLED` keep the neutral label. New `completePayment` key in both
  locales.
- **No change to the admin app.** All three types are customer-recipient only, so they never reach
  the staff in-app feed or its preference catalog.

## Acceptance criteria

- [x] `ship` on a paid order raises exactly one `ORDER_SHIPPED` to the customer, naming carrier and
      tracking when recorded, carrying a fresh `VIEW_ORDER` magic link, with subject
      `Order {number} has shipped` and a "Track your order" CTA.
- [x] Absent carrier/tracking render no sentence and never the string `null`.
- [x] A two-shipment order raises two `ORDER_SHIPPED`; a re-ship raises none.
- [x] Cancel raises one `ORDER_CANCELLED`; with a prepayment it names the refund total as *being
      processed*; without one it mentions no refund.
- [x] A cancel rolled back by the approval gate raises nothing.
- [x] UNDERPAID raises `PAYMENT_NEEDS_ATTENTION` naming both what arrived and what is outstanding,
      subject `Order {number} still needs {outstanding} {currency}`, CTA "Complete your payment".
- [x] A second partial raises it again with the smaller remainder; the completing top-up raises
      `ORDER_PAID` instead; OVERPAID never raises it.
- [x] An org-wide email unsubscribe kills only the email leg — the feed row still lands.
- [x] A per-type opt-out of both channels records the notification and finalizes it `DISPATCHED`
      with zero deliveries.

## Tests

`api/.../notification/OrderLifecycleNotificationIT` — 13 tests, all of the above. Regression:
`OrderPaidNotificationIT` (9), `NotificationPreferenceIT` (14), `NotificationAdversarialIT` (12),
`NotificationDeliveryIT` (6), `PortalNotificationsIT` (6), `OrderCancellationIT` (8),
`OrderCancellationAdversarialIT` (10), `OrderPaymentsIT` (7), `FulfillmentShipIT` (8),
`DeliverInvoiceIT` (8), `FulfillmentReadIT` (15) — 116 green. Service module: 212 green.
Storefront: 368 green.

`OrderCancellationService` gained two constructor parameters (`NotificationService`,
`MagicLinkService`); `AppConfig` and the three ITs that build it directly were updated.

## Out (this slice)

- **`OUT_FOR_DELIVERY`** — reserved for the rider epic, per §1.
- **Expiry nudge and expired notice** — need a recurring sweeper + a `nudged_at` marker; own slice.
- **Per-delivery notification** — `markDelivered` still narrates only through the final
  `REVIEW_REQUESTED` roll-up. A "part of your order arrived" event is defensible but was not asked
  for and doubles the message count on split orders.
- **Localized templates** — every notification body is rendered server-side in English today. This
  is a pre-existing gap across all seven types (the content-localization epic never reached the
  notification templates), and fixing it for three types only would make the inconsistency worse.
  It wants its own slice: a locale on the customer, a template per locale, and a decision about
  what to do when the org's default locale and the customer's disagree. **This is the single
  biggest honesty gap left in the notification surface** for an Arabic-first shopper base.

---

## Slice B — the WhatsApp channel (specified, not built)

**Owner decision: per-merchant WABA.** Each merchant connects their own WhatsApp number, so the
message carries their brand and their existing relationship with the shopper, rather than arriving
from a platform sender the shopper has never heard of.

What that costs, concretely:

1. **A migration** — unlike a new type, a new channel is *not* free. `notification_delivery.channel`
   is `TEXT + CHECK (channel IN ('in_app','email'))` (V44 line 52, already annotated
   `-- sms/whatsapp: future`). Next number is **V79**. `UNIQUE (notification_id, channel)` already
   gives one delivery per channel per event, so nothing else in the supertable changes.
2. **A subtype table** — `notification_delivery_whatsapp`, mirroring `notification_delivery_email`:
   `to_number`, `template_name`, `template_params`, `provider_message_id`, plus the delivery-status
   fields the Cloud API webhooks return.
3. **Per-org credentials** — a WABA id, phone-number id and access token per org, which is a
   *secrets* question this codebase has not faced before (SMTP creds are process-wide env vars).
   Plus an onboarding surface in admin for Meta's embedded signup.
4. **`channelsFor(recipient)` stops being a constant.** Today it is a two-line switch returning a
   fixed list. It becomes a per-org, per-customer resolution: does this org have a connected WABA,
   does this customer have a usable number. That one method is the whole channel seam.
5. **A `WhatsAppSender` interface + `LoggingWhatsAppSender` fallback**, following `EmailSender` /
   `LoggingEmailSender` / `EmailSenderFactory` exactly, so dev and CI need no provider. The
   sweeper's claim→send→settle lease (V78) generalizes unchanged — it is already channel-agnostic
   apart from the content lookup.
6. **Template approval.** Meta requires pre-approved *utility* templates for business-initiated
   messages outside the 24-hour service window; free-form is not available for these. Under the
   per-merchant decision, **each merchant clears business verification and template approval
   themselves**. That is the main product risk of this choice and should shape the onboarding copy.

### The prerequisite nobody has solved: there is no E.164 phone in this system

`customer.phone` is free text. `Text.normalizeNumeric` folds Arabic-Indic/Persian digits to ASCII
and strips invisible joiners, but it deliberately **keeps non-digits** ("a phone may legitimately
carry `+`") and collapses rather than removes whitespace. So `01012345678`,
`+20 100 123 4567` and `0100-123-4567` are all live, valid values in that column today, and
WhatsApp needs `201012345678`.

Checkout takes `phone` as an **optional, unvalidated** field. So Slice B needs, before any provider
work: an E.164 normalization + validation step at every ingress (checkout, portal `PATCH /me`,
admin), an Egypt-aware parse (`01X…` → `+201X…`), a decision on what to do with the existing rows
(backfill what parses, leave the rest unreachable-by-WhatsApp), and a `phone_verified` notion if
the number is ever to be trusted as an identity claim. This is the same shape as the unicode
normalization slice (V62) and should be sized like one — **it is not a detail inside the channel
work, it is the gate in front of it.**

### Also unblocked by the same phone work

The `stories/rider_identity_and_linking.md` note that SMS delivery of rider OTPs/invites is "the
obvious future" has the identical blocker.
