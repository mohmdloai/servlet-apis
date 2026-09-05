# Slice: The Web Push channel — a phone that buzzes when the shelf runs low

> The third delivery channel after email (Phase 2) and WhatsApp (V82), and the first channel a
> **staff** recipient has ever had besides the in-app feed. `stories/portal_notifications.md`
> §89 predicted it exactly: *"Web Push (future PWA slice) — a durable feed to accelerate; push
> never owns delivery."* That doctrine is the whole design: the feed row stays the guarantee,
> the bell's poll stays the reconciliation, and push is the leg that reaches a phone in a
> pocket. The immediate motivation is `LOW_STOCK` (V94): the person who needs to know a shelf is
> empty is usually not looking at the dashboard. **Branch `177_feat/web-push` off `master`,
> migration V96.** Frontend pair: `frontst/stories/140_st_web_push.md`.

---

## Goal

1. **A device can subscribe.** `POST /api/me/push-subscriptions` stores the browser's push
   subscription (endpoint + the two keys) against the signed-in staff user; `DELETE` removes it;
   `GET /api/me/push/config` hands the client the VAPID public key so a key rotation never
   needs a frontend rebuild.
2. **Every staff notification gets a push leg.** `channelsFor(USER)` grows from `[IN_APP]` to
   `[IN_APP, PUSH]` when the user has a live subscription — one `push` delivery **per device**,
   frozen at produce time like the email leg freezes its address, honouring the same opt-out
   preferences as every other channel.
3. **The sweeper sends it.** `dispatchPendingPush` mirrors the email/WhatsApp claim → send →
   settle walk: RFC 8291 payload encryption, RFC 8292 VAPID authorization, the JDK `HttpClient`,
   `404`/`410` from the push service pruning the dead subscription, `429`/`5xx` retried on the
   shared attempt budget.
4. **Signing out everywhere signs push out too.** A subscription is valid for the token
   generation it was created in; `logout-all`, a password change, a de-privilege or a platform
   disable — every path that already bumps `app_user.token_version` — silences it with no new
   call sites.

Done means: a manager taps *Push notifications on this phone*, walks away, a cashier sells the
sixth-to-last notebook, and the phone buzzes *"Low stock: Notebooks"*; tapping it opens the
product's stock page. And when the manager signs out everywhere from a laptop, the phone goes
quiet.

---

## What exists, what is missing

- The notification supertable (V44): one `notification` row, one `notification_delivery` per
  channel with `status ∈ PENDING|SENDING|SENT|DELIVERED|FAILED`, `attempts`, `last_error`,
  `claimed_at` (V78), and a subtype table per channel that **freezes what will be sent** —
  `notification_delivery_email` holds the address and the rendered HTML,
  `notification_delivery_whatsapp` the template invocation. `UNIQUE (notification_id, channel)`
  pins one delivery per channel per event.
- `NotificationChannel` is `IN_APP | EMAIL | WHATSAPP` with `fromDbValue` as the only parser, so
  a new value is accepted by both preference endpoints the moment it exists. Both CHECKs
  (`notification_delivery.channel`, `notification_preference.channel`) are independent and were
  last widened by **V82**, which is the precedent this migration copies line for line.
- `channelsFor(recipient)` is *the* channel seam: `USER → [IN_APP]`, `CUSTOMER → [IN_APP, EMAIL]
  (+ WHATSAPP when the org has a live WABA)`. The WhatsApp resolver is three cheap checks that
  **fail to absence, never to error**, because `notify()` runs inside the caller's business
  transaction and a channel lookup must not roll back an order.
- The sweeper (`NotificationDeliverySweeperJob`, id `notification-delivery-sweeper`, every 10 s)
  runs `reapStranded` → `dispatchPendingInApp` → `dispatchPendingEmail` → `dispatchPendingWhatsApp`.
  Email and WhatsApp share the **claim → send → settle** shape: `PENDING → SENDING` under
  `FOR UPDATE SKIP LOCKED` in one short txn, the provider call with no connection held, a second
  short txn to `SENT` / `retry` / `FAILED`. A terminal provider answer (`TerminalWhatsAppException`)
  fails the row at once instead of burning four more attempts; a terminal failure found *during
  the claim* is returned, not thrown, because throwing rolled the claim back and re-claimed the
  row every tick forever. The stranded-claim reaper keys on `status = SENDING` and is channel-
  agnostic. `EMAIL_MAX_ATTEMPTS` (default 5) is the budget email and WhatsApp both draw on.
