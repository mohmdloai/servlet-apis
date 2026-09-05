# Notifications — Implementation Plan

> Multi-channel transactional notifications (in-app + email), enable/disable per user,
> platform-admin→org and org→customer sends, on-the-fly (guest) customers, and email delivery
> via order-scoped magic links. Grounded in `sys-analysis/notifications/notifications.md` (storage
> model) and refining `docs/customer-portal-future.md` (magic links). Same spine as the rest of the
> codebase (`domain → repository → service → api`, jOOQ, manual DI, JobRunr worker, `.properties` +
> env config).

## Why this fits the codebase

The notification storage model maps onto existing infrastructure almost 1:1 — the async worker
(**JobRunr**, already running for the order sweeper), the supertable-over-jOOQ pattern, per-`org_id`
tenancy, the `/api/admin` platform tier, and the servlet/handler dispatch. The only genuinely new
external dependency is an **SMTP sender** (Gmail). Two decisions are settled by the product owner:
email provider = **Gmail SMTP**; customer recipients are **email-only** (customers have no in-app
feed — they are not `app_user` rows).

Next migration number: **V47** (V44 = notification tables, V45 = customer_magic_token, V46 =
notification_preference, all shipped).

---

## 0. Customer model — org-belong or on-the-fly (service-only; **no migration**) — ✅ already implemented

> **Status: done.** An audit of the placement path found this slice already built —
> `SalesOrderService.resolveCustomer` → `SalesOrderRepository.upsertCustomerByEmail`
> (`SalesOrderRepositoryImpl.java`) does the merge on the existing constraint. Regression coverage is
> `api/.../customer/CustomerResolutionAtPlacementIT` (on-the-fly, per-org merge, COALESCE contact
> merge, email normalization, cross-org isolation, missing-email 400); the walk-in null-customer case
> is in `InStoreSaleIT`. What follows records the behavior + the separation of concerns that settles
> the "capture email on the order?" question — **no code is owed here.**

The per-org email uniqueness this needs **already exists**: `V15` dropped the original global
`customer_email_unique` and added `customer_org_email_unique UNIQUE (org_id, email)` (verified against
the live schema — do **not** re-add it). `sales_order.customer_id` is **nullable** (`V17`), so a
walk-in anonymous sale stays customer-less, but the online/notify path resolves a customer.

**Target:** one `customer` entity, two creation paths — staff CRM entry *or* on-the-fly at checkout —
both **org-owned**, deduped by the existing `(org_id, email)` constraint.

- **Service only (no schema change):** on order placement, if the request carries an email and no
  `customer_id`, the placement path upserts against the existing `customer_org_email_unique`
  (`INSERT … ON CONFLICT (org_id, email) DO UPDATE …`, inside the placement txn) and returns the row;
  the order is placed against it. **Merge-by-email is the chosen default:** a guest whose email
  already matches an org customer is attached to that customer (one email = one customer per org). The
  merge is latest-non-null-wins (`COALESCE(excluded, current)`): a non-null incoming name/phone/address
  overwrites, a null one preserves what's there. The upsert lives on `SalesOrderRepository` (the only
  caller today); a standalone `CustomerService.upsertByEmail` was **not** added — it would be an
  uncalled method until a slice needs a direct entry point.
- **Portal tie-in:** `customer-portal-future.md` magic links key on `customer_id`, so on-the-fly
  customers are eligible the moment they have a row — no change to that design, only that both
  creation paths feed it.

