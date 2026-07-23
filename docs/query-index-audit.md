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

### Composite back-references (child rows located by a 2–3-column parent reference)
Postgres never auto-indexes the referencing side, so each was verified explicitly:
✅ all four V63 translation tables carry `UNIQUE (parent_id, language)` — the default-locale
`default_t` join on every catalog/review/comment read is served; ✅
`notification_delivery(notification_id, channel)` unique serves the in-app feed joins; ✅
`inventory` is always referenced by its full `(org_id, product_id)` PK (overview join,
availability, reservations release); ✅ `inventory_log(org_id, product_id)`; ✅ number
counters `(org_id, year)` PK; ✅ `notification_preference` partial uniques
`(org_id, user_id|customer_id, type, channel) WHERE subject_type=…` serve `resolveEnabled`
(runs inside every `notify()`) as two probes for the `type IN (x,'ALL')` pair; ✅
`product_listing_category` PK `(listing_id, category_id)` + `(category_id)` covers both
directions. ❌ The failures of this exact pattern are already ranked below: `user_org_role`
org-first (P1.6 — PK has the right columns in the wrong order), and the two-column
child-references `payment/sales_invoice/fulfillment(org_id, sales_order_id)`,
`credit_note(org_id, sales_invoice_id)`, `refund(org_id, credit_note_id|payment_id)`
(P1/P2). ❌ New from this sweep: `sales_order_line.product_id` (line → product
back-reference; drives `topProducts` grouping and offers the planner a product-side entry
into the review purchase-gate EXISTS) — P3.19.

Note on "reshape parent→child instead": in Postgres the *textual* direction of a join doesn't
fix the plan — the planner chooses the driving side. Indexing both join endpoints (the
preference here) is the correct fix; reshaping only pays when an expression (`COALESCE`) or an
OR-chain makes one side unindexable no matter what.

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
19. `sales_order_line(product_id)` — top-products grouping + product-side entry into the
    review purchase-gate EXISTS chain.

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

## 5a. Phase 2 baseline — measured (seeded perfdb: 1M orders, 16.3M rows total)

`tools/seed/` harness + `measure_baseline.sql`, warm-cache run in `tools/seed/results/baseline.txt`.
The measurement re-ranks the static list — in both directions:

| ms | shape | why |
|---|---|---|
| 344.2 | `sales_order_line` by order (P2.8) | seq scan of 1.7M rows per order detail |
| 150.0 | payments `?status=DISPUTED` (P1.3) | no usable index; partial predicates don't match |
| 92.4 | transaction ledger (P1.7) | partials only; unfiltered scan |
| 85.5 | `sales_invoice_line` by invoice (P2.10) | seq scan of 1.2M rows |
| 82.4 | fulfillments `?status=SHIPPED` (P1.5) | partial covers PENDING only |
| 30.3–31.6 | payment ledger / money story / health rollup (P1.3) | org-wide payment scans |
| 21.5–23.7 | fulfillment ledger / shipment story (P1.5) | same shape |
| 0.7–3.7 | **orders & invoices worklists, refunds** | see corrections below |

**Corrections the measurement forced:**
- `sales_order` and `sales_invoice` org-scoped reads are largely saved by their
  `(org_id, order_number)` / `(org_id, invoice_number)` **uniques** — the org_id prefix gives an
  index path and the per-org row set sorts in memory. Real but modest wins remain (sort removal);
  they drop below the line-table FK indexes in priority.
- `refund` (34.5k rows in this seed — refunds are rare events) and `user_org_role` (4 users/org)
  measure sub-4ms *at this cardinality*. The gaps are structural, the indexes still correct and
  nearly free, but they are not where the headline numbers live. Honest ranking: **FK line-table
  indexes and the payment/transaction/fulfillment status+ledger indexes first.**

## 5b. Phase 3 — V66 applied, identical re-measurement

`V66__Read_path_indexes.sql` (26 indexes, ~13s build over 16.3M rows). Same script, same org,
warm run: `tools/seed/results/after_v66.txt`. Highlights (full table in the results files):