- Senders are an interface + a real implementation + a logging fallback chosen by a factory from
  env (`SmtpEmailSender` / `LoggingEmailSender` on `SMTP_*`; `CloudApiWhatsAppSender` /
  `LoggingWhatsAppSender` on `WHATSAPP_TOKEN_KEY`). `CloudApiWhatsAppSender` is the house
  outbound-HTTP shape: the JDK `HttpClient`, 5 s connect / 15 s request, `429`/`5xx` retryable,
  other `4xx` terminal, a package-private constructor that points at a local stub for the IT.
- `MeServlet` (`/api/me/*`, `requireAuth` only) is where a user acts on their own account:
  `GET /api/me`, `POST /api/me/password`. `SecurityContext.actorId()` is the `app_user.id`.
- **There is no per-user-device row anywhere in Postgres.** Sessions live in Redis
  (`RefreshTokenStore`): a *family* per device, 8-day TTL, garbage-collected on read, exposed on
  `GET /api/auth/sessions` as `family_id`. Every "sign out everywhere", password change,
  de-privilege and platform disable bumps `app_user.token_version` through
  `UserRepository.incrementTokenVersion` — seven call sites across `AuthService`,
  `MemberService` and `UserAdminService`.
- Crypto and signing on hand with no new dependency: JCE `EC`/`secp256r1`, `KeyAgreement("ECDH")`,
  `AES/GCM/NoPadding` (already used by `SecretBox`), `HmacSHA256`; **jjwt 0.13** in `common`
  signs ES256 with an `ECPrivateKey`. No BouncyCastle, no OkHttp, and none is needed.
- `PlatformQueuePredicates.FAILED_EMAILS` is `status = 'FAILED'` with **no channel narrowing**.
  Its own comment says the two coincide only while email is the sole channel with a failure
  path — a claim V82 already made stale, and a third channel would make the operator's
  *failed emails* tile count dead phones.

Missing, therefore: a subscription row, a channel value with its subtype, the resolver and
the send walk, the two crypto primitives, the sender pair, the `/api/me` endpoints, and one
overdue `AND channel = 'email'`.

---

## Why this shape

### Push accelerates; the feed still owns delivery

Nothing about the in-app leg changes. The notification row and its `in_app` delivery are
written exactly as today; the bell keeps its 60-second poll and its refetch on focus and on
route change. Push is one more leg on the same row, produced in the same transaction, so a
sale that rolls back sends nothing, and a push that never arrives — permission revoked,
subscription expired, phone off for a week — costs the merchant nothing they did not already
have. This is the WhatsApp rule applied to staff: *additive, not a replacement*.

### One delivery row per device, not one per channel

`UNIQUE (notification_id, channel)` says "one delivery per channel". A staff member with a
phone and a laptop has two push targets, and they can fail independently: the phone's
subscription is dead (`410`), the laptop's is fine. Modelling that as one delivery whose subtype
holds N endpoints would mean inventing per-target status, per-target attempts and a
"SENT if at least one" rule on top of a pipeline that already has all of that **per delivery**.
So the constraint is relaxed for this channel only:

```sql
ALTER TABLE notification_delivery DROP CONSTRAINT notification_delivery_channel_uq;
CREATE UNIQUE INDEX notification_delivery_channel_uq
    ON notification_delivery (notification_id, channel) WHERE channel <> 'push';
```

A push delivery is **one row per (notification, subscription)**, and the subtype row freezes
that subscription's endpoint and keys plus the JSON payload — the email precedent, one level
down. Every existing mechanism then applies unchanged: the claim, the attempt budget, the
stranded reaper, `finalizeIfTerminal` (the parent flips `DISPATCHED` when *all* its deliveries
are terminal), and the failed-delivery queue. `insertDelivery` is the only write that notices.

