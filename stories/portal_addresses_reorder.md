# Slice P4: Portal saved addresses + reorder

> The two convenience features that make a returning customer faster: a **saved-address book** and
> **one-click reorder** from a past order. The one slice in this epic that adds a table.
>
> Canonical: [`frontst/docs/customer-portal-epic.md`](../../frontst/docs/customer-portal-epic.md).
> Depends on **P1** (session) and **P2** (order reads). Feeds frontend story 37.

---

## Goal
```
GET|POST /api/portal/addresses                 → list / create a saved address
PATCH|DELETE /api/portal/addresses/{id}        → edit / remove; POST .../{id}/default → set default
POST /api/portal/orders/{orderNumber}/reorder  → resolve a past order's lines to current buyable cart items
```
All scoped to the session `(org_id, customer_id)`.

## Why a `customer_address` table (not the single `customer.address` text)
`customer.address` is one free-text line frozen at checkout — fine as an order snapshot, useless as a
reusable book. A proper `customer_address` table lets a customer keep several labelled addresses and pick
one at checkout, without ever mutating past orders' snapshots. Reorder is a **read/resolve**, not a write:
it maps each past order line back to a *currently buyable* listing so the storefront can prefill the cart.

## Design
- **Migration (next `V##`)** — `customer_address(id, org_id, customer_id, label, recipient, phone,
  address, is_default BOOL, created_at, updated_at)`, FK `customer_id → customer(id) ON DELETE CASCADE`,
  index `(org_id, customer_id)`, a partial unique index enforcing **one default per customer**
  (`WHERE is_default`).
- **Addresses:** `CustomerAddressRepository` (CRUD, all `(orgId, customerId)`-scoped) + `CustomerPortalService`
  address methods; setting a default clears the previous in one txn; delete is soft-safe (addresses are
  never referenced by FK from orders — orders keep their own snapshot).
- **Reorder resolver:** for each line of the owned order, map `product_id → the current PUBLISHED
  `product_listing`` (via `product_listing.product_id`) and return `{listing_slug, title, unit_price,
  in_stock, requested_qty}`; **skip** products with no current published listing and flag them
  (`unavailable: [...]`) so the UI can say "3 of 4 items added". No stock is reserved here — the shopper
  re-runs checkout. Reuses the availability signal (B2) and the slug/price the storefront already serves.
- **API:** `PortalServlet` gains the address routes + `POST /orders/{orderNumber}/reorder`
  (`private, no-store`). Address writes honour the epic's CSRF guard (header + Origin).

## Scope
**In:** `customer_address` CRUD + default; the reorder resolver + `unavailable` reporting. **Out:** using a
saved address *in* checkout (a checkout change — separate follow-up; v1 surfaces the book + reorder-to-cart,
and the anonymous checkout still takes a typed address); address validation/geocoding; multiple default
kinds (billing vs shipping).

## Authorization
Valid customer session; every address and the reorder source order strictly `(org_id, customer_id)`-scoped.
Reorder of a foreign/unknown order number → opaque 404.

## Acceptance criteria
1. Address CRUD is customer-scoped; exactly one `is_default` per customer (setting a new default clears the
   old, atomically); delete removes only the caller's own row.
2. `POST /orders/{n}/reorder` for an owned order returns buyable cart items (`listing_slug`, current price,
   `in_stock`) for every line whose product is currently PUBLISHED, and lists the rest under `unavailable`.
3. A reorder of a foreign/unknown order → 404. A product no longer published is skipped, never errors.
4. No internal id leaks (reorder returns listing slugs, never `product_id`); cross-customer isolation holds.

## Tests
`PortalAddressesIT`: AC 1 (CRUD, single-default invariant, ownership). `PortalReorderIT`: AC 2–4 (buyable
resolution, unavailable reporting, foreign-order 404, no-leak scan). Reuses P1 session + P2/order fixtures.

## What this unblocks
Frontend story 37 (the address book UI + the reorder button that prefills the cart). A later "use saved
address at checkout" follow-up builds on the table.
