# Slice: Deliver customer email notifications (order magic links)

> **Phase 2** of [`docs/notifications-plan.md`](../docs/notifications-plan.md) (§2, §3, §6, §7, §8).
> Builds on Phase 1's in-app core (`stories/` has no Phase-1 story — it shipped as
> `feat(notifications): in-app notification core`). This slice turns on the **email** channel and the
> first **customer-facing** notification: when an online order is placed, the customer gets an email
> with a **magic link** to view that one order — no login.

---

## Goal

Wire the second delivery channel end-to-end:

1. A `CUSTOMER` recipient now resolves to an **`email`** delivery (Phase 1 rejected it loudly).
2. The producer writes the `notification_delivery_email` subtype row (to-address, subject, HTML) in
   the same business txn.
3. The recurring sweeper drains pending **email** deliveries through an `EmailSender` (Gmail SMTP in
   prod; a logging no-op when SMTP creds are absent, e.g. dev/test), marking `SENT`/`FAILED`.
4. Placement fires **`ORDER_PLACED` → customer email** alongside the existing staff in-app fan-out.
5. The email carries an **order-scoped magic link** (`V45 customer_magic_token`, `VIEW_ORDER`) that
   an anonymous public route validates and answers with just that order.

No preferences, no unsubscribe, no customer-session portal (all later phases — see §4/§7 of the plan).

---

## Scope

### In
- **`V45 customer_magic_token`** — capability tokens (SHA-256 at rest), `purpose = VIEW_ORDER` only.
- **Domain/repo:** `CustomerMagicToken` + `MagicTokenPurpose`; `CustomerMagicTokenRepository` (+factory);
  `NotificationRepository` gains the email-subtype write + read + a retry transition.
- **`EmailSender`** interface + `SmtpEmailSender` (Jakarta Mail / Angus) + `LoggingEmailSender`
  fallback + `EmailSenderFactory` (reads `mail.properties` + `SMTP_USERNAME`/`SMTP_PASSWORD`).
- **`MagicLinkService`** — mint an order-view link inside a txn; resolve a raw token to its order.
- **`NotificationService`** — `CUSTOMER → email`; insert email subtype; `dispatchPendingEmail(...)`.
- **Producer:** `SalesOrderService.placeOnlineOrder` mints the link + notifies the customer by email,
  in the placement txn (rolled-back order ⇒ no token, no notification).
- **Public route:** `GET /api/public/orders/{token}` (anonymous) → the one order.
- **Sweeper:** `NotificationDeliverySweeperJob` now drains **both** channels per tick.

### Out (deferred)
- **Preferences / unsubscribe** — Phase 3 (`V…__notification_preference`, resolution order,
  `UNSUBSCRIBE` token purpose).
- **Explicit sends** (admin→org, org→customer) — Phase 4.
- **Customer-session magic links / portal**, SMS/WhatsApp subtypes, real ESP `DELIVERED` webhooks,
  org template customization — "Later" bucket.
- `VIEW_INVOICE` — no invoice exists at `ORDER_PLACED`; add with the invoice-email events.
- A real HTML frontend page for the link — v1 points the link straight at the JSON public route.

---

## Why the sweeper sends email (not the producer, not an after-commit enqueue)