> **Separation of concerns (why there is no `sales_order.contact_email`).** Three layers, three jobs:
> - **`customer`** holds *current* identity + contact (`email/name/phone/address`), mutable.
> - **`sales_order` / `sales_order_line`** snapshot what is *operationally/legally significant at
>   purchase time* — prices (`unit_price` = "price-at-order-time", `V18`), taxes, totals. (Shipping/
>   billing address is not modelled anywhere yet; when it lands it snapshots here.)
> - **`sales_invoice`** already freezes the *legal contact* (`customer_name/email/phone/address`,
>   "in case the customer record changes later", `V21`) — this is the immutable record of who the
>   document was for.
>
> So notifications send to the **current** `customer.email` (via the order's `customer_id`). We add an
> immutable `contact_email` to the *order* only if a real business requirement demands proving/
> reproducing the original delivery target — and even then the invoice's frozen email usually already
> answers it. No order-level contact snapshot is in scope.

> **Merge caveat (deliberate):** because a later customer-session magic link authenticates "this
> email" and shows all its orders in the org, merge-by-email makes an email an identity claim. That
> is acceptable — a magic link proves inbox control — but it is why the customer-session tier (§7)
> is its own slice, not a bolt-on.

---

## 1. Notification storage (the supertable model)

Follows `notifications.md` exactly, with one **amendment**: the recipient is polymorphic, because a
customer is not an `app_user`.

- **`V44` — notification + delivery supertable + channel subtypes**
  ```sql
  notification (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        uuid NOT NULL REFERENCES org(id),
    recipient_type text NOT NULL CHECK (recipient_type IN ('USER','CUSTOMER')),
    recipient_user_id     uuid REFERENCES app_user(id),   -- set iff USER
    recipient_customer_id uuid REFERENCES customer(id),   -- set iff CUSTOMER
    type          text NOT NULL,                          -- 'ORDER_PLACED','PAYMENT_VERIFIED',...
    title         text NOT NULL,
    body          text NOT NULL,
    source_type   text, source_id uuid,                   -- light polymorphic ref (e.g. 'sales_order')
    status        text NOT NULL CHECK (status IN ('PENDING','DISPATCHED')),
    payload       jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    dispatched_at timestamptz,
    CONSTRAINT notification_recipient_ck CHECK (
      (recipient_type='USER'     AND recipient_user_id     IS NOT NULL AND recipient_customer_id IS NULL) OR
      (recipient_type='CUSTOMER' AND recipient_customer_id IS NOT NULL AND recipient_user_id     IS NULL))
  );
  CREATE INDEX idx_notification_pending ON notification (org_id, created_at) WHERE status='PENDING';

  notification_delivery (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id uuid NOT NULL REFERENCES notification(id) ON DELETE CASCADE,
    channel text NOT NULL CHECK (channel IN ('in_app','email')),   -- sms/whatsapp future
    status  text NOT NULL CHECK (status IN ('PENDING','SENT','DELIVERED','FAILED')),
    attempts int NOT NULL DEFAULT 0, last_error text,
    sent_at timestamptz, delivered_at timestamptz, failed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (notification_id, channel)
  );
  CREATE INDEX idx_delivery_pending ON notification_delivery (channel, created_at) WHERE status='PENDING';

  notification_delivery_in_app (
    delivery_id uuid PRIMARY KEY REFERENCES notification_delivery(id) ON DELETE CASCADE,
    read_at timestamptz, dismissed_at timestamptz, link_target text
  );
  notification_delivery_email (
    delivery_id uuid PRIMARY KEY REFERENCES notification_delivery(id) ON DELETE CASCADE,
    to_address text NOT NULL, subject text NOT NULL, rendered_html text,
    smtp_message_id text, bounce_status text, bounce_reason text
  );
  ```
- **Domain/repository:** `Notification`, `NotificationDelivery`, channel subtype models; enums
  `NotificationType`, `NotificationChannel`, `DeliveryStatus`; `NotificationRepository` (+factory,
  since producing runs inside business transactions). Discriminator/child rows are **app-enforced**
  (created in the same txn), per the spec; add a periodic invariant check for orphan deliveries.

## 2. Producer (`NotificationService.notify`) — transaction-safe

`notify(orgId, recipient, type, payload)`:
1. Render `title`/`body` from a **code template** for `type` (templates in code/files, not DB — org
   customization later, per spec).
2. Insert `notification` (PENDING) + one `notification_delivery` (PENDING) **per enabled channel**
   (§4) + its subtype row — **all in the caller's business transaction** (so no notification for a
   rolled-back order).
3. Deliveries land `PENDING`; the recurring worker (§3) picks them up. Producing is internal; §5
   exposes explicit admin/org producers on top of it.

> **Implemented deviation (§2 step 3 / §3): a recurring delivery sweeper, not after-commit enqueue.**
> The plan said "after commit, enqueue one JobRunr job per delivery." The codebase has **no
> after-commit hook** and no ad-hoc `enqueue` anywhere — its only JobRunr pattern is the recurring
> **order-TTL sweeper**. So Phase 1 mirrors that exactly: `NotificationDeliverySweeperJob` runs on a
> cron (`NOTIFICATION_SWEEPER_INTERVAL`, default every 10s) and drains `PENDING` deliveries. This is
> strictly *more* crash-safe than enqueue-after-commit — a `PENDING` row is always eventually
> delivered even if the app dies between commit and enqueue — and avoids introducing a fragile
> commit hook. The producer therefore takes no `JobScheduler` dependency; it only writes rows.

