# Slice: Public anonymous checkout (storefront places its own order)

> The write endpoint the storefront has been missing. [`public_storefront_read_api.md`](public_storefront_read_api.md)
> deliberately deferred checkout — *"placing an order needs `product_id` (internal), so it goes
> through the authenticated SalesOrder path … the public API never exposes the listing→product
> mapping."* This slice closes exactly that gap: an **anonymous** shopper turns a cart of public
> listing slugs into a `PENDING_PAYMENT` order, on the existing reservation / per-org-TTL machinery,
> with **no login and no staff**.
>
> Canonical contract & decisions: [`frontst/docs/storefront-commerce-epic.md`](../../frontst/docs/storefront-commerce-epic.md).
> Builds on: [`place_online_order.md`](place_online_order.md), [`reserve_stock_on_placement.md`](reserve_stock_on_placement.md),
> [`per_org_order_ttl.md`](per_org_order_ttl.md), [`storefront_org_profile.md`](storefront_org_profile.md) (payment
> instructions in the response), [`public_rate_limiting.md`](public_rate_limiting.md) (must ship with/before this).

---

## Goal

`POST /api/public/{orgSlug}/checkout` — an anonymous, rate-limited endpoint. Given a cart of
`{listing_slug, quantity}` and a customer form, it:

1. Resolves the org by slug (active-only), the same boundary as `StorefrontService`.
2. Resolves each `listing_slug` → the internal `product_id` **and** the published `sales_price`,
   PUBLISHED-only, **server-side** — the mapping is never exposed.
3. Places the order through the existing engine — customer upsert, per-line `unit_price` snapshot
   **from `sales_price`** (not `base_price`), totals, order-number claim, stock reservation, per-org
   TTL `expires_at`, and the order-view magic link — all in one transaction.
4. Returns a **customer-safe** order (no internal ids) plus the org's payment instructions and a
   `track_url` to the anonymous order view.

Done means: an unauthenticated `curl` with a cart of published slugs creates one `sales_order` in
`PENDING_PAYMENT` with reservations and `expires_at`, returns `SO-YYYY-NNNNN` + `grand_total` +
payment instructions, and a DRAFT/ARCHIVED slug or an over-cart quantity is rejected without ever
leaking a `product_id`.

---

## Why not just open the existing `POST /api/orgs/{orgId}/sales-orders`?

Three reasons that mirror why the **read** API isn't a BFF proxy:

- It requires an org role (`requireOrgAccess(orgId, STAFF)`), keys off the org **UUID**, and takes
  raw `product_id`s in the body — all wrong for an anonymous shopfront.
- It prices from `product.base_price` (the internal price). A shopper must be charged the
  **published `sales_price`** they saw.
- Its response is the full internal `SalesOrderResponse` (`product_id`, internal order `id`, staff
  notes). A public response must be whitelisted.

Putting checkout on the public surface makes "anonymous, published-only, priced-from-the-listing,
no-leak" a **property of the system**, not proxy code — exactly the argument
`public_storefront_read_api.md` makes for the read side.

---

## Design

- **`PublicCheckoutServlet`** at `/api/public/{orgSlug}/checkout` — a **new** servlet (the existing
  `PublicStorefrontServlet` is GET-only; POST there would be a 405). Mounted in
  `EmbeddedTomcatLauncher`, wired in `AppConfig`. Anonymous automatically (JWT filter bypasses
  `/api/public/*`, `JwtAuthFilter.java:65-74`). POST-only (else 405). Reads the required
  `Idempotency-Key` header. On success: `201` + `Cache-Control: no-store` (a mutation is never cached).
- **`StorefrontService.checkout(orgSlug, CheckoutInput, idempotencyKey)`** (new method on the existing
  read-model service, or a sibling `StorefrontCheckoutService`): resolves org, resolves slugs, delegates
  to `SalesOrderService`, maps to the public DTO. No transaction of its own — `SalesOrderService` owns it.
- **`SalesOrderService.placeStorefrontOrder(...)`** — a placement variant that snapshots `unit_price`
  from a caller-supplied per-line price (the listing `sales_price`) instead of `product.base_price`.
  Everything else (customer upsert, order number, reservation, TTL, magic link, idempotency) is the
  **same code path** as `placeOnlineOrder`. See §Placement variant.
