# Slice: Notification preferences (opt-out + unsubscribe)

> **Phase 3** of [`docs/notifications-plan.md`](../docs/notifications-plan.md) (§4, §8). Builds on
> Phase 1 (in-app core) and Phase 2 (email + magic links). Adds the one thing the plan defers but the
> product needs: a recipient can **turn a channel off** for a notification type — staff via an
> authenticated endpoint, customers via a one-click **unsubscribe** link in every email.

---

## Goal

Give `NotificationService.notify` a **resolution step**: before it writes a channel's delivery, it
checks whether that `(subject, type, channel)` is enabled. Disabled → the delivery is not created.
Two ways a preference gets set:

1. **Staff** manage their own preferences at `GET/PUT /api/orgs/{orgId}/notification-preferences`.
2. **Customers** (no login) click the **unsubscribe** link carried in every customer email; the
   anonymous route flips their email off for the org.

Preferences are **opt-out**: absence of a row means *enabled* (every wired event still fires by
default). A preference row only ever **suppresses**.

---

## Scope

### In
- **`V46 notification_preference`** — polymorphic subject (USER|CUSTOMER) × `type` × `channel` →
  `enabled`. `type` is a `NotificationType` name **or** the sentinel `ALL` (every type on that
  channel). Partial unique indexes per subject.
- **Resolution** in `notify()`: exact `(subject,type,channel)` → `(subject,ALL,channel)` → default
  (enabled). A fully-suppressed notification is still recorded but goes straight to `DISPATCHED`
  (zero deliveries), so nothing hangs `PENDING`.
- **Staff endpoints** — `GET`/`PUT /api/orgs/{orgId}/notification-preferences` (own-only, `VIEWER`).
- **Customer unsubscribe** — `MagicLinkService` mints an `UNSUBSCRIBE` token in every customer email;
  `POST|GET /api/public/unsubscribe/{token}` (anonymous) flips the customer's email off
  (`type=ALL, channel=email, enabled=false`), idempotently.
- **Email template** now carries the unsubscribe link (footer) in addition to the action link.

### Out (deferred)
- **Per-type customer unsubscribe** — v1 unsubscribe is all-email-off for the org (simplest,
  most-protective one-click). A future refinement can scope the token to the email's `type`.
- **Re-subscribe UI / customer preference centre** — customers can't re-enable in v1 (staff or a
  later portal slice can). The `enabled` column already supports it.
- **Email-to-staff preferences** — staff only receive `in_app` today; the table accepts an `email`
  channel row for forward-compat, but no staff email is wired yet (Phase 4+).
- **Explicit sends** (admin→org, org→customer) — Phase 4.

---

## Resolution — the one behavior that matters

```
enabledFor(subject, type, channel):
    row = SELECT enabled FROM notification_preference
          WHERE (subject match) AND channel = :channel AND type IN (:type, 'ALL')
          ORDER BY (type = :type) DESC       -- exact beats the ALL wildcard
          LIMIT 1
    return row.enabled if present else TRUE   -- opt-out: default enabled
```

`notify()` runs this per channel in `channelsFor(recipient)`. It keeps the notification row (audit:
"we would have notified"), skips suppressed channels, and if **no** channel survived, finalizes the
notification to `DISPATCHED` immediately.

---

## DB changes — `V46__Create_notification_preference.sql`

```sql
CREATE TABLE notification_preference (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       UUID        NOT NULL REFERENCES org(id),
    subject_type TEXT        NOT NULL CHECK (subject_type IN ('USER','CUSTOMER')),
    user_id      UUID        REFERENCES app_user(id),
    customer_id  UUID        REFERENCES customer(id) ON DELETE CASCADE,
    type         TEXT        NOT NULL,                 -- a NotificationType name, or 'ALL'
    channel      TEXT        NOT NULL CHECK (channel IN ('in_app','email')),
    enabled      BOOLEAN     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT notification_preference_subject_ck CHECK (
      (subject_type='USER'     AND user_id IS NOT NULL AND customer_id IS NULL) OR
      (subject_type='CUSTOMER' AND customer_id IS NOT NULL AND user_id IS NULL))
);
CREATE UNIQUE INDEX notification_preference_user_uq
    ON notification_preference (org_id, user_id, type, channel)     WHERE subject_type='USER';
CREATE UNIQUE INDEX notification_preference_customer_uq
    ON notification_preference (org_id, customer_id, type, channel) WHERE subject_type='CUSTOMER';
```

`UNSUBSCRIBE` already exists in the `customer_magic_token.purpose` CHECK (shipped in V45) — no schema
change for the token.

---

## API

