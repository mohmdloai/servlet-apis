# Read-path index audit — every endpoint → its query → the index that serves it

> Phase 1 of the performance case study. Method: all 32 `*RepositoryImpl` classes were read
> exhaustively and every SELECT/count/exists extracted (tables, WHERE columns, ORDER BY,
> pagination), then each shape was mapped against the full index inventory from
> `db/migration/` (secondary indexes, partial indexes, unique constraints, composite PKs).
> Verdicts: ✅ served · ⚠️ partially served (filter yes, sort/variant no) · ❌ unserved.
>
> **Headline:** the schema is *not* unindexed — the state-machine queues (orphan transactions,
> pending fulfillments, unpaid invoices, active reservations, notification feeds, review/comment
> moderation) were built with exactly-right partial indexes. The gaps cluster in three places:
> (1) the **generic worklist/ledger reads** added later (`refund` has *zero* indexes),
> (2) **FK join paths** for per-order detail reads (`sales_order_line`, `fulfillment_line`,
> `sales_invoice_line`, `credit_note_line`, reservations-by-order), and
> (3) **org-first reads of `user_org_role`** whose PK is user-first.
> None of this hurts at dev-scale row counts; all of it will at seeded production scale —
> which is what phase 2 (seed + measure) must prove *before* any index is added.

---

## 1. Index inventory (business tables)

Secondary indexes: see `V*` migrations — notably partials
`idx_so_pending(_global)` (PENDING_PAYMENT sweeps), `idx_txn_unverified`/`idx_txn_orphan`,
`idx_invoice_unpaid`, `idx_fulfillment_pending`, `idx_payment_unmatched`,
`idx_payment_order_unallocated`, `idx_reservation_active`, notification recipient/pending
partials, review/comment public+moderation composites, `product_listing` PUBLISHED/featured
partials, GIN trigram on `product.name_search`, `customer.name_search`,
`product_listing_translation.title_search`, `category_translation.name_search`.

Unique constraints that double as read indexes: `app_user(email)`, `org(slug)`,
`sales_order(org_id, order_number)`, `sales_order(org_id, idempotency_key)`,
`product(org_id, sku)`, `product(org_id, barcode)`, `customer(org_id, email)`,
`sales_invoice(org_id, invoice_number)`, live-invoice-per-fulfillment partial unique,
`payment_transaction(provider, provider_ref)`, `payment(payment_transaction_id)`,
`payment_allocation(payment_id, sales_invoice_id)` + both FK indexes,
`credit_note(org_id, credit_note_number)`, `refund_allocation(refund_id, payment_allocation_id)`,
`category(org_id, slug)`, `product_listing(org_id, slug|product_id)`,
`inventory_log(org_id, idempotency_key)`, token `token_hash` uniques + partial lookups.

Composite PKs that matter: `inventory(org_id, product_id)` ✅,
`user_org_role(user_id, org_id, role)` (user-first!), `product_listing_category(listing_id,
category_id)`, counters `(org_id, year)`, `notification_delivery_in_app(delivery_id)`.

---

## 2. Audit by table (endpoint → method → shape → verdict)

### sales_order
| Endpoint | Method | Shape | Verdict |
|---|---|---|---|
| `GET /sales-orders?status=` (worklist) | `list`/`count` | `org_id [+ status]`, order `created_at,id` (ASC queue / DESC ledger), OFFSET | ❌ no `(org_id, status, created_at)` and no `(org_id, created_at)` — per-org seq scan + sort |
| portal "my orders" | `findByCustomerId`/count | `org_id, customer_id`, order `placed_at DESC` | ❌ no `(org_id, customer_id)` |
| `?order_number=` lookup, idempotent replay | `findByOrderNumber`, `findByIdempotencyKey` | equality on uniques | ✅ |
| TTL sweeper | `findExpiredPendingIds` | `status='PENDING_PAYMENT', expires_at<now`, order `expires_at` | ✅ `idx_so_pending_global` |
| health rollup pending count | `health` | `org_id, status='PENDING_PAYMENT'` | ✅ `idx_so_pending` (predicate matches) |
| reports (sales, top products) | `sales`, `topProducts` | `org_id, status IN (…)`, range on `COALESCE(placed_at, created_at)` | ❌ expression blocks any range index (see §4) |

### sales_order_line
`findLinesByOrderId(s)` (order detail, every money/fulfillment flow), reservation joins, top-products join — all keyed `sales_order_id`. ❌ **no index** (PK `id` only).

