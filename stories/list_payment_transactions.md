# Slice: Read payment transactions — worklist list + detail

> Ships the reads deferred by [`refund_orphan_transaction.md`](refund_orphan_transaction.md)
> §Out ("the queue is still SQL/dashboard territory … when it ships it must use the payment-exists
> exclusion"). The queries it serves are the admin-dashboard queries pinned in
> [`transaction.md` §Operational queries](../sys-analysis/outbound/transaction.md) — the schema
> anticipated them with partial indexes (`idx_txn_unverified`, `idx_txn_orphan`, V22). Frontend
> driver: the **Payment reconciliation worklist (UNVERIFIED / ORPHAN / UNDERPAID / OVERPAID tabs)**
> in [`docs/frontend-architecture.md`](../docs/frontend-architecture.md) §1 — every tab is one call
> to the list, and each row clicks through to the detail.

---

## Goal

An org member sees the payment-transaction ledger, its work queues, and any single transaction
without psql access:

- `GET /api/orgs/{orgId}/payment-transactions?verification_status=…&reconciliation_status=…&has_payment=…&page=&size=`
- `GET /api/orgs/{orgId}/payment-transactions/{id}`

Done means: the four worklist tabs are each a single filtered call — the **open orphan queue** is
`reconciliation_status=ORPHAN&has_payment=false`, the payment-exists exclusion from
`transaction.md` expressed as a first-class filter — and the detail read answers the question the
list can't: *how was this transaction dispositioned?* (its 1:1 payment, and the order that payment
landed on, when either exists). Today an orphan is unreadable the moment you leave the
verify/resolve/refund outcome screen.

---

## Semantics

### List filters (all optional, ANDed)

| Param | Values | Meaning |
|---|---|---|
| `verification_status` | `UNVERIFIED` \| `VERIFIED` | the verification queue tab (`UNVERIFIED`) |
| `reconciliation_status` | `PENDING` \| `MATCHED` \| `ORPHAN` \| `UNDERPAID` \| `OVERPAID` | literal column match |
| `has_payment` | `true` \| `false` | does a 1:1 `payment` row exist for the transaction (`payment.payment_transaction_id` UNIQUE, V23)? `false` ⇒ `NOT EXISTS`, `true` ⇒ `EXISTS` |
| `page` / `size` | ints | default `0` / `20`, `size` clamped to `[1, 100]` (the `PlatformOrgService` pattern) |