### Staff preferences (`/api/orgs/{orgId}/notification-preferences`, `VIEWER`, own-only)
```
GET  → { "preferences": [ { "type": "ORDER_PLACED", "channel": "in_app", "enabled": false }, ... ] }
PUT  { "preferences": [ { "type": "ORDER_PLACED", "channel": "in_app", "enabled": false } ] }
```
`PUT` upserts each listed preference for the caller (merge, not full-replace). `type` must be a known
`NotificationType` name or `ALL`; `channel` ∈ {`in_app`,`email`}. Returns the resulting set.

### Customer unsubscribe (`/api/public/unsubscribe/{token}`, anonymous)
```
POST /api/public/unsubscribe/{token}   → 200 { "unsubscribed": true }
```
Resolves an `UNSUBSCRIBE` magic token, upserts `(customer, ALL, email, enabled=false)`, idempotent
(re-clicks return 200 while the token is live). Unknown/expired token → `404` (opaque). `GET` is
accepted too (one-click from an email client) and does the same — v1 accepts the mail-client-prefetch
trade-off; a confirm-page + `List-Unsubscribe-Post` hardening is a later refinement.

---

## Service contracts

```java
// NotificationService (new)
boolean isChannelEnabled(DSLContext txDsl, UUID orgId, NotificationRecipient r, String type, NotificationChannel ch);
List<NotificationPreference> getUserPreferences(UUID orgId, UUID userId);
List<NotificationPreference> setUserPreferences(UUID orgId, UUID userId, List<PreferenceInput> prefs);
void unsubscribeCustomerEmail(UUID orgId, UUID customerId);   // upsert (ALL,email,false)

// MagicLinkService (new)
String issueUnsubscribeLink(DSLContext txDsl, UUID orgId, UUID customerId, OffsetDateTime now);
Optional<ResolvedUnsubscribe> resolveUnsubscribe(String rawToken, OffsetDateTime now);  // {orgId, customerId}
```

---

## File layout

| Module | New / changed |
|---|---|
| `repository/.../db/migration` | New: `V46__Create_notification_preference.sql`. Regen jOOQ. |
| `domain` | New: `NotificationPreference`, `NotificationPreferenceRepository` (+factory). |
| `repository` | New: `NotificationPreferenceRepositoryImpl` (+factory impl). |
| `service` | Changed: `NotificationService` (resolution + staff CRUD + unsubscribe apply; new prefs-repo + magic-link deps), `MagicLinkService` (unsubscribe mint/resolve), `NotificationTemplates` (email footer link). |
| `api` | New: `NotificationPreferenceHandler` + DTOs, `PublicUnsubscribeServlet`. Changed: `OrgServlet` (register `notification-preferences`), `AppConfig` (prefs-repo, reorder magic-link before notification, feed new deps), `EmbeddedTomcatLauncher` (mount `/api/public/unsubscribe/*`). |

---

## Authorization

- Staff endpoints: `VIEWER` membership; a caller only ever reads/writes **their own** (`ctx.actorId()`)
  preferences — no `user_id` override (unlike the feed's ADMIN peek).
- Unsubscribe: anonymous; the unguessable token is the capability, scoped to one customer's email.

---

## Acceptance criteria

- [ ] A `USER` pref `(ORDER_PLACED, in_app, enabled=false)` suppresses that staff member's in-app
      delivery on the next placement; other staff still get theirs. The notification row exists and is
      `DISPATCHED` with zero deliveries for the opted-out user.
- [ ] Absence of any row → the notification fires (opt-out default). An `ALL` row suppresses every
      type on that channel; an exact-type row overrides a conflicting `ALL` row.
- [ ] `GET`/`PUT /api/orgs/{orgId}/notification-preferences` round-trips the caller's own prefs;
      `PUT` with an unknown `type` or `channel` → `400`; a caller cannot read/write another user's
      prefs.
- [ ] Every customer email's HTML contains an `/api/public/unsubscribe/{token}` link.
- [ ] `POST /api/public/unsubscribe/{token}` flips the customer's email off; a subsequent
      `ORDER_PLACED` produces the notification but **no** email delivery (→ `DISPATCHED`, zero
      deliveries). A second unsubscribe click is a `200` no-op. Unknown token → `404`.
- [ ] Rolled-back placement leaves no preference change and no token (all in the placement txn).

---

## What this unblocks

| Next slice | Depends on this |
|---|---|
| **Phase 4 — Explicit sends** | admin→org / org→customer sends respect the same resolution |
| **Per-type / re-subscribe** | the `enabled` column + `ALL` sentinel already model it |
| **Customer preference centre** | the unsubscribe token tier generalises to a managed-prefs token |