## 3. Worker (JobRunr recurring sweeper) — per-channel delivery

`NotificationDeliverySweeperJob` reads PENDING delivery ids in autocommit, then transitions each in
its own short txn (poison-pill isolation, like the order sweeper); a `FOR UPDATE` on the row keeps
concurrent ticks from double-sending. It dispatches by channel:
- **in_app:** insert is the delivery — `SENT` immediately (`DELIVERED` implicit; we own the feed).
- **email:** hand to SMTP (§6) → `SENT`; `DELIVERED` = no bounce within a window (best-effort in v1;
  real DELIVERED via ESP webhook is future). On provider error: `attempts++`, `last_error`, and
  `FAILED` after max retries (JobRunr handles retry/backoff).
When every delivery is terminal, flip the parent `notification` to `DISPATCHED`.

## 4. Preferences — enable/disable (the doc defers this; your requirement needs it)

- **`V46` — `notification_preference`** (`user_id`/`customer_id` × `type` × `channel` → enabled bool),
  polymorphic like the recipient. Resolution order in `notify()`: explicit preference → per-event
  default (§8 table) → channel validity (customers can't have `in_app`).
- **Staff:** manage own prefs via `GET/PUT /api/orgs/{orgId}/notification-preferences`.
- **Customers (no login):** per-org event **defaults** + a one-click **unsubscribe token** in every
  email (a resource-scoped magic token, §7) — no session required.

## 5. Explicit sends (extends the doc, which is internal-only)

- **Platform admin/support → org** — `POST /api/admin/notifications` (ADMIN write / SUPPORT per
  policy) `{org_id, recipient_scope, type|title+body}`. Fans out to the org's users as **one
  `notification` per recipient** (the spec's one-row-per-recipient rule; no bulk table in v1). Audited
  to `platform_audit`.
- **Org → customer** — `POST /api/orgs/{orgId}/customers/{id}/notify` (STAFF) `{type|subject+body}` →
  email to `customer.email`. Reuses `NotificationService.notify`.

## 6. Email channel — Gmail SMTP (JavaMail)

- **`EmailSender`** interface + `SmtpEmailSender` impl (JavaMail / `jakarta.mail`), wired in
  `AppConfig`.
- **Config split (matches the repo):** non-secret → new `common/src/main/resources/mail.properties`
  (`mail.smtp.host=smtp.gmail.com`, `port=587`, `starttls=true`, `from`); **secret** Gmail **App
  Password** → env var `SMTP_PASSWORD` (like `JWT_SECRET`), plus `SMTP_USERNAME`.
- **⚠️ `.env.local` is not auto-loaded** — the app reads `.properties` + `System.getenv`, there is no
  dotenv. Source it before running: `set -a; source .env.local; set +a` then `mvn exec:java -pl api`
  (or add a dotenv step). Document in CLAUDE.md's env section.
- **Gmail expectations:** requires a 2FA **App Password** (not the account password); ~500 sends/day
  cap and From-rewrite — fine for dev/low volume, not production bulk (swap `SmtpEmailSender` for an
  ESP later without touching callers).

## 7. Delivery links — order-scoped magic tokens (refines customer-portal-future.md)

Transactional emails ("view your order", "invoice ready", unsubscribe) carry a **capability** token,
not a session. This is the `customer_magic_token` sketch in `customer-portal-future.md`, used at its
narrow tier:
- **`V45` — `customer_magic_token`** (`token_hash` SHA-256, `customer_id`, `org_id`, `purpose` ∈
  {`VIEW_ORDER`,`VIEW_INVOICE`,`UNSUBSCRIBE`}, `resource_id`, `expires_at`, single-use `consumed_at`).
  ✅ **shipped in Phase 2** (VIEW_ORDER only). Hashed at rest and TTL-bounded, reusing
  `RefreshTokenStore.hashToken`; the raw token lives only in the emailed URL. VIEW_ORDER is multi-use
  until expiry (`consumed_at` reserved for one-shot purposes like unsubscribe).
- Anonymous public route (like `/api/public/*`) validates the token, scopes to the one resource, no
  broader access. **Build this tier with the email channel** — it's what the notifications need.

**Customer-session magic links (portal) are a separate, later slice** — same table, a session-minting
`purpose`, but they are customer *authentication* (view all orders, manage profile, download all
invoices) and deserve their own design (short session, revocation, issuance rate-limiting), per the
recommendation in this plan's discussion. Not in the notifications scope.

## 8. Default routing (per `notifications.md` examples, customer=email-only)

| Event | Recipient | Default channels | Status |
|---|---|---|---|
| Order placed | Customer | email | ✅ |
| Order placed | Org staff | in_app + **push** | ✅ push since V96 (`stories/web_push_channel.md`): one `push` delivery per live device, frozen payload, the `token_version` rule silences it on every sign-out-everywhere path |
| Payment needs verification (manual InstaPay) | Org admin | in_app | |
| Payment verified | Customer | email | ✅ shipped as `ORDER_PAID` — fired at both `markPaid` sites in `PaymentService.reconcileAndCreate` (MATCHED + OVERPAID, so verify **and** orphan-resolve), not on UNDERPAID or the in-store sale; carries a fresh order-view magic link (`stories/notify_order_paid.md`) |
| Order shipped | Customer | email | ✅ shipped as `ORDER_SHIPPED` — fired in `FulfillmentService.ship` on `PENDING → SHIPPED`, **once per fulfillment** (a split order is two boxes, two messages); carries `carrier`/`tracking_number` only when recorded. Deliberately **not** named `OUT_FOR_DELIVERY`, which `stories/rider_self_delivery.md` reserves for the rider-pickup edge (`stories/order_lifecycle_notifications.md`) |
| Order delivered | Customer | email | only via the `REVIEW_REQUESTED` roll-up; a per-shipment "part of your order arrived" is not built |
| Order cancelled | Customer | email | ✅ shipped as `ORDER_CANCELLED` — fired in `OrderCancellationService.cancel` after the refund obligations exist, so a cancel refused by the approval gate rolls back and stays silent. Names the refund total as *being processed* (refunds are created PENDING — no money has moved); an order with no prepayment gets no money sentence at all |
| Payment received but short (UNDERPAID) | Customer | email | ✅ shipped as `PAYMENT_NEEDS_ATTENTION` — fired on the UNDERPAID reconcile branch beside the partial Payment, **per recorded partial** (each top-up restates the smaller remainder). Silent on OVERPAID (that order is PAID) and on DISPUTED |
| Invoice issued / reissued | Customer | email *(delivery only — does not harden the invoice)* | |
| Low-stock threshold crossed | Org staff | in_app + **push** | ✅ shipped as `LOW_STOCK` (V94, `stories/reorder_point.md`) on the crossing, fired at the two sale sites by `LowStockNotifier`; the push leg (V96) is the immediate reason the channel exists — the person who needs to know a shelf is empty is usually not looking at the dashboard |
| Refund executed | Customer | email | |
| Order expired (no payment) | Customer | email | |

Producer hooks live in the existing services (`SalesOrderService`, `PaymentService`,
`FulfillmentService`, `InvoiceService`, `RefundService`, `OrderExpiryService`, `InventoryService`),
each calling `notify(...)` inside its business txn.

## Phased build (each independently shippable)

1. **Phase 0 — Customer model:** ✅ **done** — no migration (per-org `(org_id,email)` unique exists
   since V15); upsert-by-email at placement already resolves/merges the customer, covered by
   `CustomerResolutionAtPlacementIT`. Notifications target the current `customer.email`; no order-level
   contact snapshot (see §0). *(Prerequisite; no external deps.)*
2. **Phase 1 — In-app core:** ✅ **done** — `V44` tables (all four, incl. the unused-until-Phase-2
   email subtype), `NotificationService` (producer + fan-out + feed + sweeper), the recurring
   `NotificationDeliverySweeperJob` (see §3 deviation note), feed endpoints (`GET
   /api/orgs/{orgId}/notifications?page&size&unread`, `POST .../{id}/read`, `POST .../{id}/dismiss` —
   own-only + platform-ADMIN-any via `?user_id=`), and one wired event: `ORDER_PLACED` → org staff
   in_app (fired inside the online-placement txn). Covered by `NotificationDeliveryIT` (produce →
   sweep → feed, fan-out, rollback-safety) + `CustomerResolutionAtPlacementIT#onlineOrder_notifiesOrgStaff`.
3. **Phase 2 — Email + order magic links:** ✅ **done** — `EmailSender`/`SmtpEmailSender` (Gmail SMTP,
   Angus Mail) + `LoggingEmailSender` fallback + `EmailSenderFactory` (`mail.properties` +
   `SMTP_USERNAME`/`SMTP_PASSWORD`), the `email` delivery path (producer writes the
   `notification_delivery_email` subtype; the recurring sweeper now drains **both** channels via
   `NotificationService.dispatchPendingEmail`, at-least-once with per-attempt retry→FAILED),
   `V45 customer_magic_token` + `MagicLinkService` (mint/resolve VIEW_ORDER), the anonymous
   `GET /api/public/orders/{token}` route, and the first customer email event: `ORDER_PLACED` →
   customer email carrying the order magic link (fired inside the online-placement txn). Covered by
   `CustomerEmailDeliveryIT` (produce → sweep → SENT/FAILED/retry) + `OrderMagicLinkIT` (mint/resolve,
   expiry, isolation). Story: `stories/deliver_customer_email_notifications.md`.
4. **Phase 3 — Preferences:** ✅ **done** — `V46 notification_preference` (opt-out; polymorphic
   subject × `type`/`ALL` × `channel`), resolution in `notify()` (exact → `ALL` → default-enabled; a
   fully-suppressed notification is recorded and finalized `DISPATCHED` with zero deliveries), staff
   `GET/PUT /api/orgs/{orgId}/notification-preferences` (own-only), and customer one-click
   unsubscribe (`UNSUBSCRIBE` magic token in every email → `POST|GET /api/public/unsubscribe/{token}`
   → email off for the org). Covered by `NotificationPreferenceIT`. Story:
   `stories/notification_preferences.md`.
5. **Phase 4 — Explicit sends:** admin→org and org→customer producers.
6. **Web Push (V96):** ✅ **done** — `stories/web_push_channel.md`. The first channel a **staff**
   recipient has besides the feed, and the one channel with **one delivery row per device** (V96
   relaxes `UNIQUE (notification_id, channel)` to `WHERE channel <> 'push'`). `push_subscription`
   keyed on the endpoint with `token_version_at_subscribe`; `notification_delivery_push` freezes the
   target + payload; RFC 8291/8188 encryption and RFC 8292 VAPID in-house on the JCE + jjwt
   (`common/crypto/WebPushEncryptor`, `common/security/VapidSigner`, pinned to RFC 8291 Appendix A);
   `NotificationService.dispatchPendingPush` on the shared claim → send → settle lease; 404/410
   prunes the device. `/api/me/push/config` + `/api/me/push-subscriptions`. Frontend pair:
   `frontst/stories/140_st_web_push.md`. Push accelerates; the feed row and its poll own delivery.
7. **Later:** customer push on the storefront (same table + `customer_id`, the portal's own
   `/api/portal/me/push-subscriptions`), SMS, ESP webhooks for true DELIVERED, org template
   customization, broadcast model.

> **A new type is free; a new channel is not.** `notification.type` is open TEXT, so the three
> lifecycle events above cost no migration. `notification_delivery.channel` is `TEXT + CHECK`
> (widened by V82 for `whatsapp` and by **V96 for `push`** — the two CHECKs, on
> `notification_delivery` and `notification_preference`, are independent and both must move), so
> **a new channel needs a migration**, a subtype
> table beside `notification_delivery_email`, and `channelsFor(recipient)` — today a constant
> two-line switch — becoming a per-org/per-customer resolution. The owner decision is a
> **per-merchant WABA** (each merchant connects their own number, brand and template approval).
> Its blocking prerequisite is that **no E.164 phone exists anywhere**: `customer.phone` is free
> text through `Text.normalizeNumeric`, which deliberately keeps `+` and other non-digits, so
> `01012345678` / `+20 100 123 4567` / `0100-123-4567` are all live values. Phone normalization +
> validation + backfill is its own slice, in front of the channel work — see
> `stories/order_lifecycle_notifications.md` §Slice B.

## Verification (per phase)
`docker-compose up -d` · regen jOOQ after each migration · `mvn install` · Testcontainers ITs
(Postgres for storage/producer, plus a fake `EmailSender` in Phase 2 to assert SENT/FAILED without a
real SMTP) · end-to-end: place an order → assert `notification` + `notification_delivery` rows,
worker flips to SENT/DISPATCHED, and (Phase 2) a real Gmail send in a manual smoke run.

## Decisions settled / open
- **Settled:** email = Gmail SMTP; customer recipients = email-only; on-the-fly customers **merge by
  `(org_id,email)`**; two-tier magic links (order-scoped now, customer-session later).
- **Open:** which staff events default to in_app vs email; unsubscribe granularity (per-type vs
  all-marketing — note this is transactional, so unsubscribe is mostly per-type); whether SUPPORT may
  send platform notifications or only ADMIN.