- **Batch slug resolver** on `ProductListingRepository`: `resolveForCheckout(orgId, slugs, PUBLISHED)`
  → `List<CheckoutLineResolution(slug, productId, salesPrice, title)>` — one query, PUBLISHED-only, so
  N cart lines are one round-trip and a missing/non-published slug is detectable (absent from the result).
- **`STOREFRONT_ACTOR`** — a synthetic system `ActorContext` (mirror `OrderExpiryService.SWEEPER_ACTOR`)
  so the `inventory_log` rows from an anonymous placement are attributable to `SYSTEM`/"storefront".
- **Public DTOs**: `PublicCheckoutRequest`, `PublicOrderResponse` (customer-safe), `PublicShortage`.

---

## API contract

### Request
```
POST /api/public/{orgSlug}/checkout
Idempotency-Key: <opaque uuid>     # REQUIRED — missing/blank → 400
Content-Type: application/json
```
```json
{
  "customer": { "name": "Mona Hassan", "email": "mona@example.com",
                "phone": "+20 100 000 0000", "address": "12 Tahrir St, Cairo" },
  "lines":    [ { "listing_slug": "gopro-hero3", "quantity": 2 },
                { "listing_slug": "black-edition-case", "quantity": 1 } ],
  "notes":    "optional free text"
}
```

### Response — `201 Created` (customer-safe; **no** internal ids)
```json
{
  "order_number": "SO-2026-00042",
  "status": "PENDING_PAYMENT",
  "currency": "EGP",
  "subtotal": "1539.00", "tax_total": "0.00", "discount_total": "0.00", "grand_total": "1539.00",
  "placed_at": "2026-07-12T10:00:00Z",
  "expires_at": "2026-07-13T10:00:00Z",
  "lines": [
    { "title": "GoPro Hero3+ 12MP Black Edition", "quantity": 2, "unit_price": "759.50", "line_total": "1519.00" },
    { "title": "Black Edition Case", "quantity": 1, "unit_price": "20.00", "line_total": "20.00" }
  ],
  "payment_instructions": "Send via InstaPay to acme@instapay and put SO-2026-00042 in the note.",
  "track_url": "/api/public/orders/<rawToken>"
}
```
Rationale for the full line DTO: the confirmation screen needs `grand_total` (amount to transfer),
`expires_at` (payment countdown), `payment_instructions`, and the line snapshot — all in memory from
the INSERT, no second GET. `track_url` is the anonymous order-view magic link (already minted in-txn
during placement); returning it lets the confirmation link straight to tracking without waiting for
the email.

### Errors
| Status | Cause |
|---|---|
| `400` | empty `lines`, any `quantity ≤ 0`, malformed body, missing customer fields, **missing/blank `Idempotency-Key`** |
| `404` | unknown/inactive `orgSlug`; **any** `listing_slug` unknown or not `PUBLISHED` (opaque — "listing not available", never says which internal reason) |
| `409` | insufficient stock — `shortages:[{listing_slug, title, requested, available}]`, **re-keyed from `product_id`** (see §No-leak) |
| `429` | checkout rate-limit exceeded (`public_rate_limiting.md`) |
| `200`* | duplicate `Idempotency-Key` → the previously-created order verbatim (retry-safe, not an error) |

No `401`/`403` — there is no auth on this path. The security boundary is org-by-slug (active) +
PUBLISHED-only slug resolution + whitelisted response.

---

## The no-leak invariant (why the 409 needs re-keying)

The reservation engine throws `InsufficientStockException` carrying `List<Shortage>` where each
`Shortage = (UUID productId, int requested, int available)`. **`product_id` must not reach a public
client.** `PublicCheckoutServlet` (or the service) catches `InsufficientStockException` and maps each
`productId` back to the request's `listing_slug` + resolved `title` (the resolver already holds the
`slug ↔ productId ↔ title` triple), emitting `PublicShortage(listing_slug, title, requested,
available)`. Same for the 404 path: a resolver miss returns an **opaque** "listing not available" —
it never distinguishes "no such slug" from "exists but DRAFT/ARCHIVED" (that distinction is an
internal-state oracle).

---

## Placement variant — `placeStorefrontOrder`

The only functional difference from `placeOnlineOrder` is the **price source**. Two acceptable shapes;
pick the smaller diff:

- **Preferred:** extend the internal `OrderLineInput` (or add a `StorefrontLineInput(productId,
  quantity, unitPriceOverride)`) so the snapshot step uses the supplied `sales_price` instead of
  `product.base_price`, and expose `placeStorefrontOrder(orgId, CustomerInput, List<StorefrontLineInput>,
  idempotencyKey, notes, actor)`. `buildDraftOrder` branches on "override present → use it; else fetch
  base_price".
- The channel stays `ONLINE` (a storefront order is an online order); no new `channel` value.

Everything else is **identical and reused**: idempotency short-circuit (`findByIdempotencyKey`),
customer upsert (`upsertCustomerByEmail`), order-number claim, `org.order_ttl_minutes` read in-txn →
`expires_at`, `reservationService.reserveForOrder(...)`, staff `ORDER_PLACED` notification, and
`magicLinkService.issueOrderViewLink(...)` (→ the `track_url` and the customer email). Idempotent
replay skips reservation, exactly as today.

> **Why snapshot at checkout, not trust the client:** the cart carries a `unit_price` the shopper saw,
> but the server re-reads `sales_price` from the PUBLISHED listing at POST time and prices from **that**
> — a stale cart (price changed since add-to-cart) is silently repriced to the current published price.
> The response echoes the authoritative `unit_price`/`line_total` so the confirmation shows the true
> charge. (A future slice may 409 on a price delta beyond a tolerance; v1 reprices silently.)

---

## Scope

### In
- New `PublicCheckoutServlet` (`/api/public/{orgSlug}/checkout`, POST-only), mounted + wired.
- `StorefrontService.checkout(...)` (resolve org → resolve slugs → place → map public DTO), with
  `InsufficientStockException` → `PublicShortage` re-keying and opaque 404 on resolver miss.
- `ProductListingRepository.resolveForCheckout(orgId, Collection<slug>, PUBLISHED)` (+ Impl) — one
  query returning `(slug, productId, salesPrice, title)` per PUBLISHED match.
- `SalesOrderService.placeStorefrontOrder(...)` + the `StorefrontLineInput`/override plumbing.
- `STOREFRONT_ACTOR` system `ActorContext`.
- Public DTOs: `PublicCheckoutRequest`, `PublicOrderResponse`, `PublicShortage`; the servlet reads
  `Idempotency-Key`, returns `no-store`.
- `payment_instructions` in the response, read from the org (via `storefront_org_profile.md`'s new
  column). If B1 hasn't landed, degrade to a null/omitted field (soft dependency).

### Out (deferred)
- **Cash on Delivery** — a new payment path (order confirmed without prepayment); a later slice.
- **Server-side cart** — the cart is client-side; this endpoint is stateless per POST.
- **Price-delta 409** — v1 silently reprices a stale cart to current `sales_price`.
- **Coupon / discount** — `discount_total` stays `0.00`.
- **Customer self-submission of the InstaPay claim** — still staff-verified (`accept_online_payment.md`);
  no customer auth beyond the order-view token.
- **Per-listing purchase limits / min-order** — not in v1.

---

## Concurrency

Identical to `reserve_stock_on_placement.md`: the reservation locks each `inventory` row
`FOR UPDATE ORDER BY product_id ASC` inside the placement txn. Two anonymous shoppers racing for the
last N units → exactly one `201`, the other `409` with `available = 0`. Idempotency is the global
`(org_id, idempotency_key)` unique, so a double-submit (network retry, double-tap) creates one order.

---

## Authorization

None — anonymous by design. Boundaries: org-by-slug **active-only** (`OrgRepository.findBySlug(...).filter(isActive)`),
PUBLISHED-only slug resolution, rate limiting (`public_rate_limiting.md`), whitelisted response, and the
internal `product_id` never crossing the boundary (request in slugs, errors re-keyed to slugs).

---

## File layout

| Module | New / changed |
|---|---|
| `domain` | **Changed**: `ProductListingRepository` — add `resolveForCheckout(orgId, slugs, status)`. New record `CheckoutLineResolution(slug, productId, salesPrice, title)`. |
| `repository` | **Changed**: `ProductListingRepositoryImpl` — implement `resolveForCheckout` (one `WHERE org_id=? AND status='PUBLISHED' AND slug IN (…)`). |
| `service` | **Changed**: `SalesOrderService` — `placeStorefrontOrder(...)` + `StorefrontLineInput`/override in `buildDraftOrder`. **Changed**: `StorefrontService` — `checkout(...)` + shortage re-keying. New: `STOREFRONT_ACTOR`. |
| `api` | **New**: `PublicCheckoutServlet`, `PublicCheckoutRequest`, `PublicOrderResponse`, `PublicShortage`, and their mapper. **Changed**: `EmbeddedTomcatLauncher` (mount `/api/public/{orgSlug}/checkout` — more specific than `PublicStorefrontServlet` if needed), `AppConfig` (wire). |