Same reasoning as Phase 1's in-app deviation note (plan §2/§3). Email is an **external side effect**,
so it must run *after* the business txn commits — never inside it (you can't un-send on rollback). The
codebase has no after-commit hook and no ad-hoc JobRunr enqueue; its only pattern is the recurring
sweeper. So the producer only writes `PENDING` rows, and `dispatchPendingEmail` (driven by the
recurring `notification-delivery-sweeper`) does the SMTP call. This is **at-least-once**: if the app
dies between the SMTP send and the `SENT` mark, the next tick re-sends. Acceptable for transactional
mail; a provider idempotency key is a later hardening.

Concurrency: `findDeliveryById(...).forUpdate()` locks the row, so two overlapping ticks that both
picked the same id serialize — the second sees non-`PENDING` and skips. The SMTP call happens while
the lock is held; at this volume and cadence (default every 10s) that is fine.

Retry/terminal: on a send failure we bump `attempts` + `last_error` and leave the delivery `PENDING`
so the next tick retries, until `attempts` reaches `EMAIL_MAX_ATTEMPTS` (default 5) → `FAILED`. (The
recurring sweeper, unlike JobRunr per-job retry, only revisits `PENDING` rows, so the retry budget
lives in the row, not the scheduler.)

---

## API contract — public order view

```
GET /api/public/orders/{rawToken}
```
Anonymous (mounted under the `JwtAuthFilter` `/api/public/` bypass, like the storefront). No org id in
the path — the token *is* the authorization, scoped to exactly one order.

### Response — `200 OK`
The same `SalesOrderResponse` shape the authenticated `GET .../sales-orders/{id}` returns (order +
frozen totals + lines + `expires_at`). The customer needs `grand_total`, `status`, `expires_at`, and
the line summary to track/pay the order.

### Errors
| Status | Cause |
|---|---|
| `404` | token unknown, expired, or not a `VIEW_ORDER` token (opaque — never distinguish, to avoid oracle) |
| `404` | the order the token unlocks no longer exists |
| `405` | non-GET |

The link is **multi-use until expiry** (a customer reasonably re-opens "view my order"); `consumed_at`
is reserved for one-shot purposes (unsubscribe, Phase 3). TTL default 30 days (`MAGIC_LINK_TTL_DAYS`).

---

## DB changes — `V45__Create_customer_magic_token.sql`

Mirrors the `customer-portal-future.md` sketch, at its narrowest tier (capability, not session):

```sql
CREATE TABLE customer_magic_token (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       UUID        NOT NULL REFERENCES org(id),
    customer_id  UUID        NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    token_hash   VARCHAR(64) NOT NULL UNIQUE,                 -- SHA-256 hex; raw token only in the URL
    purpose      TEXT        NOT NULL CHECK (purpose IN ('VIEW_ORDER','VIEW_INVOICE','UNSUBSCRIBE')),
    resource_id  UUID,                                        -- the order/invoice this token unlocks
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,                                 -- one-shot purposes only; NULL for VIEW_ORDER
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- Hot lookup: unconsumed tokens by hash.
CREATE INDEX customer_magic_token_lookup  ON customer_magic_token (token_hash) WHERE consumed_at IS NULL;
-- Cleanup scans (a purge job is a later concern).
CREATE INDEX customer_magic_token_cleanup ON customer_magic_token (expires_at);
```

`purpose` is TEXT+CHECK (matches the notification family; new purposes need no migration until the
CHECK widens). SHA-256 (not bcrypt) because tokens are full-entropy, short-lived, single-resource —
the portal doc's rationale.

The `notification_delivery_email` table already exists (shipped unused in `V44`); this slice is the
first to write it — **no new notification table**.

---

## Service contracts

```java
// MagicLinkService
String issueOrderViewLink(DSLContext txDsl, UUID orgId, UUID customerId, UUID orderId, OffsetDateTime now);
Optional<ResolvedOrderView> resolveOrderView(String rawToken, OffsetDateTime now);  // {orgId, orderId}

// EmailSender
void send(EmailMessage msg) throws EmailException;      // record EmailMessage(to, subject, html)

// NotificationService (new/changed)
DeliverySummary dispatchPendingEmail(int batchLimit);   // mirrors dispatchPendingInApp
// notify(): CUSTOMER recipient → email delivery; resolves customer.email; writes email subtype
//           (to=email, subject=title, html=body+link) using the linkTarget as the magic URL.
```

`issueOrderViewLink` generates a 256-bit `SecureRandom` token, stores `RefreshTokenStore.hashToken`
(reused SHA-256 hex), and returns `{PUBLIC_BASE_URL}/api/public/orders/{rawToken}`.

---

## File layout

| Module | New / changed |
|---|---|
| `repository/.../db/migration` | New: `V45__Create_customer_magic_token.sql`. Regen jOOQ after. |
| `domain` | New: `MagicTokenPurpose`, `CustomerMagicToken`, `CustomerMagicTokenRepository` (+factory). Changed: `NotificationType` (add `ORDER_PLACED` is already there — no change), `NotificationRepository` (email subtype + retry). |
| `repository` | New: `CustomerMagicTokenRepositoryImpl` (+factory impl). Changed: `NotificationRepositoryImpl`. |
| `service` | New: `EmailSender`, `EmailMessage`, `EmailException`, `SmtpEmailSender`, `LoggingEmailSender`, `EmailSenderFactory`, `MagicLinkService`. Changed: `NotificationService` (email path + `dispatchPendingEmail`), `NotificationTemplates` (email HTML helper), `SalesOrderService` (customer email producer). |
| `api` | New: `PublicOrderServlet` (`/api/public/orders/*`). Changed: `EmbeddedTomcatLauncher` (mount it), `AppConfig` (wire magic-token repo, email sender, magic-link service; feed `NotificationService`/`SalesOrderService`), `NotificationDeliverySweeperJob` (drain email too). |
| `common` | `mail.properties` already present (non-secret dev defaults). |
| root/service `pom.xml` | Add Angus Mail (`org.eclipse.angus:angus-mail`). |

---

## Config

- **`mail.properties`** (committed, non-secret): `mail.smtp.host/port/auth/starttls`, `mail.from`.
- **Env secrets:** `SMTP_USERNAME`, `SMTP_PASSWORD` (Gmail App Password). When either is blank the
  factory returns `LoggingEmailSender` — the app boots and delivers "successfully" (logged) without
  real SMTP, so dev/CI never need credentials. Real send is a manual smoke run.
- **`PUBLIC_BASE_URL`** — optional, default `http://localhost:8080`; the magic-link origin.
- **`MAGIC_LINK_TTL_DAYS`** — optional, default 30. **`EMAIL_MAX_ATTEMPTS`** — optional, default 5.
- The delivery cron/batch (`NOTIFICATION_SWEEPER_INTERVAL`/`_BATCH_LIMIT`) and the
  `ORDER_SWEEPER_BACKGROUND_ENABLED` gate are unchanged from Phase 1 — one sweeper now drains both
  channels.

---

## Authorization

- Producer path: unchanged — placement already requires an org role; the customer email is a
  side-effect of a successful placement.
- Public route: **anonymous by design** — the unguessable token is the capability. Narrow scope
  (`purpose = VIEW_ORDER`, `resource_id = orderId`): a leaked link exposes one order, nothing else.

---

## Acceptance criteria

- [ ] Placing an online order writes **two** notifications: staff `USER` (in_app, as Phase 1) **and**
      the customer `CUSTOMER` (email). The email notification has one `notification_delivery` (channel
      `email`) + a `notification_delivery_email` row whose `to_address` = the order's customer email.
- [ ] A sweeper tick with a fake `EmailSender` flips the email delivery `PENDING → SENT`, sets
      `sent_at`, and (once every sibling delivery is terminal) the parent `notification → DISPATCHED`.
      The fake captures exactly one message with subject = the rendered title and an HTML body
      containing the magic URL.
- [ ] A failing `EmailSender` leaves the delivery `PENDING` with `attempts` incremented and
      `last_error` set; after `EMAIL_MAX_ATTEMPTS` it is `FAILED`; siblings/peers are unaffected
      (per-delivery txn isolation).
- [ ] `GET /api/public/orders/{token}` with a fresh `VIEW_ORDER` token returns `200` + the order DTO
      for exactly that order. An unknown/expired token → `404`. A token for order A never returns
      order B.
- [ ] A rolled-back placement (e.g. insufficient stock) leaves **no** `customer_magic_token` and no
      email notification (both were written in the placement txn).
- [ ] With `SMTP_USERNAME`/`SMTP_PASSWORD` unset, `EmailSenderFactory` yields `LoggingEmailSender` and
      the app boots; deliveries are marked `SENT` (logged, not transmitted).

---

## What this unblocks

| Next slice | Depends on this |
|---|---|
| **Phase 3 — Preferences / unsubscribe** | reuses the email channel + `customer_magic_token` (adds the `UNSUBSCRIBE` purpose + resolution order) |
| **Phase 4 — Explicit sends** | admin→org / org→customer reuse `NotificationService.notify` + the email channel |
| **More customer email events** (payment verified, shipped, invoice issued, refund executed) | each is a `notify(CUSTOMER, …)` call in its service txn, plus a `VIEW_INVOICE` link where relevant |
| **Customer-session portal** | the capability-token tier proves the pattern the session tier extends |