| read | before | after | × |
|---|---|---|---|
| order lines (order detail) | 344.23 ms | 0.018 ms | 19,124 |
| fulfillments `?status=SHIPPED` | 82.40 ms | 0.016 ms | 5,150 |
| invoice lines | 85.46 ms | 0.023 ms | 3,716 |
| payments `?status=DISPUTED` | 149.98 ms | 0.051 ms | 2,941 |
| transaction ledger | 92.36 ms | 0.069 ms | 1,339 |
| order money story | 31.56 ms | 0.035 ms | 902 |
| order shipment story | 21.51 ms | 0.026 ms | 827 |
| payments ledger | 30.18 ms | 0.048 ms | 629 |
| health rollup aggregates | 30.34 ms | 0.833 ms | 36 |
| refunds worklist | 3.72 ms | 0.109 ms | 34 |

Honest flats — reported, not hidden: orders ledger 1.09→0.92 ms and invoices ledger
0.76→1.23 ms (both were already prefix-served by their `(org_id, number)` uniques; an
unfiltered `created_at` ledger cannot be ordered by a status-composite index — the invoice
ledger's ±0.5 ms wobble is plan-choice noise at this cardinality, watch it, don't chase it).
`user_org_role` and the notification fan-out are ~flat at 800 rows — the index is
future-proofing, and the numbers say so.

Write-side cost recorded: payment 2→5 secondary indexes, sales_order 4→6, fulfillment 1→4,
sales_order_line 0→2. All inserts remain single-row per business event; bulk write paths
(seed COPY) are not production paths. jOOQ codegen note: indexes don't change generated
application code; `Indexes.java` refreshes on the next `-Pcodegen` run.

## 5c. Phase 3b — HTTP p50/p95 through the full servlet stack

`tools/bench/http_bench.py` (stdlib client: real login, warm keep-alive connection, 100
sequential samples per endpoint) against the perfdb backend; V66 dropped for the before-run
and re-applied after. Raw: `tools/seed/results/http_before.txt` / `http_after.txt`.

| endpoint | before p50 / p95 | after p50 / p95 | p50 × |
|---|---|---|---|
| order shipment story | 127.6 / 198.4 | 5.1 / 8.1 | 25 |
| transactions ledger | 90.7 / 143.5 | 4.5 / 6.3 | 20 |
| payments `?DISPUTED` | 75.3 / 102.1 | 6.4 / 9.9 | 12 |
| order detail (lines) | 72.4 / 115.3 | 5.3 / 8.7 | 14 |
| payments ledger | 57.8 / 91.7 | 4.0 / 5.4 | 14 |
| order billing story | 53.7 / 82.2 | 5.2 / 17.8 | 10 |
| fulfillments `?SHIPPED` | 53.3 / 112.1 | 5.5 / 9.5 | 10 |
| order money story | 37.4 / 56.8 | 5.0 / 7.7 | 7 |
| health rollup | 35.1 / 53.9 | 14.1 / 32.7 | 2.5 |
| invoices `?ISSUED` | 5.2 / 7.5 | 7.2 / 13.9 | ~1 (flat) |
| refunds `?PENDING` | 9.4 / 17.9 | 9.8 / 45.5 | ~1 (flat) |

Reading the two layers together:
- The HTTP floor is ~4–5 ms (JWT filter + Redis token check + Jackson + Tomcat) — that is
  the asymptote every indexed read now sits on. Endpoints that run several queries per
  request (worklist = list + count; detail = header + lines + context) multiplied the win.
- The shipment story is the biggest *felt* win (127→5 ms): it stacked two unindexed scans
  (fulfillments-by-order over 771k + lines batch over 1.3M) in one request.
- `EXPLAIN (ANALYZE)` inflated the worst scans (344 ms for order-lines vs ~72 ms real HTTP):
  per-row timing instrumentation is expensive on multi-million-row scans. The HTTP layer is
  the honest user-felt number; the EXPLAIN layer is the honest *diagnosis*. Report both.
- Flats stay flat: invoices `?ISSUED` and refunds `?PENDING` were already fast (prefix-
  served / small table) — at HTTP level the stack floor dominates them entirely.

Remaining: the case-study write-up with before/after plan excerpts — the portfolio artifact.

## 5. What phase 2 must do before any index lands

Seed realistic volume (ABO-derived catalog + generated orders/ledgers), capture
`EXPLAIN (ANALYZE, BUFFERS)` + HTTP p50/p95 per endpoint above, apply P1→P2→P3 in order,
re-measure identically after each batch, and record write-side cost (index count per table ×
insert rate) so the case study shows the tradeoff was weighed, not ignored.