No migration in this slice (org branding/payment columns land in `storefront_org_profile.md`).

---

## Acceptance criteria

- [ ] `POST /api/public/{orgSlug}/checkout` with a cart of PUBLISHED slugs + valid customer + a
      `Idempotency-Key` → `201`, `status="PENDING_PAYMENT"`, `order_number` matching `^SO-\d{4}-\d{5}$`,
      `expires_at = placed_at + org.order_ttl_minutes`. One `sales_order` (`ONLINE`), one
      `sales_order_line` + one `inventory_reservation` per line (`ACTIVE`), `inventory.reserved_qty`
      bumped. Customer upserted on `(org_id, email)`.
- [ ] **Priced from the listing:** each `unit_price` equals the resolved `product_listing.sales_price`,
      **not** `product.base_price`. (Seed a product whose `base_price ≠ sales_price` and assert.)
- [ ] `grand_total == Σ line_total` within `NUMERIC(14,2)`.
- [ ] A `listing_slug` that is DRAFT, ARCHIVED, or nonexistent → `404` with an **opaque** message; no
      response field distinguishes the three; **no `product_id`** anywhere in the body.
- [ ] Insufficient stock on any line → `409` with `shortages[{listing_slug, title, requested,
      available}]` for **every** short line; **no `product_id`** in the body.
- [ ] Missing/blank `Idempotency-Key` → `400`. Empty `lines` → `400`. `quantity ≤ 0` → `400`.
- [ ] Duplicate `Idempotency-Key` (two concurrent identical POSTs) → exactly one `sales_order`; both
      responses carry the same `order_number`; the second is `200`; **no** second reservation.
- [ ] Unknown/inactive `orgSlug` → `404`.
- [ ] The response contains **no** `product_id`, `org_id`, internal order `id`, `customer_id`, or
      timestamps other than `placed_at`/`expires_at`. (Assert by scanning the JSON.)
- [ ] `track_url` resolves anonymously: `GET /api/public/orders/{token}` (the returned path) → `200`
      with the same `order_number` (the existing `PublicOrderServlet` path).
- [ ] `payment_instructions` echoes the org's configured InstaPay instructions (or is omitted when B1
      hasn't set them).
- [ ] The order-placed side effects fire once: staff `ORDER_PLACED` in-app notification created; a
      customer `ORDER_PLACED` email queued with the magic link (same as `placeOnlineOrder`).

---

## Tests

`api/src/test/java/.../catalog/PublicCheckoutIT.java` (TestContainers):
- happy path (published cart → 201, PENDING_PAYMENT, reservations, `expires_at` per org TTL);
- **repricing** (base_price ≠ sales_price → charged sales_price);
- DRAFT/ARCHIVED/unknown slug → opaque 404, no `product_id` in body;
- insufficient stock → 409 with slug-keyed shortages, no `product_id`;
- idempotent replay (same key → one order, second call 200, no second reservation);
- concurrency (two placements for the last unit → one 201, one 409 `available:0`);
- inactive org slug → 404;
- JSON no-leak scan (regex asserts absence of `product_id`/`org_id`/internal `id`);
- `track_url` round-trips through `PublicOrderServlet`.

Regression: `PlaceOnlineOrderIT`, `ReservationIT`, `OrderExpiryServiceIT`, `StorefrontIT` stay green
(the shared placement path is unchanged for the authenticated route).

Verified live through Tomcat: anonymous `curl -X POST …/checkout` (no cookie) → 201; a second identical
POST with the same `Idempotency-Key` → 200 same order; POST a DRAFT slug → 404; over-cart quantity →
409 with slug-keyed shortages.

---

## What this unblocks

| Next | Depends on this |
|---|---|
| **Frontend story 29 (checkout + confirmation)** | the whole public checkout contract + `track_url` + `payment_instructions` |
| **Cash on Delivery** (later) | a second payment mode branching off this same placement path |
| **Storefront conversion analytics** (later) | storefront-channel orders now exist as first-class `sales_order` rows |
