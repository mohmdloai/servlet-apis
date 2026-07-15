# Slice P2: Portal order history — "my orders"

> A logged-in customer sees *their* orders. A thin, customer-scoped read on the P1 plane — reusing the
> customer-safe order DTO the tracking slice already ships.
>
> Canonical: [`frontst/docs/customer-portal-epic.md`](../../frontst/docs/customer-portal-epic.md).
> Depends on **P1** ([`portal_auth_core.md`](portal_auth_core.md)) for the `(org_id, customer_id)`
> session context. Feeds frontend story 35.

---

## Goal
```
GET /api/portal/orders?page=&size=            → PageResponse of the session customer's orders (newest first)
GET /api/portal/orders/{orderNumber}          → one order (customer-safe), 404 if not this customer's
```
Both scoped to the session's `(org_id, customer_id)` from the JWT — never a query/path customer id.

## Why reuse, not a new shape
The public tracker already defined the **customer-safe** order body (`PublicOrderResponse.forOrderView`
— no internal id / product_id / prepaid / channel). "My orders" is the same body, listed, filtered by
`customer_id`. So this slice is one repository query + one handler; the DTO, the status model, and the
frontend `publicOrder` entity are all reused.

## Design
- **Repository:** `SalesOrderRepository.findByCustomerId(orgId, customerId, page, size)` + `countByCustomerId`
  — `WHERE org_id=? AND customer_id=?`, `placed_at DESC, id DESC`, lines batch-loaded (no N+1), same
  enrichment `findPlaced` uses.
- **Service:** `CustomerPortalService.listOrders(ctx, page, size)` and `getOrder(ctx, orderNumber)` —
  read `(orgId, customerId)` from the `SecurityContext`; `getOrder` resolves by `order_number` **and**
  asserts `order.customer_id == ctx.customerId` (else the opaque 404 — never an ownership oracle).
- **API:** `PortalServlet` gains `GET /orders[/{orderNumber}]`, returning `PageResponse<PublicOrderResponse>`
  / `PublicOrderResponse.forOrderView`. `Cache-Control: private, no-store` (customer-specific).

## Scope
**In:** the two reads + repository query + customer-ownership 404. **Out:** cancel/return/dispute from the
portal (staff-only actions); filtering/sort params (newest-first only in v1); invoices (P3).

## Authorization
Valid customer session only; strictly `(org_id, customer_id)`-scoped. A customer requesting another
customer's `orderNumber` → the same opaque 404 as a nonexistent order.

## Acceptance criteria
1. `GET /orders` returns exactly the session customer's orders, newest first, paged; a customer with none
   → empty page (200).
2. `GET /orders/{orderNumber}` returns the customer-safe body for an owned order; a foreign or unknown
   number → **404** (indistinguishable).
3. No internal id / product_id / prepaid / channel leaks (JSON scan); shape identical to the public tracker.
4. Cross-customer isolation: customer A's session can never read customer B's order or list.

## Tests
`PortalOrdersIT`: AC 1–4 incl. the foreign-order 404 and the no-leak scan; reuses the P1 login helper to
mint a customer session. `SalesOrderRepositoryImplTest` gains `findByCustomerId` coverage.

## What this unblocks
Frontend story 35 (the "my orders" list + in-portal order detail reusing the `order-tracker` widget).
