# Slice P5: Customer portal notifications — a reliable in-app feed

> Customers only get **email** today (`channelsFor(CUSTOMER) → [EMAIL]`) — decided back when they had no
> logged-in surface. Now the portal (P1) gives them one, so this slice adds a **durable, in-app
> notification feed** on the portal: the reliable channel where a DB row *is* the delivery guarantee,
> with email kept as the offline reach. No new delivery infrastructure — the notification supertable
> (V44) already models `CUSTOMER` recipients and already drains `in_app` deliveries by channel.
>
> Canonical: [`frontst/docs/customer-portal-epic.md`](../../frontst/docs/customer-portal-epic.md).
> Depends on **P1** ([`portal_auth_core.md`](portal_auth_core.md)) for the customer session. Mirrors the
> staff feed (`NotificationHandler`, `/api/orgs/{orgId}/notifications`). Feeds frontend story 39.

---

## Goal
```
GET  /api/portal/notifications?page=&size=&unread=   → PageResponse of the session customer's feed (newest first)
GET  /api/portal/notifications/unread-count          → {count}  (the badge)
POST /api/portal/notifications/{id}/read             → 204 (idempotent; 404 if not the caller's)
POST /api/portal/notifications/{id}/dismiss          → 204
GET|PUT /api/portal/notification-preferences         → the customer's own opt-out settings
```
Customer-facing events (`ORDER_PLACED`, `ORDER_PAID` now; shipped/delivered/cancelled/refunded as their
producer hooks land) fan out to **both** `in_app` (the feed) **and** `email` (offline reach), honoring the
customer's per-`(org,customer)` preferences. All reads scoped to `recipient_customer_id` from the JWT.

## Why the in-app feed is the reliable channel (not push / SSE)
The `notification` row is written **inside the same business transaction** as the event, so it is durable,
**at-least-once**, and delivered whenever the customer opens `/account` (or a light poll ticks the badge)
— no dependency on a live socket, a browser push permission, a third-party push service, or email
deliverability. This is exactly how the staff feed already works: the row is the truth, the sweeper only
flips its status. **Email complements** it (reaches a customer who isn't in the portal). **Web Push / SSE
are deliberately out of scope** — they only *accelerate* an already-guaranteed feed and would add a
storefront service worker + VAPID / a persistent-connection layer this stack doesn't have. (Captured as a
future PWA slice.)

## Design
- **Routing:** `channelsFor(CUSTOMER)` → `[IN_APP, EMAIL]` (was `[EMAIL]`). The existing opt-out resolution
  (`exact(type,channel) → ALL → default-enabled`) already suppresses a channel per preference, so a
  customer can turn email off and keep the feed, or vice-versa. A fully-suppressed notification is still
  recorded `DISPATCHED` with zero deliveries (unchanged invariant).
- **Migration (next `V##`)** — a partial index mirroring the USER one:
  `CREATE INDEX idx_notification_recipient_customer ON notification (recipient_customer_id, created_at DESC)
  WHERE recipient_type = 'CUSTOMER';` The `notification`/`notification_delivery(_in_app)` tables and the
  `recipient_customer_id` FK + CHECK already exist (V44) — no schema change beyond the index.
- **Service:** `getCustomerFeed(orgId, customerId, unreadOnly, page, size)` + `countCustomerFeed` +
  `countUnread` — the `InAppFeedItem` read filtered by `recipient_customer_id` (mirror `getFeed`, which is
  keyed by `recipient_user_id`); `markRead`/`markDismissed` scoped to the customer's own `in_app` delivery
  (read_at/dismissed_at live on `notification_delivery_in_app`, per-recipient). Customer preference
  read/write reuses the existing `notification_preference` mechanism (already per-`(org,customer)`, the same
  rows the unsubscribe link writes).
- **API:** `PortalServlet` gains the routes above (`private, no-store`), scoped to the session
  `SecurityContext.actorId` (= customer id) + `org_id` — never a path/query id. `markRead/dismiss` 404 on a
  notification the caller doesn't own (no oracle).
- **Worker:** **unchanged.** `dispatchPendingInApp` reads pending deliveries **by channel**, so it already
  drains customer `in_app` deliveries; `dispatchPendingEmail` still handles the email leg.

## Scope
**In:** the channel routing change + index; the portal feed read + unread-count + read/dismiss + preferences;
wiring the two existing customer types (`ORDER_PLACED`, `ORDER_PAID`) to the dual channel. **Out:** Web Push /
service worker; SSE/WebSocket live push; new event *types* (each ships with its producer hook, not here);
staff-side changes (the staff feed is untouched).

## Authorization
Valid **customer** session only; every read/mutation strictly `(org_id, recipient_customer_id)`-scoped. A
customer can never read or mark another customer's (or a staff user's) notification.

## Acceptance criteria
1. A customer-facing event (e.g. `ORDER_PAID`) now creates **both** an `in_app` and an `email` delivery for
   the customer (subject to their preferences); the in-app row is immediately readable in the feed.
2. `GET /notifications` returns the session customer's feed newest-first, paged; `?unread=true` narrows;
   `unread-count` matches; dismissed rows are hidden.
3. `read`/`dismiss` mark only the caller's own entry (idempotent); a foreign/unknown id → 404.
4. Preferences: turning `email` off keeps the `in_app` feed working (and vice-versa); a fully-suppressed
   type still records `DISPATCHED` with zero deliveries.
5. Isolation: customer A never sees or mutates customer B's feed; a staff token → 401 on `/api/portal/*`
   (P1 invariant); no internal id leaks in feed items.
6. The staff feed (`/api/orgs/{orgId}/notifications`) is byte-for-byte unchanged (regression).

## Tests
`PortalNotificationsIT`: AC 1 (dual-channel produce + immediate feed read), AC 2 (feed/unread/paging), AC 3
(read/dismiss + foreign-id 404), AC 4 (preference suppression matrix), AC 5 (cross-customer isolation +
no-leak scan). `NotificationDeliveryIT`/staff feed ITs stay green (AC 6). Reuses the P1 session helper.

## What this unblocks
| Next | Depends on this |
|---|---|
| **Frontend story 39** — the portal bell + unread badge + notifications list | the feed + unread-count + read/dismiss |
| Web Push (future PWA slice) | a durable feed to *accelerate* — push never owns delivery |