### The subscription is keyed on the endpoint, and expires with the token generation

The browser's push endpoint URL is unique per subscription by construction, so it is the
natural key: `UNIQUE (endpoint)`, upsert on re-subscribe. It is **not** keyed on the Redis
session family: a family is TTL-bounded and garbage-collected on read, so a subscription bound
to it would dangle silently, and the codebase has never held a per-device row in Postgres.

What a subscription *does* carry is **`token_version_at_subscribe`**, the user's
`app_user.token_version` when it was created. A subscription is live only while
`app_user.token_version` still equals it. That one column makes every existing revocation path
work with no new call site: `logout-all`, a self password change, a MANAGER revoking a member's
role, a platform ADMIN disabling the account — all bump the version, all silence push. It is the
same mechanism that already invalidates the access tokens those actions must kill. A single
`logout` from one device is the client's job: it unsubscribes and `DELETE`s its own endpoint
before clearing cookies (best-effort; a session that merely expires keeps its subscription, and
a push tapped after that lands on the login page — the same as an email read after logout).

Dead subscriptions are also pruned by the push service's verdict: a `404` or `410` on send
deletes the row. There is deliberately no client-side polling of "is my subscription still
valid" — the server learns it at the only moment it matters.

### Frozen at produce time; resolved to absence, never to error

`pushTargetsFor(txDsl, user)` returns the user's live subscriptions (`token_version` matches,
`app_user.active`), catching any `RuntimeException` into an empty list — the `whatsAppSpecFor`
rule, for the same reason: this runs inside the placement transaction. No live subscription →
no push delivery row and nothing logged as a failure (the "customer without an email address"
precedent — absence is a state, not an error). Each target gets its own delivery + subtype row
with the payload JSON frozen then; a device subscribed a second later gets the next event, not
this one.

### The crypto is in-house, pinned to the RFC's own test vector

The alternatives were weighed. `nl.martijndwars:web-push` pulls BouncyCastle, jose4j and Apache
HttpAsyncClient into a codebase that has none of them and whose dependency posture is
deliberate (manual DI, the JDK client everywhere). What Web Push needs is small and fully
covered by the JCE: an ephemeral P-256 key pair, ECDH with the browser's `p256dh`, HKDF-SHA256
(a dozen lines over `HmacSHA256`), AES-128-GCM with the `aes128gcm` record framing (RFC 8188),
and a VAPID JWT — ES256, which jjwt already signs. The two hard parts — the key-derivation
info strings and the uncompressed-point encoding of P-256 keys — are exactly what
**RFC 8291 Appendix A** pins byte for byte: given its fixed keys and salt, the ciphertext must
match. That test is the guarantee a library would have offered, without the library.

`WebPushEncryptor` lives in `common/.../crypto/` beside `SecretBox`; `VapidSigner` in
`common/.../security/` beside `JwtUtil` (jjwt is a `common` dependency, not a `service` one).

### The failed-emails tile stops counting other channels

`FAILED_EMAILS` gains `AND channel = 'email'`. It is a visible change to a platform tile, and it
is overdue: since V82 a failed WhatsApp delivery has been counted as a failed email. A failed
push is a dead phone, not an operator queue — it is pruned or retried by the sweeper — so no
`failed-pushes` kind ships (Out, with the row shape).

---

## Contract

### Migration V96

