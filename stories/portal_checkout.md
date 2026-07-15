# Slice P6: Checkout uses the logged-in customer (authenticated checkout)

> The gap the portal exposed: a **logged-in** customer still checks out through the **anonymous** path
> (`POST /api/public/{orgSlug}/checkout`), retyping their name/email/phone/address every time, and the
> order is attributed only by the *typed* email — the session is ignored. P4 shipped a saved-address book
> but explicitly left "use it at checkout" out; this is that connective slice. It adds an **authenticated
> checkout** that binds the order to the session customer (verified email, no re-typing) and consumes a
> saved address.
>
> Canonical: [`frontst/docs/customer-portal-epic.md`](../../frontst/docs/customer-portal-epic.md).
> Depends on **P1** (session), **P4** (`customer_address`). Reuses the anonymous checkout core
> (`StorefrontService.checkout` → `SalesOrderService.placeStorefrontOrder`). Feeds frontend story 38.

---

## Goal
```
POST /api/portal/checkout   {lines:[{listing_slug, quantity}], notes?, address_id? | address?, save_address?}
                            → 201 PENDING_PAYMENT (PublicOrderResponse + track_url) / 200 idempotent replay
```
The customer is **the session** (`ctx.customerId`, `org_id` from the JWT) — never a body email. The
delivery address is a **saved `address_id`** (ownership-checked) or a typed `address`; `save_address:true`
persists a typed one to the book. `Idempotency-Key` required (as anon). The guest anonymous checkout is
**unchanged**.

## Why an authenticated endpoint (not cosmetic prefill on the anon path)
Prefilling the anonymous form would still send a **client-editable email**, so the order would attribute by
typed string, not identity — a logged-in customer could (accidentally or maliciously) place an order under a
different email, and "My orders" could miss it. Binding the order to `ctx.customerId` server-side makes it
**theirs by construction**: it always appears in their history, the email is the verified session email, and
saved addresses become usable. It also keeps the two planes clean — the anon `/api/public/.../checkout`
stays cookie-less for guests; the authed variant lives on `/api/portal/*`.

## Design
- **Placement variant:** `SalesOrderService.placeStorefrontOrderForCustomer(orgId, customerId,
  deliverySnapshot, lines, idempotencyKey, notes, actor)` — loads the `customer` by `(orgId, customerId)`
  and calls the shared `placeReserved(...)` **directly with the known customer**, bypassing
  `resolveCustomer`'s email-upsert (nothing to upsert — the identity is fixed). Everything else (slug→
  product_id + sales_price resolution, reservation, per-org TTL, `ORDER_PLACED` notifications, the
  order-view magic link / `track_url`) is inherited unchanged.
- **Delivery snapshot:** if `address_id` → load that `customer_address` (P4), assert it belongs to the
  session customer (foreign/unknown → opaque 404), snapshot its `recipient/phone/address` onto the order
  (orders keep their own frozen address; the book row is never FK'd). If typed `address` → use it (and, when
  `save_address`, insert a new book row in the same txn). Exactly one of `address_id`/`address` (400 else).
- **Service/API:** `CustomerPortalService.checkout(ctx, input, idempotencyKey)` → the variant above;
  `PortalServlet` `POST /checkout` (`X-Portal-Request` + CSRF Origin check + `Idempotency-Key`), returns
  the same customer-safe `PublicOrderResponse` (+ `track_url` to the portal order page). Same error surface
  as anon: unknown/unpublished slug → opaque 404, shortage → 409 slug-keyed `shortages`, over-limit → 429.
- **Rate limiting:** a `rl:portal-checkout` bucket (mirror `rl:pub-checkout`), on `/api/portal/checkout`.

## Scope
**In:** the authed `POST /api/portal/checkout`; the known-customer placement variant; saved-address
resolution + optional save; rate-limit bucket. **Out:** changing the anonymous guest checkout (untouched);
multiple addresses *per order* (one delivery address); payment-method changes; editing the profile mid-
checkout (that's the profile page).

## Authorization
Valid **customer** session; the order is bound to `ctx.customerId`; any `address_id` must be the caller's
own (opaque 404 otherwise). Guests continue to use the anonymous path — this endpoint is portal-only.

## Acceptance criteria
1. A logged-in checkout places a PENDING_PAYMENT order whose `customer_id == session customer` (verified
   even if a different email were somehow supplied — no body email is read); it appears in `GET
   /api/portal/orders`.
2. `address_id` uses the owned saved address's snapshot; a foreign/unknown `address_id` → 404; a typed
   `address` works; `save_address:true` adds exactly one book row; neither/both address inputs → 400.
3. Same commerce semantics as anon: unknown slug → opaque 404, shortage → 409 slug-keyed, over-limit → 429;
   `Idempotency-Key` required and replay returns the same order (200).
4. Response is the customer-safe body + a `track_url` to the portal order page; no internal id / product_id
   leaks.
5. The anonymous `POST /api/public/{orgSlug}/checkout` is byte-for-byte unchanged (regression).

## Tests
`PortalCheckoutIT`: AC 1 (order bound to session + shows in /orders), AC 2 (saved-address ownership +
save + XOR validation), AC 3 (shortage 409 / unknown-slug 404 / idempotent replay), AC 4 (no-leak scan +
track_url), AC 5 (anon-checkout regression via the existing `PublicCheckoutIT`). Reuses the P1 session +
P4 address fixtures.

## What this unblocks
Frontend story 38 (the session-aware checkout: prefill + saved-address picker + authed submit). Removes the
"retype everything even though I'm logged in" friction the portal otherwise creates.
