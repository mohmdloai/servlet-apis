# Case study: indexing a multi-tenant commerce API — measured, not guessed

> How I took the hottest reads of a Java/jOOQ/PostgreSQL commerce backend from
> **344 ms to 0.018 ms** at the query layer and **25× faster p50** at the HTTP layer —
> and, just as importantly, which indexes I *refused* to add and why.
>
> Everything here is reproducible from this repo: the audit
> (`docs/query-index-audit.md`), the seed harness (`tools/seed/`), the benchmark client
> (`tools/bench/`), the migration (`V66__Read_path_indexes.sql`), and the raw
> measurement files (`tools/seed/results/`).

## TL;DR

| | before | after |
|---|---|---|
| Worst query (order lines, per order-detail view) | 344 ms | **0.018 ms** (19,124×) |
| Worst user-felt endpoint p50 (order shipment story) | 127.6 ms | **5.1 ms** (25×) |
| Payments status worklist p50 | 75.3 ms | 6.4 ms |
| Every indexed read now sits on | — | the ~4–5 ms HTTP stack floor |

Method: **audit every read path → seed production-scale data → measure two layers →
apply only convicted indexes → re-measure identically → report the flats too.**

---

## 1. The trap I was avoiding

The app is a multi-tenant inventory/commerce API: raw Jakarta Servlets on embedded
Tomcat, jOOQ for SQL, PostgreSQL, ~80 endpoints across orders, payments, invoices,
fulfillments, refunds, a public storefront, and a customer portal.

The instinct when something "feels slow" is *add indexes everywhere*. Two problems:

1. **The schema wasn't unindexed.** It already had ~35 deliberate indexes, including
   well-designed partial indexes for every state-machine queue (orphan transactions,
   pending fulfillments, unpaid invoices, active stock reservations). Blanket claims
   would have been both wrong and insulting to the existing design.
2. **A dev database can't convict anything.** With 92 rows in the ledger, `EXPLAIN
   ANALYZE` happily shows sub-millisecond seq scans — the planner is *right* to scan.
   Every "before" number captured at toy scale is theater.

So: no index ships without a measurement at realistic scale that convicts its absence.

## 2. The audit — indexes cover access paths, not tables

I read all 32 repository implementations and extracted every `SELECT`'s true shape:
filter columns, literal state predicates, sort keys, pagination. Then I mapped each
shape against the full index inventory — secondary indexes, partial indexes, unique
constraints, composite primary keys — and gave every read a verdict: *served*,
*partially served*, or *unserved* (`docs/query-index-audit.md` §2).

The single most useful mental model from this exercise: **a table can look thoroughly
indexed while half the paths into it scan.** Three patterns produced almost every gap:

- **FK line tables with no reverse index.** Postgres never auto-indexes the
  referencing side of a foreign key. `sales_order_line`, `sales_invoice_line`,
  `fulfillment_line`, `credit_note_line` — every order-detail read fetches "lines by
  parent id", and every one of those was a full-table scan waiting for volume.
- **The partial-index blind spot.** `fulfillment` had a partial index
  `WHERE status = 'PENDING'` — perfect for the packing queue it was built for, and
  *invisible* to `?status=SHIPPED` on the same endpoint. A partial index optimizes a
  query, not an endpoint: if the filter value is a parameter, check every value the
  parameter takes.
- **Right columns, wrong order.** `user_org_role` has PK `(user_id, org_id, role)` —
  all the columns anyone ever filters on. But a composite btree serves only its
  leading column's direction: user→orgs (the login path) flew; org→users (the member
  roster, the last-owner guard, the staff fan-out inside every order notification)
  scanned. A membership table is read in both directions; it needs both orientations.

The audit also verified every *composite back-reference* (child rows located by a
2–3 column parent key): the translation tables' `(parent_id, language)` uniques, the
notification-delivery `(notification_id, channel)` unique, counters, preference
partial-uniques — all served. Knowing what's *fine* is half the audit.

## 3. Seeding at production scale — real catalog, synthetic ledgers

No public dataset looks like a commerce backend's money tables, so the harness
(`tools/seed/`) uses two sources:

- **Amazon Berkeley Objects** for the catalog: 145,615 real products with titles,
  brands, categories, and image references — mapped into the schema's
  listing/translation model (English from ABO; Arabic synthesized from a real word
  bank so the folded-search generated columns and trigram indexes see representative
  text), distributed across 200 tenant orgs.
- **A state-machine-consistent generator** for everything money: 1M orders with a
  realistic status mix, 1.7M lines, 840k payment+transaction pairs, 696k invoices
  (the live-invoice-per-fulfillment partial unique respected, number counters seeded
  ahead of minted numbers), 771k fulfillments, reservations that keep
  `reserved_qty = Σ ACTIVE`, and a 5M-row inventory ledger with coherent running
  balances. **16.3M rows**, built in minutes, into a dedicated schema-cloned database
  so dev data is never touched.

Worth admitting: the schema fought back, correctly. My first seed created stock
reservations exceeding stock; the database's `CHECK` constraints rejected the state
and the TTL sweeper degraded gracefully (per-order transaction, skip and retry) when
it met the inconsistency. The fix was to make the seeder release the overflow the way
the real system would have. Constraints catching corrupt synthetic data is the system
working — and it's why I trust these numbers.

## 4. Measuring two layers (they disagree, and both are right)

**Layer 1 — the diagnosis:** `EXPLAIN (ANALYZE, BUFFERS)` for all 26 audit-gap query
shapes, parameterized on one fixture org, warm cache, second run kept.

**Layer 2 — the user experience:** a benchmark client that logs in through the real
auth flow and measures full-stack latency (JWT filter → Redis token check → jOOQ →
Jackson) per endpoint, 100 sequential samples after warmup, reporting p50/p95/p99.

Running both exposed something worth knowing: **`EXPLAIN ANALYZE` inflates big
scans.** The order-lines scan measured 344 ms under ANALYZE but ~72 ms over real
HTTP — per-row timing instrumentation is expensive across millions of rows. The plan
tells you *why* it's slow; the HTTP number tells you *how much it hurts*. Report both,
trust each for its own question.

## 5. Deep dives — the plans, side by side

### 5.1 One order's lines: 344 ms → 0.018 ms (19,124×)

Every order-detail view fetches its lines by `sales_order_id`. No index on the FK:

```
Gather  (actual time=344.161..344.220 rows=2)
  Workers Launched: 2
  Buffers: shared hit=96 read=44696
  ->  Parallel Seq Scan on sales_order_line  (actual ... rows=1 loops=3)
        Filter: (sales_order_id = 'b6a39a36-...'::uuid)
        Rows Removed by Filter: 566,666        ← per worker, ~1.7M rows total