```sql
-- the channel
ALTER TABLE notification_delivery DROP CONSTRAINT notification_delivery_channel_check;
ALTER TABLE notification_delivery ADD CONSTRAINT notification_delivery_channel_check
    CHECK (channel IN ('in_app','email','whatsapp','push'));
ALTER TABLE notification_preference DROP CONSTRAINT notification_preference_channel_check;
ALTER TABLE notification_preference ADD CONSTRAINT notification_preference_channel_check
    CHECK (channel IN ('in_app','email','whatsapp','push'));

-- one push delivery per device (see "One delivery row per device")
ALTER TABLE notification_delivery DROP CONSTRAINT notification_delivery_channel_uq;
CREATE UNIQUE INDEX notification_delivery_channel_uq
    ON notification_delivery (notification_id, channel) WHERE channel <> 'push';

-- the device
CREATE TABLE push_subscription (
    id                          UUID PRIMARY KEY,
    user_id                     UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    endpoint                    TEXT NOT NULL UNIQUE,
    p256dh                      TEXT NOT NULL,          -- base64url, 65-byte uncompressed P-256 point
    auth                        TEXT NOT NULL,          -- base64url, 16 bytes
    user_agent                  VARCHAR(255),
    token_version_at_subscribe  INTEGER NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at                TIMESTAMPTZ
);
CREATE INDEX push_subscription_user_idx ON push_subscription (user_id);

-- what was sent, frozen (the email precedent)
CREATE TABLE notification_delivery_push (
    delivery_id      UUID PRIMARY KEY REFERENCES notification_delivery(id) ON DELETE CASCADE,
    subscription_id  UUID REFERENCES push_subscription(id) ON DELETE SET NULL,
    endpoint         TEXT NOT NULL,
    p256dh           TEXT NOT NULL,
    auth             TEXT NOT NULL,
    payload_json     JSONB NOT NULL,
    provider_status  INTEGER
);
```

No other index: V44's `idx_delivery_pending (channel, created_at) WHERE status = 'PENDING'`
already serves the sweeper for every channel (V82's verdict), and `push_subscription` is read
by `user_id` and by `endpoint`, both covered.

### `NotificationChannel.PUSH("push")`

Accepted by `PUT /api/orgs/{orgId}/notification-preferences` on the staff plane at once (same
`fromDbValue` parser). The portal plane's parser accepts it too, and `channelsFor(CUSTOMER)`
never offers it, so a customer preference row for `push` is inert — exactly as a customer
`in_app` row is today.

### `/api/me` endpoints (`requireAuth`, own account only, 403 while impersonating)

- `GET /api/me/push/config` → `{enabled, public_key?}`. `enabled` is a primitive (always on the
  wire); `public_key` is the VAPID public key, base64url, present iff enabled. `enabled:false`
  means the server has no key pair and the client renders the switch as unavailable.
- `POST /api/me/push-subscriptions` `{endpoint, keys:{p256dh, auth}, user_agent?}` → `201`
  fresh, `200` when the endpoint already belongs to this user (idempotent re-subscribe; the
  keys and `token_version_at_subscribe` are refreshed). An endpoint that belongs to **another**
  user — one browser profile, two accounts — is moved to the caller (`200`); the browser has
  one subscription per origin and it belongs to whoever is signed in now. `400` on a missing
  endpoint, a non-`https` endpoint, or keys that do not decode to 65 / 16 bytes. `503` when
  `enabled:false`.
- `DELETE /api/me/push-subscriptions` `{endpoint}` → `204`, idempotent, own-only; an endpoint
  that is not the caller's is also `204` (no oracle).
- `GET /api/me/push-subscriptions` → `[{id, user_agent, created_at, last_used_at}]` — never the
  endpoint or the keys; a listing for the sessions-style "devices with push" read. Optional
  for v1; the frontend story says whether it renders it.

### Produce

`channelsFor(USER)` → `[IN_APP]` + `PUSH` iff `pushTargetsFor(user)` is non-empty. For each live
subscription: one `notification_delivery (channel = 'push', PENDING)` + one
`notification_delivery_push` with `payload_json`:

```json
{"notification_id":"…","type":"LOW_STOCK","title":"Low stock: Notebooks",
 "body":"5 left of Notebooks (NB-A5) — reorder point 5.",
 "org_id":"…","source_type":"product","source_id":"…"}
```

Title and body are the rendered template in the recipient's locale (the in-app row's own
words). `source_type`/`source_id` are what the client resolves to a route; the API-shaped
`link_target` is deliberately **not** in the payload. The whole payload stays well under the
4 KB push limit. `isChannelEnabled(type, PUSH)` gates each target like every other channel;
opt-out is the standard `notification_preference` row — a `PUSH` row for `ALL` silences every
push, a per-type row one type.

