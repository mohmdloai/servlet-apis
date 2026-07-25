# Slice: Customer wishlist (`/api/portal/wishlist`)

> Roadmap item 3 (Tier 1) — [`docs/storefront-growth-roadmap.md`](../docs/storefront-growth-roadmap.md).
> A heart on the product card / buy box; a portal "My wishlist" page. Cheap *because* the portal
> exists, and the hook for later re-marketing (back-in-stock, "still interested?"). Guests keep a
> local list that the **frontend** merges into the account after login — the roadmap's "mirror the
> cart merge-on-login" was wrong: no server-side cart merge exists (the cart is client-only
> `localStorage`), so the merge is client-driven (frontend story 64) and the backend needs **no
> change to `verify-code`**. Feeds frontend story 64.

---

## Goal

A logged-in customer hearts a listing → `POST /api/portal/wishlist {listing_slug}` stores the pair;
`GET /api/portal/wishlist` returns their saved listings as **whitelisted catalog rows** (the exact
`PublicListingResponse` card shape, PUBLISHED-only); `DELETE /api/portal/wishlist/{listingSlug}`
removes one. All idempotent, all scoped to the session's `(org_id, customer_id)` — never the
URL/body.

## Migration — **V69** `customer_wishlist`

```sql
CREATE TABLE customer_wishlist (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID NOT NULL REFERENCES org(id),
    customer_id         UUID NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    product_listing_id  UUID NOT NULL REFERENCES product_listing(id) ON DELETE CASCADE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (customer_id, product_listing_id)
);
CREATE INDEX customer_wishlist_owner_idx ON customer_wishlist (org_id, customer_id, created_at DESC);
```

`ON DELETE CASCADE` both ways = the table self-heals (customer purge, listing deletion); the
`UNIQUE` pair + `ON CONFLICT DO NOTHING` makes every add — including the login-merge replay —
idempotent. jOOQ codegen after. *(Numbering: V67 = branch 111, V68 = branch 112 — this branch off
master carries only V69; see the codegen note in the implementation instructions.)*

## Design

- **Slug-only API surface** (no-leak): the wire carries the listing **slug** in both directions;
  `product_listing_id` never crosses. The write resolves slug → id server-side via
  `ProductListingRepository.findBySlug(orgId, slug)` — **any status** (the review-write precedent,
  `ListingReviewService.submit` ~94–95): a heart placed just before an unpublish still lands, the
  row simply stops serving while unpublished and reappears on re-publish. Unknown slug → opaque
  404 (the merge replay client-side ignores it).
- **`WishlistService`** (new, `service` module; constructed in `AppConfig`, exposed as a field):
  - `add(orgId, customerId, listingSlug)` → resolve, **cap guard** (≥ 200 rows → 400
    `"wishlist is full"` — keeps the read bounded), insert `ON CONFLICT DO NOTHING`. Returns void
    (204 fresh *and* replay — idempotence over ceremony).
  - `remove(orgId, customerId, listingSlug)` → resolve + delete own row; idempotent 204 (an
    unknown-but-valid slug or an already-removed row is still 204; only an unresolvable slug 404s).
  - `list(orgId, customerId)` → the customer's listing ids newest-first (the owner index), then a
    **new PUBLISHED-only read** `findPublishedViewsByIds(orgId, ids)` on the storefront read path
    that returns fully-enriched `ListingView`s (availability + images + categories + rating —
    reuse `listPublished`'s batch enrichment; no N+1) **in the wishlist's recency order**; map to
    `PublicListingResponse[]` (bare JSON array — the "my reviews" convention; the ≤200 cap bounds
    it, no pagination).
- **`PortalServlet`** routes (the reviews-block dispatch pattern, identity via
  `requirePrincipal`): `GET /wishlist` · `POST /wishlist` (`{listing_slug}`) ·
  `DELETE /wishlist/{listingSlug}`; `Cache-Control: private, no-store`; 405 otherwise.
- **Rate limiting**: deliberately stays on the generous `rl:portal-read` catch-all — wishlist
  writes are cheap idempotent upserts, unlike reviews/comments (moderation-queue writes). The ≤200
  cap is the real bound. Documented, not accidental.
- **No notification, no admin surface, no `verify-code` change** — v1 is the storage + read.

## Scope

### In
V69 + codegen; `WishlistRepository` (+ impl + factory); `WishlistService` + `AppConfig` wiring;
the three `PortalServlet` routes; `findPublishedViewsByIds` on the listing read path; ITs.

### Out
Guest storage + merge (frontend-only, story 64). Back-in-stock / re-marketing notifications (the
future hook, not this slice). Pagination (capped instead). A dedicated rate bucket. Any public
(anonymous) wishlist read.

## Tests (`PortalWishlistIT` — model: `PortalReviewsIT`'s harness)

1. **Roundtrip**: add two, `GET` returns both as whitelisted card rows (slug/title/price/in_stock/
   images — no id/product_id), newest-first; delete one → gone; re-delete → 204.
2. **Idempotent add**: same slug twice → one row, both calls 204.
3. **Publish lifecycle**: unpublish a hearted listing → drops from `GET` (row survives); republish
   → returns. Delete the listing → row cascades.
4. **Isolation**: customer B (and the same email in another org) never sees A's rows; identity
   comes from the principal, never the body.
5. **Unknown slug** → opaque 404 on add; **cap**: at 200 rows the next add → 400.
6. **DRAFT heart**: adding a DRAFT listing's slug stores the row and `GET` omits it until publish
   (the any-status-resolve, PUBLISHED-only-serve pair).

## Definition of done

All six ITs green; existing portal ITs untouched and green; spotless clean; `AppConfig` wiring
compiles the api module.
