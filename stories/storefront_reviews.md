# Slice R1: Verified-purchase listing reviews (portal write · staff moderation · public read)

> The storefront sells on faith — InstaPay to a stranger — and shows zero social proof. Reviews were
> deliberately deferred until the customer portal existed; it now does (P1–P6: isolated
> `/api/portal/*` plane, `ctx.customerId`, org-scoped sessions). This slice ships reviews across all
> three planes: a **portal-authenticated write gated on a delivered purchase**, a **staff moderation
> worklist**, and an **anonymous whitelisted public read** with computed rating aggregates on the
> listings the storefront already renders.
>
> Canonical decisions: [`frontst/docs/storefront-reviews-epic.md`](../../frontst/docs/storefront-reviews-epic.md)
> (§1–§3, §6–§8, §10). Feeds frontend story 40. Reuses the portal spine from `portal_auth_core.md`
> (filter, CSRF, buckets) and the queue-vs-ledger worklist conventions.

---

## Goal

A logged-in customer who **received** a product can rate it 1–5 with optional text; the merchant
approves or rejects; approved reviews and their aggregate appear on the public listing surface.
One review per `(customer, listing)`, edit = re-moderation, own-delete, and no route ever serves
two planes.

Done means: a customer with a DELIVERED kettle can review the kettle and not the unbought GoPro
(403, cause-naming); the review is invisible publicly until approved; the listing detail then
carries `rating_avg`/`rating_count`; and the public rows leak no identity beyond the frozen
display name.

## Design

- **Migration (next `V##`)** — `listing_review` (UUID PKs like every business table —
  `product_listing.id` is a UUID, V40):
  ```
  id UUID PK DEFAULT gen_random_uuid() · org_id UUID NOT NULL REFERENCES org(id)
  product_listing_id UUID NOT NULL REFERENCES product_listing(id)
  customer_id UUID NOT NULL REFERENCES customer(id) ON DELETE CASCADE
  rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5)
  body TEXT NULL                                   -- ≤ 2000 (service); plain text, stored verbatim
  display_name TEXT NOT NULL                       -- frozen from customer.name at write (epic §6)
  status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED'))
  created_at / updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
  UNIQUE (customer_id, product_listing_id)         -- one review per buyer per listing (epic §3)
  ```
  Indexes: `(product_listing_id, status, created_at DESC)` (public read + aggregate),
  `(org_id, status, created_at)` (moderation queue). jOOQ codegen.
- **Eligibility (the verified-purchase gate, epic §2)** — one repository predicate,
  `hasDeliveredProduct(orgId, customerId, productId)`. Note `fulfillment_line` carries **no**
  `product_id` — it references `sales_order_line_id` — so the predicate is:
  `EXISTS fulfillment_line JOIN sales_order_line (product_id = ?) JOIN fulfillment
  (status = 'DELIVERED') JOIN sales_order (org_id = ?, customer_id = ?)`. The `product_id` is
  resolved server-side from the listing slug (the public plane never sees it, as ever).
- **Service** — `ListingReviewService`:
  - `submit(orgId, customerId, listingSlug, rating, body)` — listing must exist in-org (opaque
    404); eligibility else **403** `"you can review items after they're delivered"`; body ≤ 2000
    (400); freezes `display_name` from the customer row; **upsert on the unique key** — an
    existing review is overwritten, status resets to `PENDING` (edit = re-moderation), and the
    upsert bumps `updated_at` explicitly (no trigger convention in this schema);
  - `myReviews(orgId, customerId)` / `deleteOwn(orgId, customerId, reviewId)` (foreign/unknown id
    → opaque 404, the P2 pattern);
  - `moderate(orgId, reviewId, APPROVED|REJECTED)` (staff; unknown id in-org → 404);
  - `publicPage(orgId, listingId, page, size)` + `aggregate(orgId, listingId)` — APPROVED only.
- **Portal API** (`PortalServlet`, session-scoped; mutations behind `X-Portal-Request` + Origin;
  new bucket `rl:portal-review` at a strict per-customer/IP limit):
  ```
  POST   /api/portal/reviews          {listing_slug, rating, body?}   → 201 (or 200 on edit-upsert)
  GET    /api/portal/reviews          → mine, newest-first (listing slug+title joined, status)
  DELETE /api/portal/reviews/{id}
  ```
  All `Cache-Control: private, no-store` (the portal convention).
- **Session hint for ISR surfaces (epic §11)** — `CustomerAuthCookies` gains a third cookie,
  `customer_hint=1`: **not** HttpOnly (client JS may read it — that is its whole point), `Path=/`,
  `SameSite=Strict`, same Max-Age as the session; written wherever the session cookies are written
  (verify-code, refresh), cleared on logout/logout-all. **UI-only** — no filter or handler ever
  reads it; frontend story 40 uses it to branch the review CTA on the cookie-free ISR listing page
  without firing a portal call for anonymous shoppers. A stale hint (dead session) is harmless:
  the first portal call 401s and the UI degrades to signed-out.