### payment
| `GET /payments?status=&unallocated=` worklist + ledger | `list`/`count` | `org_id [+status] [+unallocated>0]`, order `received_at,id` | ❌ only partials (`unmatched`, per-order-unallocated); no `(org_id, status, received_at)`, no `(org_id, received_at)` |
|---|---|---|---|
| order money story | `findByOrderId` | `org_id, sales_order_id`, all statuses | ❌ only the `WHERE unallocated>0` partial — full read unserved |
| allocation FIFO lock | `findUnallocatedByOrderForUpdate` | `sales_order_id, unallocated>0` | ✅ `idx_payment_order_unallocated` |
| by transaction | `findByTransactionId` | unique | ✅ |
| health disputed/unallocated counts, reports collected | `health`, `revenue` | `org_id` scan w/ FILTER aggregates; `org_id, received_at` range | ❌ rides on nothing but would be served by the ledger index above |

### payment_transaction
Queue reads (`?verification_status=UNVERIFIED`, `?reconciliation_status=ORPHAN`) ✅ partials
(note: queue sorts `occurred_at` while partials index `recorded_at` — filter served, sort is a
small extra step). Unfiltered ledger `(org_id, recorded_at DESC)` ❌. `provider_ref` lookup
without `provider` ❌ (unique is `(provider, provider_ref)`; handler usually passes both — verify
in phase 2 before acting).

### refund — **zero indexes beyond PK; every read unserved** ❌❌
| `GET /refunds?status=` worklist/ledger | `list`/`count` | `org_id [+status] [+credit_note_id]`, order `created_at,id` | ❌ |
|---|---|---|---|
| credit-note cap meters (list + detail) | `sumExecutedByCreditNote(s)`, `existsExecuted…` | `org_id, credit_note_id, status='EXECUTED'` (+ IN-batch, GROUP BY) | ❌ |
| payment detail refunds | `findByPaymentId` | `org_id, payment_id` | ❌ |
| reports refunded | `revenue` | `org_id, status='EXECUTED', executed_at` range | ❌ |