Enum/boolean values are matched case-insensitively; an unknown value is a **400**, not an empty
list (a typo'd dashboard tab must fail loudly). Filters compose freely and are simply ANDed
(`UNVERIFIED` + any reconciliation value is a valid-but-empty query — reconciliation is NULL until
verified; not our job to police).

### The orphan queue is a composition, not a special case

The two disposition exits leave different footprints (per the shipped services — note:
`refund_orphan_transaction.md` §"Deliberately not touched" describes only the *refund* exit):

- `/resolve` flips reconciliation **ORPHAN → MATCHED** (`PaymentTransactionService.resolveOrphan`
  applies the MATCHED outcome) — it leaves the queue *by status*.
- `/refund` deliberately **keeps ORPHAN** (the transaction truly never matched an order); its
  disposition marker is the 1:1 standalone `payment` row — it leaves the queue *by the
  payment-exists exclusion*.

Hence the compositions:

- **Open orphan queue** (work to do): `reconciliation_status=ORPHAN&has_payment=false` — exactly
  the `transaction.md` §Operational queries orphan query, which needs both conditions.
  (`verification_status=VERIFIED` is implied: reconciliation is only ever stamped on verified
  rows.)
- **Orphan history** (`reconciliation_status=ORPHAN&has_payment=true`): the refund-dispositioned
  orphans. Resolve-dispositioned ones read as what they became — MATCHED.
- `reconciliation_status=ORPHAN` alone: open + refund-dispositioned — a literal column match,
  like every other value.

The filter is mechanical in the repository (`EXISTS` / `NOT EXISTS` subquery); no queue semantics
live below the query params. The frontend's ORPHAN tab owns composing the two params.

### Ordering

- **Any filter present** → queue view: `occurred_at ASC, id ASC` — oldest first, FIFO worklist,
  matching the dashboard queries' intent (and the V22 partial indexes).
- **No filter** → ledger view: `recorded_at DESC, id DESC` — recent activity first. This view
  includes everything: unverified claims, matched credits, dispositioned orphans, and the DEBIT
  rows written by refund execution.

`id` tiebreaks make pagination deterministic.

### Detail read

`GET /{id}` returns the transaction plus its money context when it exists:

- the 1:1 `payment` (via the existing `PaymentRepository.findByTransactionId`) — present iff the
  transaction was matched at verify time, resolved, or refund-dispositioned;
- the payment's order (via `SalesOrderRepository.findById`) — present iff
  `payment.sales_order_id` is set (absent for a standalone orphan-refund payment).

This reuses the verify response shape verbatim: `PaymentTransactionResponse.from(txn, payment,
order)` with the null blocks omitted by the global mapper. List rows stay lean (no per-row joins);
the disposition story is the detail view's job.

---

## Why this slice is small

| Piece | Already existed | Added here |
|---|---|---|
| Queue SQL + rationale | `transaction.md` §Operational queries (verbatim WHERE clauses) | jOOQ translation |
| Indexes | `idx_txn_unverified`, `idx_txn_orphan` partial indexes (V22); `payment.payment_transaction_id` UNIQUE (V23) | nothing — **no migration** |
| Disposition marker | "payment exists ⇒ dispositioned" (resolve + refund slices) | first *reader* of that rule (`has_payment`) |
| Detail joins | `PaymentRepository.findByTransactionId`, `SalesOrderRepository.findById` | reused verbatim |
| Response shape | `PaymentTransactionResponse` (verify endpoint) with null-omitted `payment`/`order` blocks | reused for both list rows and detail |
| Page envelope | `PageResponse<T>` (`{data, total, page, size}`) | reused |
| Routing | `PaymentTransactionHandler` mounted under `OrgServlet` | a `GET` branch (handler is POST-only today, incl. its 405 guard) |

---

## Scope

### In
- `PaymentTransactionRepository.ListFilter` (verificationStatus, reconciliationStatus,
  hasPayment — all nullable = unfiltered) + `list(orgId, filter, offset, limit)` +
  `count(orgId, filter)` + `findById(orgId, id)` (plain read beside the existing
  `findByIdForUpdate`); jOOQ impl with `DSL.exists`/`DSL.notExists` against `PAYMENT`.
- `PaymentTransactionService.list(orgId, filter, page, size)` returning a small
  `TransactionPage(List<PaymentTransaction> items, long total)` record, and
  `get(orgId, id)` returning txn + optional payment + optional order — both read-only on
  `rootDsl`, no explicit transaction (plain SELECTs, same as every other read endpoint); the
  service owns param parsing and the size clamp, nothing else.
- `GET` routing in `PaymentTransactionHandler`: bare path → list, `/{id}` → detail; handler
  javadoc updated (it currently claims all routes are POST + MANAGER).
- Responses: `PageResponse<PaymentTransactionResponse>` (list) and `PaymentTransactionResponse`
  (detail), via the existing `from(txn, payment, order)`.
- Docs: CLAUDE.md endpoint list; a note in `transaction.md` §Operational queries that the first
  two queries are now served by the list endpoint.

### Out (deferred)
- **`direction` / date-range / free-text filters, `sort` param** — the daily money-in/money-out
  sums in `transaction.md` are aggregate reports, not lists; they'll ship as a report endpoint,
  not filter soup here.
- **Lookup by provider reference** — `findByProviderRef` exists on the repository; expose it
  later as a `provider_ref=` list filter when a screen needs it (G6).
- **Embedding payment/order in list rows** — a join per row to answer a question the detail view
  now answers.
- **The refund on the detail view** — the disposition payment is enough to say *how* the orphan
  exited; the refund lifecycle screen is the Refund worklist's job.

---

## API contract

### List
```
GET /api/orgs/{orgId}/payment-transactions?reconciliation_status=ORPHAN&has_payment=false&page=0&size=20
```

`200 OK`:
```json
{
  "data": [
    {
      "id": "<uuid>",
      "org_id": "<uuid>",
      "provider": "INSTAPAY_MANUAL",
      "provider_ref": "IP-778899",
      "direction": "CREDIT",
      "amount": "250.00",
      "currency": "EGP",
      "verification_status": "VERIFIED",
      "verified_by": "<uuid>",
      "verified_at": "2026-07-01T10:15:00Z",
      "reconciliation_status": "ORPHAN",
      "occurred_at": "2026-07-01T09:58:00Z",
      "recorded_at": "2026-07-01T10:14:00Z",
      "claimed_by_customer_id": "<uuid>"
    }
  ],
  "total": 3,
  "page": 0,
  "size": 20
}
```
(Row shape = the verify response minus its `payment`/`order` blocks. `total` counts the filtered
set, so the ORPHAN tab's badge is free.)

### Detail
```
GET /api/orgs/{orgId}/payment-transactions/{id}
```

`200 OK` — same row shape plus the money context when it exists, e.g. a resolve-dispositioned
orphan:
```json
{
  "id": "<txn uuid>",
  "provider": "INSTAPAY_MANUAL",
  "verification_status": "VERIFIED",
  "reconciliation_status": "ORPHAN",
  "payment": {
    "id": "<uuid>",
    "sales_order_id": "<uuid>",
    "amount": "250.00",
    "unallocated_amount": "0.00",
    "status": "ALLOCATED"
  },
  "order": {
    "id": "<uuid>",
    "order_number": "SO-2026-000123",
    "status": "PAID",
    "grand_total": "250.00",
    "prepaid_amount": "250.00"
  }
}
```
An open orphan has neither block; a refund-dispositioned orphan has `payment` only
(`sales_order_id` absent — NULL).

### Errors
| Status | Cause |
|---|---|
| `400` | unknown `verification_status` / `reconciliation_status` / `has_payment` value; non-integer `page`/`size`; malformed `{id}` |
| `403` | caller has no role in `:orgId` |
| `404` | detail: transaction not found in `:orgId` |

---

## Authorization

`AuthzHelper.requireOrgAccess(orgId, VIEWER)` for both reads — the project-wide bar for reads
(same as `GET /payments/{id}`, `GET /invoices/{id}`). The POST routes on this handler stay
MANAGER; seeing the queue is visibility, acting on it is authority.

---

## Tests

`api/src/test/java/.../payment/PaymentTransactionReadIT.java` (TestContainers Postgres, drives the
services) — seed one org with: an UNVERIFIED claim, a MATCHED credit (payment + order), an open
ORPHAN, an orphan dispositioned via **resolve**, an orphan dispositioned via **refund**, an
UNDERPAID and an OVERPAID credit, and a DEBIT (executed refund). Then:

List:
- `?reconciliation_status=ORPHAN&has_payment=false` → exactly the open orphan; **both
  dispositioned orphans excluded** (resolve leaves by the MATCHED flip, refund by the
  payment-exists rule).
- `?reconciliation_status=ORPHAN&has_payment=true` → exactly the refund-dispositioned orphan.
- `?reconciliation_status=ORPHAN` alone → open + refund-dispositioned (literal match — pins that
  no hidden expansion exists).
- `?has_payment=true` alone → every transaction with a payment, across statuses (MATCHED, both
  dispositioned orphans, UNDERPAID, OVERPAID — partial and over- payments create payments too).
- `?verification_status=UNVERIFIED` → exactly the claim; `UNDERPAID` / `OVERPAID` → literal
  column matches; `MATCHED` → the straight match **plus** the resolve-dispositioned orphan.
- unfiltered → all rows including dispositioned orphans and the DEBIT, `recorded_at DESC`.
- filtered ordering is `occurred_at ASC` (seed distinct timestamps).
- pagination: `size=2` pages tile the filtered set without overlap; `total` is the filtered count.
- second org's transactions never appear (org scoping).
- `?reconciliation_status=BOGUS` / `?has_payment=maybe` → 400.
- VIEWER lists fine; non-member → 403.

Detail:
- open orphan → no `payment`/`order` blocks.
- resolve-dispositioned orphan → `payment` with `sales_order_id` + `order` block.
- refund-dispositioned orphan → `payment` standalone (no `sales_order_id`), no `order` block.
- MATCHED credit → `payment` + `order` (same shape the verify response returned at write time).
- unknown id → 404; another org's transaction id → 404 (scoping, not 403).
- after `/refund` dispositions the open orphan, the `has_payment=false` queue shrinks by one, the
  literal ORPHAN list does not, and the detail now shows the standalone payment (regression pin
  for the disposition-marker contract).

Handler-level 400s (unknown enum / `has_payment` values, malformed id) and the VIEWER/403 gate
follow the codebase convention: enforced in the handler, not re-tested per-endpoint in the
service-driven ITs.
