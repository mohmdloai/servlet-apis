# Slice: Org-scoped dashboard reads (health rollup + disputes / unallocated / members total)

> The tenant dashboard's top strip shows a handful of tiles — members, pending-payment orders, open
> disputes, unallocated money. Today the frontend has **no single number to read**: it fans out
> parallel `size=1` list calls to scrape counts out of `total`, reaches for the **ORPHAN
> payment-transaction proxy** to guess at unallocated money (a different figure than the backend
> actually tracks), and counts members with `data.length` after pulling the **whole roster**. The
> irony is that the platform-admin plane already computes the exact strip — `OrgHealth(memberCount,
> pendingPaymentOrders, openDisputes, unallocatedPayments)` in
> [`OrgHealthRepositoryImpl.health`](../repository/src/main/java/com/loai/inventory/repository/OrgHealthRepositoryImpl.java),
> surfaced by `GET /api/admin/orgs/{orgId}` as
> [`AdminOrgDetailResponse.Health`](../api/src/main/java/com/loai/inventory/api/dto/AdminOrgDetailResponse.java) —
> but it is gated on a platform `SystemRole`, so a tenant can't see its own numbers. This slice gives
> the org its own rollup, plus the two drill-down lists the tiles link into.

---

## Today (the gaps)

- **G1 — no org-scoped health rollup.** The strip derives every count from a separate list request,
  each pulled down to `size=1` just to read `total`. Four tiles → four round-trips, none of them
  the figure they actually want.
- **G2 — no way to see disputes at org scope.** `payments` at org scope is *only* the per-payment
  dispute lifecycle
  ([`PaymentHandler`](../api/src/main/java/com/loai/inventory/api/servlet/handler/PaymentHandler.java):
  `GET /payments/{id}`, `POST /payments/{id}/dispute|uphold`) — there is no org-wide payments list
  and no disputes list. A tenant can flag and resolve a dispute but can't **count or preview** its
  open ones.
- **G3 — unallocated money is only reachable by a proxy.** The backend's `unallocatedPayments`
  figure counts **Payments** whose `unallocated_amount > 0`
  ([`OrgHealthRepositoryImpl`](../repository/src/main/java/com/loai/inventory/repository/OrgHealthRepositoryImpl.java)).
  The frontend can only reach the **ORPHAN payment-transaction** queue
  (`?reconciliation_status=ORPHAN&has_payment=false`) — a *transaction*-granularity proxy that
  answers a different question (unmatched transfers), so the tile is approximate and can disagree
  with the real number.
- **G4 — members total requires loading the roster.** `GET /api/orgs/{orgId}/members` returns a
  **bare array**, no `PageResponse`, no `total`
  ([`MemberHandler.doGet`](../api/src/main/java/com/loai/inventory/api/servlet/handler/MemberHandler.java) —
  `writeJson(resp, 200, data)`). The tile does `data.length`, which pulls every member row just to
  show a count — O(n) for an O(1) fact.

---

## Goal

1. **One call powers the strip.** `GET /api/orgs/{orgId}/health` returns the same four aggregates the
   platform plane computes — `{member_count, pending_payment_orders, open_disputes,
   unallocated_payments}` — so the widget swaps its data source with **no UI change** and the
   `size=1` fan-out collapses into a single request.
2. **Disputes and unallocated money are countable *and* previewable by the tenant.** The counts come
   from the rollup (G1); a new org-scoped **payments list** (`GET /api/orgs/{orgId}/payments`) backs
   the drill-down previews, filterable by `status=DISPUTED` (G2) and by `unallocated=true` (G3), so
   the unallocated tile stops leaning on the ORPHAN-transaction proxy and reads the exact figure.
3. **Members total is O(1).** The tile reads `member_count` from the rollup; the roster endpoint is
   additionally paginated so the roster *view* pages instead of loading everything at once.

Done means: the tenant dashboard renders every tile from **one** health call, and each tile that
drills down (disputes, unallocated, members) has a real list behind it — no `size=1` scraping, no
transaction-proxy stand-in, no full-roster count.

---

## Design

### G1 — `GET /api/orgs/{orgId}/health` (the spine)

- **Reuse, don't reinvent.** `OrgHealthRepository.health(orgId)` already returns
  `OrgHealth(memberCount, pendingPaymentOrders, openDisputes, unallocatedPayments)` in one bundled
  scan (distinct `user_org_role`, `sales_order` PENDING_PAYMENT, and a single `payment` pass with
  two `FILTER` counts). The only thing missing is a tenant-facing door. Add a thin org-scoped
  service call + handler that invokes the **same** repository method; the figures are identical to
  `GET /api/admin/orgs/{orgId}`'s `health` block by construction (same query, so the tenant and the
  platform operator can never see divergent numbers).
- **Shape:** return the identical JSON the admin detail already emits for its `health` node —
  `{member_count, pending_payment_orders, open_disputes, unallocated_payments}` — so the frontend's
  existing `AdminOrgHealthDTO` entity is reused verbatim as the org-scope type.
- **Authorization: MANAGER.** The rollup carries `member_count`, and the members roster read is
  already **MANAGER** (`AuthzHelper.requireOrgAccess(req, orgId, MANAGER)` in `MemberHandler.doGet`);
  a rollup that includes a roster-derived figure must be no more permissive than the roster itself.
  Suspension is enforced for free — `requireOrgAccess` already 403s a suspended org's members (with
  the platform-ADMIN bypass preserved).
