# The WhatsApp channel (slice B) — everything up to the Meta boundary

> **Slice B of the notification-reach epic** (`frontst/docs/notification-reach-epic.md`) — the
> original ask's "real unlock". Built complete against a logging sender; going live needs the
> merchant's own Meta onboarding, which is outside this system by design (§The boundary).

## What ships

`NotificationChannel.WHATSAPP` — the third leg, alongside the durable in-app feed and email.

```
V44 line 52:  channel TEXT NOT NULL CHECK (channel IN ('in_app','email'))  -- sms/whatsapp: future
V82:          …CHECK (channel IN ('in_app','email','whatsapp'))
```

That one line is the asymmetry this epic exists to make visible: a new notification **type** costs
nothing (`notification.type` is open TEXT), a new **channel** costs a migration and everything below.

| Piece | What |
|---|---|
| `V82` | widen the channel CHECK **and** the independent one on `notification_preference`; `notification_delivery_whatsapp`; `org_whatsapp_config` |
| `common/crypto/SecretBox` | AES-256-GCM — the first per-tenant secret this system stores |
| `WhatsAppSender` / `LoggingWhatsAppSender` / `CloudApiWhatsAppSender` / `WhatsAppSenderFactory` | the `EmailSender` quartet, verbatim in shape |
| `WhatsAppTemplates` | the template invocations, per type × language |
| `channelsFor` | now per-org, per-customer, per-type |
| `dispatchPendingWhatsApp` | the sweeper's third leg, on the same claim → send → settle lease |
| `OrgWhatsAppService` + `/api/orgs/{orgId}/whatsapp` | connect · status · enable/disable · disconnect |

## The decisions

### WhatsApp is **additive**, not a replacement for email

A shopper with both gets both. Preferences already let either side be turned off, nobody silently
loses a message, and the opposite policy (WhatsApp wins, email falls back) is a **one-line change
in `channelsFor`** if the duplication turns out to annoy people more than a missed message would.
Deliberately the conservative default on the way in.

### Three conditions, all failing to *absence*

The channel exists for a notification only when the org has an **ACTIVE** `org_whatsapp_config`,
**and** the customer has a `phone_e164` (V79 — an unparseable number is exactly this: unreachable),
**and** the event has an approved template. Any of them missing is a suppressed channel, never an
error — the D3 precedent, and the reason `whatsAppSpecFor` catches its own exceptions: `notify` runs
inside the caller's *business* transaction, so failing to answer "is WhatsApp available?" must never
roll back an order.

### Not every type gets a template

`COMMENT_REPLIED` and `REVIEW_REQUESTED` are deliberately absent. Meta classifies a Q&A answer and a
review nudge as **marketing**, not utility — a different (paid, opt-in) category, and sending paid
marketing is not what this epic is for. The switch is exhaustive, so a future type must decide
explicitly; "no template" is a visible `null`, not an oversight.

### Templates are invocations, not prose

Outside the 24-hour service window Meta accepts only a **pre-approved template name + ordered
positional parameters**. So the same event is expressed twice — as a sentence for the feed and the
email, as `(name, language, params)` here — and neither derives from the other. `template_language`
is stored per row because Meta approves **per language**: `order_shipped` in `ar` and in `en` are two
separately-approved artefacts, and a delivery record that cannot say which it invoked is not an
audit trail. Slice L landing first is what makes this cheap — the copy already exists in both.

`order_shipped` deliberately takes **fewer parameters than the email says**: carrier and tracking are
optional on a fulfillment, and a Meta template's parameter count is fixed at approval, so a template
naming a carrier could not be sent for a shipment without one. WhatsApp carries the fact; email
carries the detail.

### The first per-tenant secret

Every other credential here is hashed one-way (passwords, magic-link and refresh tokens) or supplied
by the operator at boot (JWT, SMTP). A per-merchant WhatsApp token is neither: it must be replayed
verbatim to Meta on every send, and it belongs to the merchant. Hashing is impossible; plaintext at
rest would mean one `SELECT *`, backup or log line hands over the ability to message that merchant's
customers as them.

`SecretBox` is AES-256-GCM under a platform key (`WHATSAPP_TOKEN_KEY`), fresh nonce per encryption,
authenticated so tampering fails loudly. **Deliberately not a key-management system** — no rotation,
no per-tenant keys, no envelope encryption. That is the honest scope for a first secret; rotation
needs a key id per row and a re-encrypt job that nobody has asked for, and an unused `key_version`
column would be worse than saying so. **Fails closed**: no key ⇒ `connect` is a 409, never a
plaintext write.