Execution Time: 344.230 ms
```

Three parallel workers read 44,696 disk pages to find **two rows**. After
`CREATE INDEX ... ON sales_order_line (sales_order_id)`:

```
Index Scan using sales_order_line_order_idx  (actual time=0.012..0.013 rows=2)
  Index Cond: (sales_order_id = 'e66b0796-...'::uuid)
  Buffers: shared hit=5
Execution Time: 0.018 ms
```

Five buffer hits instead of 44,792. This one line of DDL is most of the headline —
and it's the *least glamorous* index in the migration: a plain FK index that
convention says should have existed from day one.

### 5.2 The partial-index blind spot: `?status=SHIPPED`, 82 ms → 0.016 ms

```
->  Parallel Seq Scan on fulfillment  (actual time=77.903..77.903 rows=0 loops=3)
      Filter: ((org_id = '...') AND (status = 'SHIPPED'::fulfillment_status))
      Rows Removed by Filter: 257,211
Execution Time: 82.400 ms                       ← to return ZERO rows
```

82 ms to discover there is nothing in transit — the existing partial index
(`WHERE status='PENDING'`) simply doesn't contain SHIPPED rows. After the general
`(org_id, status, created_at, id)` index:

```
Index Scan using fulfillment_org_status_idx  (actual time=0.008..0.008 rows=0)
  Buffers: shared hit=3
