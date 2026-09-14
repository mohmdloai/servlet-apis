# Slice: A signed-in customer pays by card (portal pay + a return target on the intent)

> Follow-up to [`paymob_card_checkout.md`](paymob_card_checkout.md) (V99), which named this as
> *"a thin follow-up, not a redesign"*. **Branch `194_feat/paymob-portal-pay` off `master`, no
> migration.** Frontend pair: `frontst/stories/161_st_checkout_pay_by_card.md` — which also moves
> the card choice into the checkout form for guests; that half needs nothing from this slice.

---

## Goal

A customer who is signed in to the storefront's account plane can pay a pending order by card from
their own order page, and Paymob sends them back **to that page**, not to a magic-link tracker
that drops them out of their account.

```
POST /api/portal/orders/{orderNumber}/pay   → { checkout_url, expires_at }   (customer session is the capability)
```

Done means: the portal route answers exactly what the public route answers for the same order,
the intention's `redirection_url` is the portal order page, and the webhook/poller settle it with
no change at all.

---

## Why a second door and not a shared one

The public route's capability is the order-view magic token — a signed-in customer holds one only
in an email. The portal plane's capability is the session (`CustomerAuthFilter`, its own signing
key, `aud=customer`), and every portal order write already resolves ownership through
`CustomerPortalService.getOrder(orgId, customerId, orderNumber)` — a foreign or unknown number is
the same opaque 404. `…/payment-claim` and `…/payment-proof/presign` are exactly this twin for the
InstaPay path; `…/pay` is the third.

The one thing the two doors must not share is the **return page**. Today `PaymentIntentService
.pay` builds `redirection_url` from the order-view token. A signed-in customer sent back to
`/{locale}/{slug}/orders/{token}` lands on the branded tracker with no account chrome and no way
back to their orders — it works, and it is the wrong page.

---

## Scope

### In
- `PortalServlet`: `POST /orders/{orderNumber}/pay` — `requirePrincipal`, `getOrder` for ownership,
  then `paymentIntentService.pay(...)` with a **portal return target**. `Cache-Control: private,
  no-store`. The portal CSRF rules apply as on every portal write (`X-Portal-Request: 1`,
  `Origin`/`Referer` against `CORS_ALLOWED_ORIGINS`).
- `PaymentIntentService.pay(UUID orgId, UUID orderId, UUID customerId, ReturnTarget target)`:
  `ReturnTarget.publicTracker(rawToken)` → `/{locale}/{slug}/orders/{token}` (today's URL, unchanged
  byte for byte); `ReturnTarget.portalOrder(orderNumber)` → `/{locale}/{slug}/account/orders/
  {orderNumber}`. Locale resolution is the same `Locales.resolve(customer, org default)`.
- Rate limiting: nothing to add — `RateLimitFilter` already routes any `POST …/pay` on both planes
  to the strict `rl:payment-claim` bucket, matched ahead of the `/api/portal/` catch-all.

### Out
- Any change to the intent model, the webhook, or the poller. A `payment_intent` does not record
  which door minted it; the return URL is Paymob's, baked into the intention.
- A method choice at checkout on the backend side — placement is unchanged; the frontend chains
  `checkout` then `pay` (161).

---

## Behaviour to pin

- **Reuse crosses planes.** The reuse rule is *live PENDING intent for the same order and amount*.
  A customer who minted from the emailed tracker and then taps pay on their account page inside the
  TTL gets the **same** `checkout_url` — whose `redirection_url` is the tracker. Both pages carry
  the return watcher (159/161), so the payment still settles and the customer still sees it; they
  merely land on the other page. Documented, not "fixed": minting a second intention to change a
  return address would be a second intention, which the reuse rule exists to avoid.
- **Ownership before anything.** An order number that is not the session customer's → opaque 404,
  before any Paymob call. The intent service is never handed an order it did not resolve through
  the session.
- Same 409s and 502 as the public route (not `PENDING_PAYMENT`, nothing left, no card channel,
  Paymob refused), same bodies.

---

## Tests

- `PortalServletPayTest` (unit, mocked services): no session → 401; a foreign order → 404 with no
  intent-service call; success → 200 with the body; CSRF header missing → the portal's 403.
- `PaymobPayIT`: `ReturnTarget.portalOrder` → the intention's `redirection_url` is
  `{PUBLIC_BASE_URL}/{locale}/{slug}/account/orders/{orderNumber}`; `publicTracker` → unchanged;
  the cross-plane reuse case above (same URL back).

## Acceptance criteria

- [ ] `POST /api/portal/orders/{n}/pay` with a valid customer session on an owned `PENDING_PAYMENT`
      order → `200 {checkout_url, expires_at}`; one `payment_intent` row.
- [ ] The intention Paymob receives carries `redirection_url = …/account/orders/{n}` for the
      portal door and `…/orders/{token}` for the public door; nothing else in the request differs.
- [ ] Another customer's order number, or an unknown one → `404`, and Paymob is never called.
- [ ] No session → `401`; a portal write without `X-Portal-Request` → the plane's `403`.
- [ ] A second tap inside the TTL, from either door, returns the same `checkout_url`.
- [ ] A callback for an intent minted from the portal door settles exactly as one minted from the
      public door (the webhook code path is untouched — pinned by re-running the existing
      `PaymobWebhookIT` happy path against a portal-minted intent).
