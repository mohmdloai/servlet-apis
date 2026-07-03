# Slice: Per-org order TTL (configurable payment-hold window)

> Implements the deferred half of [`reservation.md` §Default TTL](../sys-analysis/outbound/reservation.md):
> *"Manual InstaPay orders: 24 hours. **Configurable per-org.**"* The default shipped hardcoded
> (`ONLINE_TTL = Duration.ofHours(24)`); this slice makes it an org setting, the same
> business-policy-knob pattern as `refund_approval_threshold` (V33).

---

## Goal

Each org tunes how long a reserved online/phone order may sit in `PENDING_PAYMENT` before the
TTL sweeper expires it and releases its stock: a high-value B2B org wants a 72h bank-transfer
window, a flash-sale org wants 2h so reserved stock recycles fast.

Done means: placement stamps `expires_at = placed_at + org.order_ttl_minutes`, the org's OWNER
sets the value via the existing `PUT /api/orgs/{orgId}`, and an untouched org behaves exactly as
before (1440 minutes = 24h).

---

## Design decisions

- **Minutes, not hours** — the spec's own future (PSP webhooks: "minutes") needs sub-hour
  windows and flash sales want sub-day granularity; hours would be a unit we'd have to migrate
  away from later.
- **Bounds in the schema** — `CHECK (order_ttl_minutes BETWEEN 15 AND 43200)` (15 minutes to 30
  days): a fat-fingered `0` would insta-expire every new order before the customer could pay; a
  huge value would hold reserved stock effectively forever. The service mirrors the bounds as a
  friendly 400 (`OrgService.MIN/MAX_ORDER_TTL_MINUTES`).
- **Read inside the placement txn** — `placeOnlineOrder` loads the org row in the same
  transaction that stamps `expires_at`, so a just-changed setting applies immediately and
  consistently (the `RefundService.orgThreshold` pattern).
- **The sweeper is untouched** — `OrderTtlSweeperJob` keys off `sales_order.expires_at` alone;
  per-order windows of any length coexist in one queue. Already-placed orders keep the
  `expires_at` they were stamped with — changing the knob is not retroactive (by design: the
  customer was quoted a deadline at placement).
- **In-store is unaffected** — no reservation phase, no `PENDING_PAYMENT`, no TTL.

---

## Scope

### In
- Migration **V47**: `org.order_ttl_minutes INTEGER NOT NULL DEFAULT 1440 CHECK (BETWEEN 15 AND 43200)`.
- `Org` domain field + `OrgRepositoryImpl` read/update; jOOQ regen.
- `OrgService.update(id, name, refundApprovalThreshold, orderTtlMinutes)` — null leaves the
  value unchanged; out-of-bounds → 400.
- `SalesOrderService.placeOnlineOrder`: org row read in-txn; `ONLINE_TTL` constant deleted
  (constructor gains `OrgRepositoryFactory`).
- `PUT /api/orgs/{orgId}` body + `OrgResponse` gain `order_ttl_minutes`.

### Out (deferred)
- **Per-channel TTLs** (ONLINE vs PHONE vs future PSP) — one knob per org covers v1; a PSP
  integration slice can add a channel override if webhook latency demands it.
- **Retroactive re-stamping** of open orders on a knob change — intentionally not done (see
  design decisions).
- **Backorders** — explicitly out (`salesOrder.md`: "v1 rejects orders that can't be fully
  reserved"); unrelated to the hold window.

---

## API contract

`PUT /api/orgs/{orgId}` (OWNER) — existing endpoint, extended body:
```json
{ "name": "Acme", "refund_approval_threshold": "750.00", "order_ttl_minutes": 4320 }
```
- `order_ttl_minutes` optional; omitted/null → unchanged. Out of 15…43200 → `400`.
- `OrgResponse` now returns `order_ttl_minutes` everywhere the org is serialized.

Placement (`POST /sales-orders`, ONLINE/PHONE) is contract-unchanged — only the stamped
`expires_at` now derives from the org setting.

---

## Tests

`CustomerResolutionAtPlacementIT` (drives `placeOnlineOrder` against real repositories):
- `reservationTtl_isPerOrg_defaultStays24h` — three orgs (120 min / 4320 min / untouched):
  each order's `expires_at − placed_at` equals its org's window; the untouched org keeps exactly
  24h, so every pre-V47 org and test is behavior-identical. (Replaces the old
  `reservationTtl_isFixed24h_identicalAcrossOrgs` test that empirically pinned the gap.)
- `orderTtl_updatableViaOrgService_boundsEnforced` — default is 1440; `OrgService.update` to 60
  sticks and immediately drives placement; 14 and 43201 → `ValidationException` and the stored
  value is untouched.
- Regression: `OrderExpiryServiceIT` (sweeper), `InStoreSaleIT`, `OrgAdminHandlerAuthTest` —
  all green; full suite 89 unit + 260 IT.
