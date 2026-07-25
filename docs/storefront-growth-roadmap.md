# Storefront Growth Roadmap — ordered suggestions

> A prioritized backlog of what to build next on the **public storefront + customer portal**,
> written after the storefront-commerce (24–29), customization (C1–C4), customer-portal (P1–P6), and
> reviews/comments (R1–R2) epics all shipped. Grounded in the same conventions as the rest of the
> codebase (`domain → repository → service → api`, jOOQ, manual DI, JobRunr worker, per-`org_id`
> tenancy, `.properties` + env config, vertical slices per [`frontend-feature-delivery`]) and the
> honesty posture the customization epic locked (no fake compare-at prices). This is a planning doc,
> not a spec — each item names its slice shape; the real spec is written when the item is pulled.
>
> **Next migration number: V73.** (This note read "V62" while the roadmap was being written; V62–V72
> have since landed — V68 = `org_tax_shipping_config` (item 5), V69 = `customer_wishlist` (item 3),
> V70 = `product_variant` (item 6), V71 = `collection` (item 8), V72 = `coupon` (item 9). The
> intervening ones came from work outside this roadmap: V62 unicode normalization, V63–V64 content
> localization, V65 email verification, V66 read-path indexes, V67 the payment-proof key (item 2).)

## Status (2026-07-25) — what has since been built

This section was added as the items were pulled; **the analysis and ordering below are left exactly as
written**, so the plan can still be read against what actually happened. Items 1–9 have all shipped
(each as its own spec, since this doc names slice *shapes*, not specs):

| # | Item | Status | Shipped as |
|---|------|--------|-----------|
| 1 | Review-request email on delivery | ✅ shipped | `stories/review_request_on_delivery.md` |
| 2 | Shopper payment-proof upload | ✅ shipped | `stories/shopper_payment_proof_claim.md` (V67) |
| 3 | Wishlist / favorites | ✅ shipped | `stories/customer_wishlist.md` (V69) |
| 4 | Best-sellers strip (computed) | ✅ shipped | `stories/storefront_best_sellers.md` |
| 5 | Tax + shipping + currency config | ✅ shipped | `stories/org_tax_shipping_config.md` (V68) |
| 6 | Product variants | ✅ shipped | `stories/catalog_variants_model.md` + `catalog_variants_commerce.md` (V70) |
| 7 | Attribute facets | ✅ shipped | `stories/storefront_attribute_facets.md` (no migration — rode V70's index) |
| 8 | Named collections | ✅ shipped | `stories/storefront_collections.md` (V71) |
| 9 | Honest coupon codes | ✅ shipped | `stories/honest_coupons.md` (V72) |
| 10 | Algorithmic recommendations | ⬜ **the sole remaining item** | — |

Two notes on how the plan held up:

- **Item 9's owner call was given on 2026-07-24: approved in principle** — a merchant-issued code that
  reduces the *real* total, shown transparently at checkout. The honesty rule (constraint 1 below) was
  not relaxed to do it: there is still no compare-at price, no was–now, no % OFF badge on a listing and
  no countdown, and the discount is displayed only on the checkout and order surfaces.
- **Item 8 shipped beside `featured_sort`, not as a migration of it** — the roadmap's "generalized from
  one implicit collection to N named ones" turned out to cost a public-wire break (`?featured=true` is
  deployed) for no merchant-visible gain, so featured stays as-is and collections sit next to it. The
  reasoning is recorded in the slice.

## Where the storefront stands (the baseline these suggestions build on)

A shopper can already: browse a branded bilingual (AR/EN, full RTL) home with merchant banners +
curated/newest/category strips, open a product with gallery + price + stock + **verified-purchase
reviews** + **merchant-answered Q&A**, search (text + price + category + sort), add to a local cart,
check out as a guest **or** as a logged-in customer (passwordless email-OTP portal: order history,
invoices+PDF, saved addresses, one-click reorder, notifications feed, authed checkout), and track the
order by magic link. Payment is **manual InstaPay prepay** (or in-store cash); staff reconcile it on
the admin plane.

So identity and engagement — the expensive, defining parts — are done. What remains is **catalog
depth** and **payment/fulfillment friction**, plus a few automatic-discovery surfaces. The items
below are ordered by leverage: the early ones are small because they ride machinery that already
exists (portal, notifications, delivered-fulfillment, presigned uploads, reservations, confirmed
order lines).

## Two standing constraints (don't relitigate)

1. **The honesty rule holds.** No compare-at / was–now / % OFF / countdown pricing anywhere — it is
   unrepresentable in the schema and stays that way. Genuine merchant-granted discounts (a coupon
   that reduces the *real* total) are a *separate* question flagged in item 8; fake urgency is not.
2. **Payment stays manual.** No card gateway is assumed. The friction fixes below make the *manual*
   InstaPay loop faster rather than replacing it.

---

## Tier 1 — small builds that activate what's already shipped

These are the highest leverage right now: each is a slice or two, each rides existing
infrastructure, and together they close the *friction* gaps (dead reviews, payment-reconciliation
lag, no shipping cost) that hurt conversion more than any missing catalog feature.