### Send

`dispatchPendingPush(limit)` mirrors `dispatchPendingWhatsApp` step for step:

1. **Claim** (short txn): `PENDING → SENDING`, read the subtype row. A subtype with no
   subscription (`subscription_id IS NULL` — the device was pruned since produce) is a terminal
   failure **returned from the claim**, per the WhatsApp rule.
2. **Send** (no txn): `WebPushSender.send(target, payloadJson)` → encrypt with a fresh ephemeral
   key + 16-byte salt (RFC 8291), `POST endpoint` with `Content-Encoding: aes128gcm`,
   `TTL: 86400`, `Urgency: normal`, `Authorization: vapid t=<jwt>, k=<public key>`, where the JWT
   is ES256 with `aud` = the endpoint's origin, `sub` = `WEB_PUSH_SUBJECT`, `exp` = now + 12 h.
   `201`/`200` → success. `404`/`410` → `TerminalWebPushException(GONE)`. `400`/`401`/`403`/`413`
   → terminal (a misconfigured key pair or an oversized payload is never fixed by a retry; logged
   at ERROR). `429`/`5xx`/transport → retryable.
3. **Settle** (short txn): success → `SENT` (+ `provider_status`, `push_subscription.last_used_at`);
   `GONE` → `FAILED` **and `DELETE` the subscription**; other terminal → `FAILED`; retryable →
   `markDeliveryRetry` until `attempts + 1 >= emailMaxAttempts` → `FAILED`. `finalizeIfTerminal`
   as everywhere.

The job's `run()` gains one line. `reapStranded` needs nothing: it keys on `SENDING`.

### Sender + config

- `service/.../push/WebPushSender` (`void send(PushTarget, byte[] payload) throws WebPushException`),
  `JdkWebPushSender` (the `CloudApiWhatsAppSender` shape: JDK client, 5 s / 15 s, a
  package-private constructor for a stub base), `LoggingWebPushSender` (never sends, returns
  normally so the sweeper marks `SENT`), `WebPushSenderFactory.build(config)`.
- `common/.../crypto/WebPushEncryptor` (RFC 8188 + 8291), `common/.../security/VapidSigner`
  (RFC 8292; jjwt ES256).
- Env: `WEB_PUSH_VAPID_PUBLIC_KEY` / `WEB_PUSH_VAPID_PRIVATE_KEY` (base64url, the raw 65-byte
  point and the 32-byte scalar — generated once with the documented one-liner and kept like
  `JWT_SECRET`), `WEB_PUSH_SUBJECT` (`mailto:` or `https:`), `WEB_PUSH_MAX_ATTEMPTS` (optional;
  default = `EMAIL_MAX_ATTEMPTS`, the shared budget). Either key unset → the logging sender and
  `config.enabled:false`. A key present but malformed → startup failure (the `SecretBox` rule).

### Platform plane

