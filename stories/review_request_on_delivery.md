# Slice: Review-request notification on delivery (`REVIEW_REQUESTED`)

> Roadmap item 1 (Tier 1) — [`docs/storefront-growth-roadmap.md`](../docs/storefront-growth-roadmap.md).
> Reviews (R1, [`storefront_reviews.md`](storefront_reviews.md)) are built but nothing *prompts*
> them — the only wired customer events are `ORDER_PLACED`, `ORDER_PAID`, `COMMENT_REPLIED`. This
> slice closes the loop: when an order finishes delivering, the customer gets one notification
> ("How was your order? Review your items") whose email deep-links to the branded order page.
> Feeds a tiny frontend touch (portal-feed label for the new type) — the review form already exists.

---

## Goal

The moment an online order reaches **FULFILLED** (its last shipment delivered), raise exactly **one**
`REVIEW_REQUESTED` notification to the customer — the in-app portal feed row **and** the
preference-honoring email leg, deep-linking to the order so they can rate each delivered item. Fire
**once per order**, never on re-delivery, never for the in-store sale, never when the order has no
emailable customer.

## Why the FULFILLED roll-up is the idempotency hinge (no new dedup query)

`FulfillmentService.markDelivered` already locks the order `FOR UPDATE` and calls `maybeRollUpOrder`,
which flips `FULFILLING → FULFILLED` **only when every order line's delivered qty is met**. Capturing
`wasFulfilling` before the roll-up and firing only on the `FULFILLING → FULFILLED|CLOSED` edge gives
**per-order-once** semantics for free: a multi-shipment order fires exactly when its final line
delivers; a partial delivery stays `FULFILLING` and doesn't fire; a re-deliver of an already-DELIVERED
fulfillment is a 409 before we reach here. No `existsBySource` query, no marker column, no migration.

## Design

- **`NotificationType.REVIEW_REQUESTED`** — new enum constant. Stored as `name()` in the open
  `notification.type` TEXT column, so **no migration**. Preferences accept any type name
  automatically (opt-out-able from day one; the org-wide email unsubscribe also suppresses it).
- **`NotificationTemplates`** — the `Rendered` record gains a `ctaLabel` (the email CTA text), since
  the hard-coded "View your order" anchor is wrong for a review email. The three existing cases keep
  `"View your order"`; `REVIEW_REQUESTED` renders title **"How was your order?"**, body **"Your order
  {order_number} was delivered. Tell other shoppers how it went — rate the items you received."**,
  cta **"Review your items"**. `emailHtml(body, linkUrl, ctaLabel, unsubscribeUrl)` takes the label.
- **`FulfillmentService`** gains `NotificationService` + `MagicLinkService` constructor deps (the
  exact `PaymentService`/ORDER_PAID precedent) + `AppConfig` wiring. Inside `markDelivered`'s txn,
  after `maybeRollUpOrder`:
  ```
  if (wasFulfilling && order is FULFILLED|CLOSED && customer has a non-blank email) {
      link = magicLinkService.issueOrderViewLink(txDsl, orgId, order.customerId, order.id, now)
      notificationService.notify(txDsl, orgId, customer(order.customerId), REVIEW_REQUESTED,
          {order_number}, "sales_order", order.id, link.absolute())
  }
  ```
  The email CTA points at the branded order-view page (no-login, consistent with the other
  customer emails); the in-app portal feed row deep-links via the `order_number` payload like
  `ORDER_PLACED`/`ORDER_PAID`.

## Scope

### In
`REVIEW_REQUESTED` enum + template case + `ctaLabel`; the `notify()` call gated on the FULFILLED edge
+ emailable customer in `markDelivered`; `FulfillmentService` deps + `AppConfig` wiring; unit +
integration tests.

### Out
Any change to the review-write path (exists). The in-store direct-DELIVERED sale (`createDelivered` /
`SalesOrderService.sell`) — excluded by construction (no FULFILLED roll-up runs there). A
"fire on first delivered shipment" variant (would need a new dedup query) — v1 is "review your
completed order". Per-item email content — the email points at the order; per-line "rate this" CTAs
already live on the portal order page.

## Guards / gotchas

- **No-customer orders** (PHONE, `customerId == null`): skip silently, like ORDER_PAID.
- **Email-less customer**: `notify`'s EMAIL leg throws `IllegalStateException` if the customer has no
  email, which would roll back the whole deliver txn. Guard the `notify` on
  `customer != null && customer.email` non-blank (the `customer` row is already loaded for invoicing).
- **Partial-then-cancel completion**: an order whose remaining lines are cancelled (rolled up by
  `OrderCancellationService`, not here) does **not** fire — acceptable v1 ("review your *completed*
  order").
- **Opt-out**: honored automatically via `channelsFor(CUSTOMER)` + `resolveEnabled`.

## Tests

- **Unit (`NotificationTemplatesTest`)**: `REVIEW_REQUESTED` renders the expected title/body/ctaLabel;
  `emailHtml` uses the supplied CTA label.
- **Integration (`FulfillmentServiceIT` / delivery IT)**:
  1. Single-shipment order → deliver → exactly one `REVIEW_REQUESTED` notification for the customer
     (in-app + email deliveries), payload `order_number`, source `sales_order`.
  2. Two-shipment order → deliver first (order stays FULFILLING) → **no** notification; deliver second
     (→ FULFILLED) → exactly one notification total.
  3. Customer opted out of email → in-app row still created, no email delivery.
  4. Order with `customerId == null` → no notification, delivery still succeeds.
  5. In-store sale (`sell`) → order CLOSED, **no** `REVIEW_REQUESTED`.

## Definition of done

Enum + template + `ctaLabel` land; `markDelivered` fires once on the FULFILLED edge for emailable
customers; `AppConfig` wires the two deps; all tests above green; `mvn test` clean. No migration.