- **No embedded rows.** `/health` is *counts only*. The disputes/unallocated **rows** live in the
  list below; keeping the rollup lean is what lets it stay one cheap query.

### G2 + G3 — `GET /api/orgs/{orgId}/payments?status=&unallocated=&page=&size=`

The counts are solved by G1; these gaps also need a **list** for the drill-down previews. There is
no org-wide payments list today (only the order-scoped `GET /sales-orders/{id}/payments`), so add one
on the existing `payments` handler:

- **Filters (AND):** `status` ∈ the `PaymentStatus` names (`RECEIVED|ALLOCATED|PARTIALLY_ALLOCATED|
  DISPUTED|REFUNDED|…`; unknown → **400**, same convention as `/refunds`, `/invoices`,
  `/payment-transactions`); `unallocated=true` narrows to `unallocated_amount > 0` — the exact
  predicate behind the health figure, so the tile's preview and its count come from the *same*
  definition. `status=DISPUTED` backs the disputes preview (G2); `unallocated=true` backs the
  unallocated preview (G3).
- **Envelope + ordering:** `PageResponse` (`{data, total, page, size}`), same **queue-vs-ledger**
  convention as the other worklists — a filter is a worklist (oldest-first, `received_at ASC, id
  ASC`, the allocation FIFO), the bare list is the ledger (newest-first). Rows reuse the existing
  [`PaymentResponse`](../api/src/main/java/com/loai/inventory/api/dto/PaymentResponse.java) shape.
- **Authorization: VIEWER.** Every other money read at org scope is VIEWER (`/refunds`, `/invoices`,
  `/payment-transactions`, order-scoped `/payments`); this matches.
- Needs a new `PaymentRepository.list(orgId, status, unallocatedOnly, offset, limit)` + `count(...)`
  — the org-scoped sibling of the existing `findByOrderId`.

### G4 — paginate `GET /api/orgs/{orgId}/members`

- The tile's **count** now comes from `health.member_count` (O(1)); no roster load.
- Separately, wrap the roster in `PageResponse` (`?page=&size=`, `total` = distinct-member count) so
  the roster *view* pages. Read stays **MANAGER**. This is the roster endpoint's job; the health
  rollup is the count's job.

---

## Errors

| Status | Cause |
|---|---|
| `400` | payments list `status` is not a known `PaymentStatus` (mirrors the other list endpoints) |
| `403` | caller lacks the role for the resource (health/members: not MANAGER; payments: not VIEWER), is not a member of `orgId`, or the org is suspended (platform-ADMIN bypass preserved) |
| `404` | `orgId` unknown |

---

## Tests

- **Health rollup matches the admin plane** (IT): for a seeded org with N members, P PENDING_PAYMENT
  orders, D DISPUTED payments, and U payments with `unallocated_amount > 0`, `GET
  /api/orgs/{orgId}/health` returns exactly `{member_count:N, pending_payment_orders:P,
  open_disputes:D, unallocated_payments:U}` — and the numbers equal `GET /api/admin/orgs/{orgId}`'s
  `health` block for the same org (both reduce to `OrgHealthRepository.health`).
- **Health is membership-gated** (IT): a VIEWER-only member gets 403 (MANAGER required), a
  non-member gets 403, a suspended org's member gets 403, and a platform ADMIN bypasses.
- **Payments list — disputes** (IT): `?status=DISPUTED` returns only DISPUTED payments, `total` ==
  `health.open_disputes`; an unknown `status` is 400; VIEWER-gated.
- **Payments list — unallocated** (IT): `?unallocated=true` returns only `unallocated_amount > 0`
  rows, `total` == `health.unallocated_payments` — proving the tile no longer needs the ORPHAN proxy.
- **Members paginated** (IT): `GET /members?page=&size=` returns a `PageResponse` whose `total`
  equals `health.member_count`, and paging returns disjoint slices; the previous bare-array behavior
  is gone.

---

## Out of scope / known gaps

- **Not real-time.** `/health` is a point-in-time read; the strip refreshes by re-calling it. No
  push/subscription.
- **`GET /members` shape change is breaking.** Moving from a bare array to a `PageResponse` envelope
  changes the response contract; the frontend roster consumer must switch to `data`/`total` in the
  same release (or, if a softer migration is wanted, gate the envelope behind the presence of
  `?page` and keep the bare array as the default — a decision to make with the frontend, not
  silently). The tile itself stops reading `data.length` regardless, since it moves to
  `health.member_count`.
- **`member_count` counts distinct users with any role** (matches the admin rollup's
  `countDistinct(user_org_role.user_id)`): a user holding multiple roles counts once. Deliberately
  identical to the platform figure so the two planes agree.
- **No new aggregates.** This slice exposes the *existing* four figures at org scope; a ninth-tile
  metric beyond `OrgHealth` (e.g. revenue, low-stock) is a separate slice that would extend
  `OrgHealth` and both the admin and org rollups together.
- **Health does not embed rows.** Disputes/unallocated previews come from the payments list, not
  from `/health`; bundling rows into the rollup would defeat its one-cheap-query purpose.