### 1. Review-request email after delivery — *(S · 1 backend slice, mostly a new event)*  ✅ shipped

**What.** When a fulfillment reaches DELIVERED, send the customer one notification ("How was your
order? Review your items") with a portal deep-link to the review form.

**Why now.** Reviews (R1) are built but nothing *prompts* them — the only wired customer events are
`ORDER_PLACED`, `ORDER_PAID`, `COMMENT_REPLIED`. Review volume is the entire point of R1; a request
email is what turns it from *built* into *used*.

**Rides.** `NotificationService.notify` (in-app + email, preference-honoring), the existing
`ORDER_*` producers, the DELIVERED transition in `FulfillmentService`, the portal deep-link pattern.

**Shape.** A new `NotificationType` (`REVIEW_REQUESTED`) + template, raised inside the deliver txn
(idempotent per order/customer so a re-deliver doesn't double-send); opt-out honored. No migration if
it reuses the notification supertable. Frontend: none beyond the email body (the review form exists).

### 2. Shopper payment-proof upload — *(S–M · 1 backend slice + 1 portal/storefront story)*  ✅ shipped

**What.** After checkout, let the shopper attach an InstaPay **reference + screenshot** to their
order (on `/orders/{token}` for guests, or the portal order page). It lands in the reconciliation
worklist as a pending `payment_transaction` pre-filled with the reference and proof image.

**Why now.** The single biggest conversion friction is the *wait*: the shopper transfers money, then
nothing happens until a staff member manually hunts for the transfer and reconciles it. Letting the
shopper hand the reference directly collapses that lag and reduces "did my payment go through?"
support load.

**Rides.** The presign machinery (logo/banner/OG image uploads), `PaymentTransactionService`'s
record path, the reconciliation worklist, the order magic-link (`customer_magic_token` capability).

**Fit.** This *strengthens* the manual model instead of fighting it — the human still verifies, but
starts from the shopper's own evidence.

**Shape.** A public/portal `POST …/orders/{token}/payment-claim {reference, proof_object_key?}` that
creates an unverified `payment_transaction` (org-scoped, image key prefix-guarded like every asset);
staff verify as today. Migration only if proof keys need a new column; otherwise reuses existing
transaction fields.

### 3. Wishlist / favorites — *(S · 1 backend slice + 1 storefront/portal story)*  ✅ shipped

**What.** A heart on the product card / buy box; an `/account/(authed)/wishlist` page. Guests get a
local (cookie) list that merges into their account on login.

**Why now.** Cheap *because* the portal exists, high delight, and it becomes the hook for later
re-marketing (back-in-stock, "still interested?"). It's the lightest way to add the shopper-retention
surface Souq leans on.

**Rides.** The portal auth plane + session, the `customer` row, the listing read model, the local-cart
merge-on-login pattern already used for the cart.

**Shape.** `customer_wishlist(customer_id, product_listing_id, created_at)` (migration, UNIQUE pair);
portal `GET/POST/DELETE /api/portal/wishlist`; guest list in a cookie merged on `verify-code`. No-leak:
the wishlist read returns whitelisted listing rows, same as the catalog.

### 4. Best-sellers strip (honest, computed) — *(S · read-only, no new customer data)*  ✅ shipped

**What.** A home strip and an optional catalog sort: listings ranked by **actual confirmed units
sold** over a rolling window (e.g. 30/90 days), PUBLISHED-only.

**Why now.** This is the cheap, honest half of the "Recommendations" gap. You already store confirmed
`sales_order_line` rows — best-sellers is an aggregate query, no behavior tracking, no personal data,
no fake ranking. It gives the storefront an *automatic* discovery surface (today only the merchant's
manual "Featured" strip exists) without touching the honesty rule.

**Rides.** The reporting read model, `product_listing` + confirmed order lines, the existing
home-strip widget (identical to Featured/Newest) and the public listings read.

**Shape.** A materialized/aggregated read (`ListingSort.BEST_SELLING` + a bounded window) exposed as
`?sort=best_selling` and a home strip; cache it like the other `max-age=60` reads. Deliberately *not*
personalized and *not* co-purchase — those are Tier 3.

### 5. Config-complete tax + shipping fee + currency — *(S each · finish scaffolding)*  ✅ shipped

**What.** Make the money fields that are already threaded through orders/invoices but pinned to `0`
/ `"EGP"` into per-org settings: a configurable tax rate, a flat or per-zone **shipping fee** added as
an order line, and a single configurable store currency (not multi-currency).

**Why now.** `SalesOrder.taxTotal` / `discountTotal` / `currency` and per-line `taxRate`/`lineTax`
already compute — they're just hardcoded to zero/EGP in `SalesOrderService`. Turning them on makes the
money math *real* and is a prerequisite for shipping. Low effort, no new architecture.

**Rides.** The existing order/invoice arithmetic, the org branding/settings surface (V52), the
admin Settings → Storefront + Business-profile forms.

**Shape.** Org columns for `tax_rate` and a `shipping_fee` (or a small `shipping_zone` table for
per-area rates); checkout adds a shipping line and applies tax; the storefront shows both in the
order summary. Currency: read from the org instead of the `CURRENCY_EGP` constant.

---

## Tier 2 — the real catalog-depth gaps (bigger, do after Tier 1)

### 6. Product variants — *(L · an epic, not a slice)*  ✅ shipped

**What.** One product sold in selectable options — a phone in 3 storages, a shirt in 4 sizes — each
option with its own price and stock.

**Why.** The single biggest thing missing for a *believable* catalog. Today it's one listing per
product with a single `sales_price`; you literally cannot model a variant SKU.

**Rides / cost.** It touches catalog → listing → **inventory reservation** → cart → checkout → order
line. The reservation engine and the no-leak whitelist already key on `product_id`, so a variant can
map to its own stock-tracked identity — that's the seam to design around. Scope it as its own epic with
a dedicated architecture doc (extend `catalog-architecture.md`); don't try to slice it thin.

### 7. Attribute facets at search — *(M–L · rides on #6)*  ✅ shipped

**What.** "1386 results — filter by Color / Storage / Brand / rating." Structured, multi-select facets
beyond the current text + price + category + sort.

**Why after variants.** Facets need a structured attribute model, which naturally *is* the
variant/attribute model from #6. Built alone it has nothing to filter. Rating facet can piggyback on
the `rating_avg` already on listing rows.

**Shape.** A product-attribute model + faceted query (counts per facet value), extending the B3
search read (`storefront_search_and_filters.md`). Keep the no-silent-coercion 400 discipline of the
existing filter parsing.

---

## Tier 3 — merchant growth levers (respect the honesty rule)

### 8. Named collections — *(M)*  ✅ shipped (V71)

**What.** Generalize the single "Featured" strip into merchant-defined **collections** ("Best of
Best", "Ramadan picks") with their own landing pages and slugs. This is exactly Souq's category-circle
merchandising — and it's honest (curation, not fake deals).

**Rides.** The featured-curation machinery (`featured_sort`, the set-replace PUT, the home strip) —
generalized from one implicit collection to N named ones.

### 9. Honest coupon codes — *(M · needs a product decision first)*  ✅ shipped (V72 — owner call given 2026-07-24: approved)

**What.** A merchant-issued code that reduces the **real** total (filling the already-present
`discountTotal`), shown transparently at checkout — not a fake compare-at price.

**Why it's flagged, not scheduled.** It brushes against the honesty rule. The rule forbids *fake
urgency / fabricated original prices*; a genuine merchant-granted discount is arguably a different
thing. **This one needs an explicit owner call** before it's specced. If approved, it rides the
existing `discountTotal` field and order arithmetic.

### 10. Algorithmic recommendations — *("also bought" / personalized) — (L · later)*  ⬜ not started — the sole remaining item

**What.** "Customers who bought X also bought Y" (co-purchase from order lines) and, further out,
per-shopper personalized rows (needs the portal identity + view/purchase history).

**Why last.** Highest complexity, needs behavioral data and careful privacy framing. Best-sellers
(item 4) delivers most of the *automatic-discovery* value at a fraction of the cost; do this only once
the catalog (variants/facets) is deep enough to make recommendations meaningful.

---

## Recommended sequence

**Do Tier 1 first, in this order:** 1 (review-request email) → 2 (payment-proof upload) → 5
(tax/shipping/currency) → 4 (best-sellers) → 3 (wishlist). These are all small, each compounds an epic
you already shipped, and together they fix the friction (dead reviews, payment lag, missing shipping
cost, no auto-discovery) that matters more to conversion right now than any Tier-2 feature. **Then**
take **variants (#6)** as a deliberate epic, followed by **facets (#7)**. Tier 3 is opportunistic —
collections (#8) whenever merchandising is the priority; coupons (#9) only after the honesty call.

| # | Item | Tier | Effort | Rides | Migration? | Status |
|---|------|------|--------|-------|-----------|--------|
| 1 | Review-request email on delivery | 1 | S | notifications, DELIVERED txn | no | ✅ |
| 2 | Shopper payment-proof upload | 1 | S–M | presign, payment_transaction | maybe | ✅ V67 |
| 3 | Wishlist / favorites | 1 | S | portal, cart-merge | yes | ✅ V69 |
| 4 | Best-sellers strip (computed) | 1 | S | order lines, home strip | no | ✅ |
| 5 | Tax + shipping + currency config | 1 | S×3 | order arithmetic, org settings | yes (small) | ✅ V68 |
| 6 | Product variants | 2 | L | reservations, catalog split | yes (epic) | ✅ V70 |
| 7 | Attribute facets | 2 | M–L | #6, B3 search | yes | ✅ (no migration needed) |
| 8 | Named collections | 3 | M | featured curation | yes | ✅ V71 |
| 9 | Honest coupon codes | 3 | M | discountTotal | yes — **needs owner call** | ✅ V72 (approved 2026-07-24) |
| 10 | Algorithmic recommendations | 3 | L | order lines, portal history | yes | ⬜ remaining |