Execution Time: 0.016 ms
```

An empty result is the *best* case for an index and the *worst* case for a scan — the
scan must prove the negative by reading everything.

### 5.3 The disputes queue: 150 ms → 0.051 ms

Same shape, higher stakes: `?status=DISPUTED` backs the dashboard's health rollup, so
it runs constantly. Before: parallel scan over 840k payments, 17,597 pages read, to
sort 45 matching rows. After: 28 buffer hits, index scan in ordered index sequence —
the `(org_id, status, received_at, id)` index returns rows *pre-sorted* for the
queue's FIFO order, so the Sort node disappears from the plan entirely.

### 5.4 The honest one: `user_org_role`, ~flat — and shipped anyway

The org-first index fixed the design smell from §2. Measured gain today: roughly
none, because the seeded orgs hold 4 users each and 800 rows fit in one page. The
case for shipping it is structural (every org-first read, including the notification
fan-out, scans without it; pain scales with org size), and the case study says
exactly that instead of inventing a win. Honest flats are what make the big numbers
believable.

## 6. What I deliberately did NOT index

- **Substring search** (`ILIKE '%term%'` across name/SKU/marketing copy): btrees
  can't serve infix matches, and one indexed leg inside an OR of unindexed legs still
  scans. The folded search columns already carry GIN trigram indexes; unifying every
  leg onto them is query-shape work, not another index.
- **Cross-status ledger sorts** on orders/invoices: already prefix-served by their
  `(org_id, number)` uniques; measured ~1 ms. A status-composite index cannot provide
  cross-status ordering — adding one would be write cost for nothing.
- **Reports**: `COALESCE(placed_at, created_at)` range windows structurally block
  range indexes. Fix is a persisted column — deferred until a measurement demands it.
- **OFFSET pagination + per-page COUNT**: real cost, wrong tool — that's keyset
  pagination work, not indexing.

Every index is write amplification (payment went 2→5 secondary indexes, fulfillment
1→4 — recorded in the audit). An index no measurement asked for is pure insert tax.

## 7. Results

**Query layer** (identical script, same org, warm — full tables in
`tools/seed/results/`):

| read | before | after | × |
|---|---|---|---|
| order lines (order detail) | 344.23 ms | 0.018 ms | 19,124 |
| fulfillments `?SHIPPED` | 82.40 ms | 0.016 ms | 5,150 |
| invoice lines | 85.46 ms | 0.023 ms | 3,716 |
| payments `?DISPUTED` | 149.98 ms | 0.051 ms | 2,941 |
| transaction ledger | 92.36 ms | 0.069 ms | 1,339 |
| order money story | 31.56 ms | 0.035 ms | 902 |
| payments ledger | 30.18 ms | 0.048 ms | 629 |
| orders / invoices ledgers, `user_org_role` | ~1 ms | ~1 ms | flat, reported |

**HTTP layer** (real login, full servlet stack, p50/p95 over 100 samples):

| endpoint | before p50 / p95 | after p50 / p95 |
|---|---|---|
| order shipment story | 127.6 / 198.4 ms | 5.1 / 8.1 ms |
| transactions ledger | 90.7 / 143.5 | 4.5 / 6.3 |
| payments `?DISPUTED` | 75.3 / 102.1 | 6.4 / 9.9 |
| order detail (lines) | 72.4 / 115.3 | 5.3 / 8.7 |
| fulfillments `?SHIPPED` | 53.3 / 112.1 | 5.5 / 9.5 |
| invoices `?ISSUED` (was already served) | 5.2 / 7.5 | 7.2 / 13.9 (flat) |

Two systemic observations: endpoints multiply query wins (the shipment story stacked
two unindexed scans in one request — biggest felt improvement), and after V66 every
indexed read converges on a **~4–5 ms floor** that belongs to JWT + Redis + JSON, not
the database. That floor is the "reads are done, stop here" signal.

## 8. Reproduce it

```bash
cd tools/seed
curl -LO https://amazon-berkeley-objects.s3.amazonaws.com/archives/abo-listings.tar
tar -xf abo-listings.tar
ABO_DIR=./listings/metadata ./run.sh          # ~16.3M rows into perfdb (dev DB untouched)
# baseline / after: measure_baseline.sql (EXPLAIN layer), ../bench/http_bench.py (HTTP layer)
# indexes: repository/src/main/resources/db/migration/V66__Read_path_indexes.sql
```

## 9. Takeaways

1. **Indexes cover access paths, not tables.** Audit endpoint → query shape → serving
   index; "the table has indexes" answers nothing.
2. **Measure at a scale where the planner's choices are real.** Dev-scale EXPLAIN
   convicts nobody.
3. **Partial indexes optimize a query, not an endpoint.** Parameterized filters need
   the general index too.
4. **Composite key order encodes a direction.** Bidirectional relationships need both
   orientations.
5. **EXPLAIN diagnoses; HTTP testifies.** They disagree on big scans — use each for
   its own question.
6. **Report the flats.** The refusal list and the ~1× rows are what make the 19,124×
   row credible.
