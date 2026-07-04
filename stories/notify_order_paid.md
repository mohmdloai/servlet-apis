# Slice: ORDER_PAID customer notification (G5 — the mission's third leg)

> The notification platform shipped in three phases
> ([`docs/notifications-plan.md`](../docs/notifications-plan.md)) but only one event is wired:
> `ORDER_PLACED`. The §8 routing table already reserves the next row — **"Payment verified →
> Customer → email"** — and the moment that matters to the customer is when their order flips to
> **PAID**: the money landed, the goods will ship. Today that flip
> (`PaymentService.reconcile`, MATCHED and OVERPAID branches) is silent; the customer who just
> InstaPay'd 1000.00 hears nothing until delivery.

---

## Goal

When an order flips `PENDING_PAYMENT → PAID`, the customer gets an email — "your payment was
received, order SO-… is confirmed" — carrying the same login-free order-view magic link the
placement email carries.

Done means: a `NotificationType.ORDER_PAID` is produced **inside the reconcile transaction** at
both `markPaid` sites (MATCHED exact cover, OVERPAID), which covers every route to PAID-by-money
for free — `POST /payment-transactions` (verify) **and** `POST /payment-transactions/{id}/resolve`
(orphan resolution reuses the reconcile path). A rolled-back reconcile (currency mismatch,
threshold denial) sends nothing; the sweeper delivers after commit, at-least-once, exactly like
`ORDER_PLACED`.

---

## Why this slice is small

The Phase 1–3 machinery does everything except name the event:

| Piece | Already existed | Added here |
|---|---|---|
| Event type | open `notification.type` TEXT column — adding a type needs **no migration** | `NotificationType.ORDER_PAID` enum constant |
| Producer API | `NotificationService.notify(txDsl, orgId, recipient, type, payload, refType, refId, link)` — txn-participating | two call sites in `PaymentService.reconcile` (after `updatePaymentState`, both `markPaid` branches) |
| Template | `NotificationTemplates.render` switch | one `case ORDER_PAID` (payload: `order_number`, `amount`, `currency`) |
| Customer email channel | Phase 2: email subtype, `EmailSender`, sweeper drains both channels, retries → FAILED | nothing |
| Magic link | `MagicLinkService.issueOrderViewLink(txDsl, orgId, customerId, orderId, now)` → `GET /api/public/orders/{token}` | reused — a **fresh** token per email, same as placement |
| Opt-out | Phase 3 preferences (`exact → ALL → default-enabled`) + unsubscribe footer link | nothing — `ORDER_PAID`/email is suppressible from day one |
| Wiring | `AppConfig` composition root | `PaymentService` gains `NotificationService` + `MagicLinkService` constructor deps |

---

## Semantics

- **Fires on**: the two `order.markPaid(...)` branches in `PaymentService.reconcile` — MATCHED
  and OVERPAID. Recipient: `NotificationRecipient.customer(order.getCustomerId())`, email-only
  (customers have no in-app seat). If the order has no customer (`customer_id` NULL — possible
  on PHONE orders), skip silently: there is nobody to mail.
- **Deliberately does NOT fire on**:
  - **UNDERPAID** — the order isn't paid; a partial-payment acknowledgement
    (`PAYMENT_RECEIVED`) is a separate future event, not this one diluted.
  - **The in-store sale** (`SalesOrderService.sell`'s `markPaid`) — the customer is at the
    counter; the receipt is the notification, and walk-ins often have no email. The event means
    "your *remote* payment arrived", not "an order somewhere became PAID".
- **Payload/template**: payload `{order_number, amount, currency}` where `amount` is the
  transaction amount just applied; rendered as title `Payment received for SO-…`, body `We
  received your payment of 1000.00 EGP for order SO-…. Your order is confirmed.` The email wraps
  it with the order-view CTA link and the standard unsubscribe footer (existing `emailHtml`).
  OVERPAID uses the same template — the excess-refund conversation is the admin's, not an
  automated email's.
- **Ordering/atomicity**: produced after the order state is persisted, inside `txDsl` — the
  notification exists iff the PAID flip commits. Delivery is post-commit via the recurring
  sweeper (`ORDER_SWEEPER_BACKGROUND_ENABLED` gate, `EMAIL_MAX_ATTEMPTS` retry budget) —
  at-least-once, same guarantees as `ORDER_PLACED`.
- **Preferences**: resolution `exact(ORDER_PAID, email) → ALL(email) → enabled`; a suppressed
  channel still records the notification, finalized `DISPATCHED` with zero deliveries (the
  platform's standing rule). The org-wide unsubscribe flip applies.

---

## Scope

### In
- `NotificationType.ORDER_PAID` + `NotificationTemplates` case.
- Producer calls in `PaymentService.reconcile` (both `markPaid` branches); constructor +
  `AppConfig` wiring (`NotificationService` and `MagicLinkService` are both constructed before
  `PaymentService` gains them — same pattern as `SalesOrderService`).
- Docs: CLAUDE.md (wired events list), `notifications-plan.md` §8/phase notes ("Payment
  verified" row → shipped as ORDER_PAID).

### Out (deferred)
- **`PAYMENT_RECEIVED` partial-payment ack** (UNDERPAID) — next event, own story.
- **Org-staff in-app on PAID** ("ready to fulfill") — the packing queue already lists PAID
  orders; add the ping only when the fulfillment screen asks for it.
- **In-store receipts by email** — different feature (receipt rendering), different trigger.
- **Org-branded email templates** — platform-wide deferral (notifications-plan).

---

## API contract

No new endpoints. Observable surface:

- `GET /api/orgs/{orgId}/notifications` — staff won't see it (customer-only recipient); the
  customer surface is the email itself.
- The email's CTA resolves anonymously at `GET /api/public/orders/{token}` (existing).
- `LoggingEmailSender` logs the send in dev/CI (no SMTP creds needed).

---

## Tests

`api/src/test/java/.../notification/OrderPaidNotificationIT.java` (TestContainers Postgres,
drives services; sweeper driven manually per existing notification ITs):
- exact-cover verify → one ORDER_PAID notification, customer email delivery PENDING → sweep →
  SENT/DISPATCHED; body carries amount + order number; link token resolves at the public
  endpoint to the customer view of *this* order.
- OVERPAID verify → fires once, same template.
- UNDERPAID verify → **no** ORDER_PAID; the later exact top-up fires exactly one.
- orphan `/resolve` onto an exact-cover order → fires (the reconcile-reuse path is covered).
- currency-mismatch 400 → whole txn rolls back, no notification row (rollback-safety pin).
- customer preference `ORDER_PAID`/email disabled → notification recorded, finalized
  `DISPATCHED`, zero deliveries; org-wide unsubscribe → same.
- in-store sale → no ORDER_PAID row.
- idempotent verify replay (200 path) → no second notification.