- **Staff API** — `ReviewHandler` under `OrgServlet`, the worklist conventions:
  ```
  GET  /api/orgs/{orgId}/reviews?status=&page=&size=    (VIEWER — filtered = queue created_at ASC,
                                                         unfiltered = ledger DESC; unknown status → 400)
  POST /api/orgs/{orgId}/reviews/{id}/approve | /reject (STAFF)
  ```
  Admin rows carry customer context (name/email) + listing title — staff already see CRM.
- **Public read** (`PublicStorefrontServlet`, anonymous, `rl:pub-read`):
  ```
  GET /api/public/{orgSlug}/listings/{listingSlug}/reviews?page=&size=
  ```
  The `{listingSlug}` resolves through the **same PUBLISHED-only resolution as the listing read**
  (never a bare slug lookup), so reviews on a DRAFT/ARCHIVED listing are unreachable by
  construction, not just by navigation. APPROVED only, `created_at DESC`, paged envelope,
  `Cache-Control: public, max-age=60`. Row:
  `{display_name, rating, body?, created_at}` — nothing else (epic §6). And the **aggregate**:
  `PublicListingResponse` (detail **and** list rows) gains optional `rating_avg` (one decimal,
  string) + `rating_count`, computed per epic §8 (grouped query over APPROVED; omitted when count
  is 0 — absent, never zero-fabricated). B3 filters/sort are untouched — rating is **not** a sort
  or filter in this slice.

## Scope

### In
Migration + codegen; the eligibility predicate; service + validation; the three plane surfaces;
`PublicListingResponse` aggregate fields; the `customer_hint` UI cookie on `CustomerAuthCookies`;
DTOs (`PortalReviewRequest/Response`, `ReviewAdminResponse`, `PublicReviewResponse`).

### Out (deferred)
Merchant replies to reviews (R2's pattern, later); photos; helpful votes; rating as a public
filter/sort; solicitation notifications (`REVIEW_REQUESTED`, a later P5-riding slice); any
auto-approval setting.

## Authorization

Portal routes: the customer session is the whole story — every query keys on
`(ctx.orgId, ctx.customerId)`; cross-customer access is unrepresentable, foreign ids opaque-404.
Staff: VIEWER reads the queue, STAFF moderates (catalog convention). Public: anonymous, whitelisted,
PUBLISHED-listing-scoped (a review on an unpublished listing is unreachable because its listing read
is).

## Acceptance criteria

1. **Eligibility:** a customer with a DELIVERED fulfillment line for the product can submit; the
   same customer for an un-bought listing → 403; bought-but-not-delivered (PENDING/SHIPPED
   fulfillment, or PAID order with no fulfillment) → 403; unknown listing slug → opaque 404.
2. **Upsert-edit:** second submit for the same listing replaces rating/body and resets an APPROVED
   review to PENDING (it drops off the public read until re-approved); the row count doesn't grow.
3. **Moderation:** approve → row appears in the public page and moves the aggregate; reject → never
   public; the queue (`?status=PENDING`) is oldest-first, the ledger newest-first; unknown status
   → 400.
4. **Public whitelist:** rows carry exactly `{display_name, rating, body?, created_at}` (JSON
   scan — no customer_id/email/ids); `max-age=60`; non-GET → 405.
5. **Aggregate:** with APPROVED ratings 4 and 5, detail + list rows carry `rating_avg:"4.5"`,
   `rating_count:2`; PENDING/REJECTED rows don't count; zero approved → both fields absent.
6. **Own-delete:** deleting mine removes it everywhere (public read + aggregate reflect within
   cache TTL); deleting another customer's id → opaque 404.
7. **Plane isolation:** the portal routes reject staff-`aud` tokens and anonymous calls (the P1
   filter contract, regression-riding); the public routes accept no writes.
8. Body > 2000 chars → 400; rating outside 1–5 → 400; body stored/returned byte-verbatim
   (inertness is storage-level; rendering is the frontend's rule).
9. **Hint cookie:** `customer_hint` rides every response that sets the session cookies
   (verify-code, refresh) and every one that clears them (logout, logout-all); it is never
   HttpOnly; no server code path reads it (only `CustomerAuthCookies` writes it).

## Tests

`PortalReviewsIT` (AC 1–2, 6–8 — seeds an order + DELIVERED fulfillment via the existing
rehydrate/insert helpers from `PortalOrdersIT`), `ReviewModerationIT` (AC 3, worklist conventions),
`PublicReviewsIT` (AC 4–5 incl. the no-leak scan and aggregate arithmetic). Regression:
`StorefrontIT`/`StorefrontSearchIT` green with the two new optional response fields;
`PortalAuthIT` extended for AC 9 (the hint cookie set/cleared alongside the session cookies),
otherwise untouched. Verified live through Tomcat end-to-end: OTP login → submit → 403 matrix
→ approve via staff plane → public read shows row + aggregate.

## What this unblocks

| Next | Depends on this |
|---|---|
| **Frontend story 40** — stars + reviews on the listing page, portal write UI, admin queue | the whole surface |
| **R2 comments** (`storefront_comments.md`) | reuses the three-plane pattern + moderation worklist shape |
| Rating sort/filter on B3, review solicitation, merchant replies | the table + aggregate |