`FAILED_EMAILS` → `status = 'FAILED' AND channel = 'email'`. Re-measure
`notification_delivery_failed_global_idx` on `perfdb` (V73's rule) and record the capture in
`tools/seed/results/`; the predicate is more selective, so the expectation is "same plan".

---

## Out (deferred, each with its shape)

- **Customer push on the storefront.** The storefront has no service worker; `channelsFor(CUSTOMER)`
  is untouched. When it comes it is the same table with `customer_id`, the portal's own
  `/api/portal/me/push-subscriptions`, and `CustomerSessionStore`'s version.
- **Per-device revoke from the sessions page.** Needs the family as provenance
  (`session_family_id UUID NULL` on `push_subscription`, read from the access token's `fam` claim
  by exposing it on `SecurityContext`) so `DELETE /api/auth/sessions/{familyId}` can delete that
  device's subscription. Sign-out-everywhere already covers the lost-phone case.
- **A `failed-pushes` platform queue.** `PlatformQueueKind.FAILED_PUSHES` + a sealed
  `PlatformQueueRow.FailedPush(org, id, notificationType, userAgent, attempts, lastError,
  createdAt)` — **the endpoint never crosses** (it is a bearer capability, worse than PII).
  Not shipped because a dead device is pruned, not worked.
- **`Topic` collapsing** (`Topic: low-stock-{productId}` so ten undelivered low-stock pushes for
  one product collapse to the latest). One header; deferred until there is a shelf that needs it.
- **Rich notifications** (actions, images, `renotify`). The payload carries what the feed row
  carries; anything more is a template decision.
- **Renaming `emailMaxAttempts`** to a channel-neutral name now that three channels share it.

---

## Tests

- `WebPushEncryptorTest` — RFC 8291 Appendix A: the fixed authentication secret, receiver key
  pair, sender key pair and salt produce **exactly** the RFC's ciphertext; round-trip decrypt
  with the receiver key for a random payload; a `p256dh` that is not a valid point → rejected.
- `VapidSignerTest` — the header parses as `vapid t=…, k=…`; the JWT verifies with the public
  key; `aud` is the endpoint origin only (scheme + host, no path); `exp` ≤ 24 h; `sub` echoes
  config.
- `PushSubscriptionIT` (Testcontainers, `/api/me` handler): subscribe `201`, re-subscribe `200`
  refreshes keys, another user's endpoint is moved, bad keys/`http:` endpoint → `400`,
  `enabled:false` → `503`, `DELETE` idempotent and oracle-free, `GET` never returns the endpoint;
  a `token_version` bump (`logout-all`) leaves the row but `pushTargetsFor` no longer returns it;
  a disabled user likewise; deleting the user cascades.
- `WebPushDeliveryIT` (real `NotificationService`, a recording `WebPushSender`): a staff
  notification for a user with two live subscriptions produces **two** push deliveries + one
  in-app; no subscription → in-app only, nothing logged as failure; a `PUSH`/`ALL` opt-out
  suppresses the leg; the sweeper sends the frozen payload (title/body/source) and marks `SENT`
  + `last_used_at`; a `410` from the sender fails the delivery **and deletes the subscription**;
  a `503` retries then fails at the budget; a subtype whose subscription was pruned fails at the
  claim without a send; the parent flips `DISPATCHED` when the in-app and every push leg are
  terminal; a `LOW_STOCK` crossing end to end (the `LowStockNotificationIT` fixture) reaches the
  push leg with the product in `source_id`.
- `NotificationPreferenceHandler` — `push` parses; the portal parser too; unknown still `400`.
- `PlatformQueuesIT` — a `FAILED` WhatsApp or push delivery no longer counts on `failed_emails`.
- `NotificationTemplatesTest` untouched (no new type); the existing email/WhatsApp ITs stay green.

---

## Docs to touch

- `docs/notifications-plan.md`: §8 routing gains a *push* column for the two staff rows
  (`ORDER_PLACED`, `LOW_STOCK` — `in_app` + `push`); the "a new channel is not free" note names
  V96 and this story; *Later* drops "push" into *shipped*.
- CLAUDE.md: the notifications paragraph (channels, the per-device delivery rule), the `/api/me`
  block, the env list, the `failed-emails` narrowing.

## Definition of done

- [ ] V96 applied, codegen re-run; `NotificationChannel.PUSH`; both CHECKs widened; the partial
      unique; `push_subscription` + `notification_delivery_push`.
- [ ] `WebPushEncryptor` + `VapidSigner` in `common`, pinned to the RFC vectors.
- [ ] `WebPushSender` pair + factory; env + startup validation; `LoggingWebPushSender` when unset.
- [ ] `pushTargetsFor`, the produce leg, `dispatchPendingPush`, the job line.
- [ ] `/api/me/push/config`, `/api/me/push-subscriptions` (`GET`/`POST`/`DELETE`).
- [ ] `FAILED_EMAILS` narrowed; `perfdb` re-measure recorded.
- [ ] Tests above green; `service` + `api` batteries green; perfdb hand-migrated to V96.
