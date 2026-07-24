# Slice: Shopper payment-proof claim (`payment-claim`)

> Roadmap item 2 (Tier 1) — [`docs/storefront-growth-roadmap.md`](../docs/storefront-growth-roadmap.md).
> After InstaPay-prepaying, a shopper attaches their **reference + screenshot** to the order (guest
> magic-link view *or* portal order page). It lands in the staff reconciliation worklist as a pending
> `payment_transaction`, collapsing the "did my payment go through?" lag. Strengthens the manual model
> — the human still verifies, but starts from the shopper's own evidence. Feeds frontend story 61.

---

## Goal

A shopper POSTs `{reference, proof_object_key?, note?}` for their order; the system records an
**UNVERIFIED CREDIT `payment_transaction`** (amount = the order's outstanding) that immediately
appears in the staff queue at `?verification_status=UNVERIFIED`. Staff verify + reconcile it with the
**existing** record path — unchanged. Two entry points, one service method.

## What already exists (so the slice stays small)

- `PaymentTransaction.createClaimed(...)` — a CREDIT/UNVERIFIED factory literally built for the
  "customer-supplied claim" (carries `claimedByCustomerId`, `customerNote`).
- `payment_transaction` columns `claimed_by_customer_id`, `customer_note`, `raw_payload`,
  `UNIQUE(provider, provider_ref)`, partial index `idx_txn_unverified` (the admin queue).
- `PaymentTransactionRepository.insertIfAbsent` = `INSERT … ON CONFLICT (provider,provider_ref) DO
  NOTHING` → idempotent replay for free.
- The presign machinery (`ObjectStorage.presignPut/presignGet` + per-scope key minters/prefix guards).
- `MagicLinkService.resolveOrderView` (guest token → order) and `PortalServlet` order reads (session
  → owned order). The reconciliation worklist read (`GET …/payment-transactions?verification_status=`).

## Design

### Migration — **V67** `payment_transaction` gains `proof_object_key TEXT`
A dedicated column, not the existing free-text `verification_proof` (which staff write during verify
and the worklist renders verbatim): the shopper's screenshot is an **object-storage key** that must be
presigned to view, a categorically different value. jOOQ codegen after.

### Object storage
`ObjectStorage.newPaymentProofKey(orgId, orderId, filename)` → `{orgId}/payment-proof/{orderId}/{uuid}-{name}`
+ `paymentProofKeyPrefix(orgId, orderId)`, mirroring `newImageKey`/`keyPrefix`. On claim, the supplied
`proof_object_key` must `startsWith` the `(orgId, orderId)` prefix (400 otherwise) — an anonymous
caller must not attach an arbitrary/cross-tenant key.

### Domain / repository
Add `proofObjectKey` to `PaymentTransaction` (field + getter, threaded through `createClaimed` +
`rehydrate` + the private ctor) and to the repo insert + row mapping.

### Service — `PaymentTransactionService.claim(orgId, ClaimCommand)`
`ClaimCommand{salesOrderId, claimedByCustomerId, reference, proofObjectKey?, note?}`. Loads the order
(`SalesOrderService.findPlaced`), computes `outstanding = grandTotal − prepaidAmount`; **409 if ≤ 0**
(fully-paid — before the `amount > 0` CHECK/invariant). Builds `createClaimed(provider=INSTAPAY_MANUAL,
providerRef=reference, amount=outstanding, claimedByCustomerId, customerNote=note, proofObjectKey,
occurredAt=now)`, `insertIfAbsent`, **stops** (no verify, no reconcile). Returns `{transaction,
inserted}` → 201 first record / 200 idempotent replay.

### Entry points
- **Guest** — `POST /api/public/orders/{token}/payment-claim` on `PublicOrderServlet` (JWT-bypassed):
  re-resolve the token via `MagicLinkService.resolveOrderView` (extend `ResolvedOrderView` to expose
  `customerId` — it's already on the token row), then `claim(...)` with that customer. Plus a
  **magic-token-authorized presign** `POST /api/public/orders/{token}/payment-proof/presign` (guests
  have no JWT — the STAFF org presign can't be reused): resolves the token, mints
  `newPaymentProofKey(orgId, orderId, filename)`, returns `{upload_url, object_key, expires_in}`.
- **Portal** — `POST /api/portal/orders/{orderNumber}/payment-claim` on `PortalServlet` (identity from
  `CustomerPrincipal`; ownership via `getOrderDetail`) + `POST …/payment-proof/presign`.

### Rate limiting
New buckets: `rl:pub-payment-claim` (per-IP, on the public POST + presign) and `rl:portal-payment-claim`
(per the portal buckets), each with an env limit falling back to a default — anonymous/authed writes.

## Scope

### In
V67 + codegen; `proofObjectKey` through domain/repo; `newPaymentProofKey`/prefix; `claim()`; the guest
+ portal claim & presign routes; extend `ResolvedOrderView` with `customerId`; rate-limit buckets; the
claim's presigned-GET exposure on the transaction detail read so staff see the screenshot; unit + ITs.

### Out
Any change to staff verify/reconcile (unchanged — the shopper's UNVERIFIED row flows through the
existing `verify()` path). Auto-verification (a human still confirms). Editing/deleting a filed claim
(v1: re-filing the same reference is an idempotent no-op; a wrong claim is resolved by staff).

## Guards / gotchas

- **Fully-paid order** → 409 before the `amount > 0` invariant (don't let the DB CHECK 500).
- **Object-key prefix guard** bound to the token's/session's `(orgId, orderId)` — mandatory on the
  anonymous path.
- **Idempotency** via `UNIQUE(provider, provider_ref)` + `insertIfAbsent`; a re-filed identical
  reference returns the prior row (200). Two shoppers colliding on one reference string hit the same
  natural key — acceptable (a reference is globally unique in reality); document it.
- **Amount is the outstanding at claim time**, snapshotted — a later partial payment doesn't rewrite it.

## Tests

- **Unit**: `claim()` builds an UNVERIFIED CREDIT txn at the outstanding amount; 409 on a fully-paid
  order; prefix-guard rejects a foreign `proof_object_key`.
- **IT (guest + portal)**: a claim on a PENDING_PAYMENT order records an UNVERIFIED txn that surfaces
  in `?verification_status=UNVERIFIED`; a duplicate reference replays 200 with no second row; a claim
  on someone else's order (portal) / an unknown token (guest) → opaque 404; staff `verify` on the
  recorded row proceeds to reconcile as today.

## Definition of done

V67 + codegen; `claim()` + both routes + presign; prefix guard + rate limits; the four ITs + unit
green; `mvn test` clean. Frontend story 61 delivers the "I've paid — submit proof" form.