### sales_invoice
| `GET /invoices?status=` worklist/ledger | `list`/`count` | `org_id [+status]`, order `created_at,id` | ❌ generic shape (only the unpaid-partial exists) |
|---|---|---|---|
| awaiting-payment queue, AR aging | `?status=ISSUED`-adjacent, `arAging` | matches `idx_invoice_unpaid` predicate | ✅ |
| order billing story | `findByOrderId` | `org_id, sales_order_id` | ❌ |
| portal invoices | `findByCustomerId` | `org_id, customer_id, status<>'VOID'` | ❌ |
| live invoice per fulfillment | `findByFulfillmentId` | partial unique | ✅ |
| reports invoiced | `revenue` | `org_id, status IN (ISSUED,PAID), issued_at` range | ❌ (partial's predicate is narrower) |

### sales_invoice_line / credit_note / credit_note_line / refund_allocation
`sales_invoice_line(sales_invoice_id)` ❌ · `credit_note(org_id, sales_invoice_id)` (crediting
story + issuance-cap guard) ❌ · `credit_note_line(credit_note_id)` ❌ ·
`refund_allocation(payment_allocation_id)` (settle-sum + exists-join) ❌.
`payment_allocation` both directions ✅.

### fulfillment / fulfillment_line
Queue `?status=PENDING` ✅ partial. Any other status queue (e.g. SHIPPED) and the unfiltered
ledger ❌. `findByOrderId (org_id, sales_order_id)` (shipment story, cancel flows, delivered/
fulfilled sums) ❌. `fulfillment_line(fulfillment_id)` (every detail/list row batch-load) ❌;
`fulfillment_line(sales_order_line_id)` (review purchase-gate EXISTS chain) ❌.

### inventory / inventory_log / inventory_reservation
`inventory` point reads/locks ✅ PK `(org_id, product_id)`. Overview list: LEFT JOIN from
`product`, filter `org_id` ✅ but `q` legs `name/sku ILIKE '%t%'` are unindexable by btree and
**not** covered by the trigram index (it's on `name_search`, and only one OR-leg uses it — an OR
with unindexed legs forces a scan anyway, see §4); OUT/LOW compute `stock_qty−reserved_qty`
inline (expression). `inventory_log.findByProductId` filter ✅ `(org_id, product_id)` but the
`created_at DESC, id DESC` sort is unindexed on a potentially huge per-product ledger ⚠️ —
extend to `(org_id, product_id, created_at, id)`. Reservations: ACTIVE-by-product ✅ partial;
CONSUMED/RELEASED variants ⚠️ (rare, admin-only); by-order reads join through
`sales_order_line` ❌ + `inventory_reservation(sales_order_line_id)` ❌.

### user_org_role — PK is user-first; every org-first read unserved ❌
Members roster page/count (`GET /members`), `ownerIdsForUpdate` last-owner guard,
`findActiveUserIdsByOrgAndRoles` (**runs inside every `ORDER_PLACED` notification fan-out**),
health/platform `member_count(s)`, `soleMemberOrgIds` anti-join — all filter `org_id` first.
User-first reads (`findOrgRoles`, `findRolesInOrg` — the login/token path) ✅ PK.

### Fully or adequately served (no action)
`customer` (uniques + org idx; `findAll` sort ⚠️ minor), `customer_address`, `category` (+
parent/child check), `product` point reads (sku/barcode/id), `product_listing` point reads +
PUBLISHED partial + featured partial + category/image/translation batch loads,
`storefront_banner`/`storefront_page`, `listing_review`/`listing_comment` public pages and
moderation queues (✅ purpose-built composites; `listing_review` lacks `(org_id, customer_id)`
for portal "my reviews" ⚠️ and `(org_id, customer_id, product_listing_id)` is a **unique-index
candidate** — the one-review-per-customer-per-listing rule is currently app-enforced only),
`notification` feeds + sweeper queues, `notification_preference` (uniques), magic tokens,
`org`, `app_user` (email unique; admin directory sort/prefix-ILIKE ⚠️ minor — btree can't serve
`ILIKE` even anchored; needs `lower(email) text_pattern_ops` if it ever matters),
`platform_audit` actor/target filters (unfiltered ledger sort ⚠️ — add `(created_at, id)` only
if measurement convicts), `impersonation_event` (insert-only).

---

## 3. The gap list, ranked

**P1 — hot worklists & every-page reads (measure first, expect the big wins here):**
1. `refund(org_id, status, created_at, id)` — worklist/ledger/queue; plus
   `refund(org_id, credit_note_id, status)` — cap meters; plus `refund(payment_id)` — FK.
2. `sales_order(org_id, status, created_at, id)` + `sales_order(org_id, created_at, id)`
   (queue + ledger) + `sales_order(org_id, customer_id, placed_at)` (portal).
3. `payment(org_id, status, received_at, id)` + `payment(org_id, received_at, id)` +
   `payment(org_id, sales_order_id)`.
4. `sales_invoice(org_id, status, created_at, id)` + `sales_invoice(org_id, sales_order_id)` +
   `sales_invoice(org_id, customer_id) WHERE status <> 'VOID'` (partial).
5. `fulfillment(org_id, status, created_at, id)` + `fulfillment(org_id, sales_order_id)`.
6. `user_org_role(org_id, user_id)` — roster, guards, notification fan-out.
7. `payment_transaction(org_id, recorded_at, id)` — unfiltered ledger.

**P2 — FK join paths (per-order detail reads; cheap, near-zero risk):**
8. `sales_order_line(sales_order_id)`
9. `fulfillment_line(fulfillment_id)` · `fulfillment_line(sales_order_line_id)`
10. `sales_invoice_line(sales_invoice_id)` · `credit_note_line(credit_note_id)`
11. `credit_note(org_id, sales_invoice_id)`
12. `inventory_reservation(sales_order_line_id)`
13. `refund_allocation(payment_allocation_id)`

**P3 — scale-dependent (only with measured evidence):**
14. `inventory_log(org_id, product_id, created_at, id)` — replace the 2-col index.
15. `listing_review(org_id, customer_id, product_listing_id)` **UNIQUE** — integrity + speed.
16. `product(org_id, created_at)`; sku-search trigram — decide after §4.
17. `platform_audit(created_at, id)`; `app_user(lower(email) text_pattern_ops)`.
18. Reports: persisted `sale_ts` (or expression index on `COALESCE(placed_at, created_at)`)
    if report latency measures badly.

---

## 4. Non-index findings (query-shape work, same phase)

- **OFFSET pagination + a second COUNT query on every page render.** Every list does two scans;
  OFFSET degrades linearly with depth. All ledgers already order by `(ts, id)` — keyset-ready.
  Honest scope: keep OFFSET for admin UIs (bounded pages), consider keyset for public catalog.
- **`ILIKE '%term%'` OR-chains** (product `name`/`sku`, listing `marketing_copy`): one indexed
  leg in an OR of unindexed legs still forces a scan. Either trigram-index every leg or fold the
  search into the single `name_search`/`title_search` column that already has a GIN index.
- **Queue sort vs partial-index column mismatch** (`payment_transaction`: filter indexes on
  `recorded_at`, queue sorts `occurred_at`) — fine while queues are small; note for phase 2.
- **`COALESCE(placed_at, created_at)`** in reports blocks index range scans by construction.
- **Inventory OUT/LOW** filter on computed `stock_qty − reserved_qty` — expression/partial
  index candidate only if the overview measures slow at scale.

## 5. What phase 2 must do before any index lands

Seed realistic volume (ABO-derived catalog + generated orders/ledgers), capture
`EXPLAIN (ANALYZE, BUFFERS)` + HTTP p50/p95 per endpoint above, apply P1→P2→P3 in order,
re-measure identically after each batch, and record write-side cost (index count per table ×
insert rate) so the case study shows the tradeoff was weighed, not ignored.