**No read path returns the token** — not the value, not a masked prefix, not a last-four. `connected`
plus the number is the whole answer a settings screen needs.

### Authority: OWNER to write, MANAGER to read

Higher than the STAFF bar merchandising uses, because this stores a credential that messages the
merchant's customers *as* them and Meta bills them per message — money-shaped authority, not catalog.

## Two defects found while building

**1. `notification_preference` has its own channel CHECK.** Widening `notification_delivery` alone
left a customer unable to opt out of WhatsApp — the PUT would 500 on a constraint violation. Caught
by the opt-out test; both constraints are now widened in V82, with a comment saying they are two
separate columns on two separate tables and nothing but that comment keeps them in step.

**2. A terminal claim failure was being rolled back — pre-existing, on the email path.** `claimEmail`
marked a delivery FAILED for a missing subtype row and then signalled it by **throwing out of
`transactionResult`**, which rolled that very write back. The row returned to PENDING and was
re-claimed **every tick, forever, silently**. The unit test could not see it: it stubs
`transactionResult`, so nothing ever rolls back there, and `verify(repo).markDeliveryFailed(…)`
passes happily.

I hit it on the WhatsApp path, where it is reachable in **normal operation** — a merchant disconnects
between produce and send — rather than only via a producer bug. Both paths now return a
`Claim(claimed, failedTerminally)` record instead of throwing, so the failure commits. Pinned by
`disconnectingBetweenProduceAndSend_failsTheDeliveryRatherThanRetryingForever`.

## The boundary — what is NOT built, and cannot be from here

1. **Business verification** and **WhatsApp Business Account creation** — the merchant does this in
   Meta's Business Manager.
2. **Template approval.** Every template must be registered as **utility**, in **both** languages.
   The exact reference bodies are in `WhatsAppTemplates`' Javadoc so whoever registers them has the
   text, with `{{1}}, {{2}}…` lined up against the params. **A merchant whose approved body has a
   different parameter count gets a terminal 4xx** and a FAILED delivery — by design, loudly.
3. **Embedded signup.** The connect endpoint takes the credentials as a body; a proper Meta OAuth
   flow that mints them is a frontend + Meta-app slice.
4. **Delivery webhooks.** `provider_status` / `provider_error` exist and stay null. WhatsApp is the
   first channel that *could* report a true DELIVERED (email never could without an ESP), but the
   webhook needs a public verified endpoint. `provider_message_id` is already stored so those
   receipts can be reconciled against these rows when it lands.

Until a merchant completes 1–3, `WhatsAppSenderFactory` returns `LoggingWhatsAppSender` (no
`WHATSAPP_TOKEN_KEY`) or the org simply has no config — either way the channel is absent and nothing
else changes. **The whole pipeline runs in dev and CI with no Meta account**, exactly as email runs
with no mailbox.

## Acceptance criteria

- [x] A connected org + a reachable customer gets a third delivery leg; it sends, as **that
      merchant's** identity, and stores the returned `wamid`.
- [x] An unconnected org, a customer with no `phone_e164`, a DISABLED config, or a type with no
      template each yield **no WhatsApp leg** — and never an error, and never affect the other legs.
- [x] `template_language` follows the resolved locale (slice L).
- [x] A per-channel opt-out suppresses WhatsApp only.
- [x] A transient fault retries; a terminal rejection fails immediately without burning the budget;
      disconnecting between produce and send fails the delivery rather than retrying forever.
- [x] The token is ciphertext at rest, round-trips for the sender, and appears in no read.
- [x] With no encryption key, connecting is refused rather than stored in the clear.

## Tests

`WhatsAppChannelIT` (12) · `WhatsAppTemplatesTest` (8, incl. an `@EnumSource` sweep forcing every
type to be an explicit decision) · `SecretBoxTest` (7). **Full backend sweep: 1054 ITs green**; unit
modules common 64, service 243.

## Env

- `WHATSAPP_TOKEN_KEY` — Base64, exactly 32 bytes decoded (`openssl rand -base64 32`). Unset ⇒
  `LoggingWhatsAppSender` and `connect` 409s. A wrong-length key is a **startup failure**, matching
  how `JWT_SECRET` is validated — a short key is a silent downgrade to a weaker cipher.

## Out

- **SMS.** WhatsApp is the decided reach channel; SMS would ride this same seam.
- **Rotation / per-tenant keys** — see the SecretBox note.
- **A frontend settings screen.** The endpoints exist; the admin UI for them is a frontend story.
- **Marketing templates**, and therefore `COMMENT_REPLIED` / `REVIEW_REQUESTED` on this channel.
