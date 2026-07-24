# Seed harness — production-scale data for the index case study

Phase 2 of `docs/query-index-audit.md`: measurements only mean something at realistic volume,
so this harness builds a dedicated `perfdb` (schema cloned from `inventorydb` — the dev DB is
never touched) and fills it from two sources:

1. **Catalog — real data.** [Amazon Berkeley Objects](https://amazon-berkeley-objects.s3.amazonaws.com/index.html)
   listings metadata (~147k products): titles/descriptions/brands → `product` +
   `product_listing_translation` (en from ABO; ar synthesized from a real-Arabic word bank so
   the V62 `fold_search` generated columns and trigram indexes see representative text),
   `product_type` → per-org `category` trees, ABO image ids → `product_listing_image` rows
   under the org-scoped object-key scheme. Image **bytes** are deliberately not uploaded —
   they never touch a query plan. Products are distributed round-robin across the generated
   orgs (default 200).
2. **Transactions — synthetic, state-machine consistent.** No public dataset matches this
   schema's order/money model, so `transactions.sql` generates it: orders with a realistic
   status mix (incl. paid-then-cancelled to feed refunds), lines priced from listings,
   reservations that follow order state (`reserved_qty == Σ ACTIVE` kept honest), one live
   invoice per DELIVERED fulfillment (partial-unique respected, counters seeded ahead of
   minted numbers), 1:1 transaction↔payment, allocations, orphan/unverified queue noise,
   credit-note-backed and direct refunds, and an `inventory_log` ledger with coherent
   running balances.

Deterministic (single seeded RNG on the Python side; SQL side uses `random()` — re-runs give
the same *shape*, not identical rows). Stdlib + psql only, no dependencies.

## Run

```bash
# once: fetch + extract the ABO listings metadata (~83 MB)
curl -LO https://amazon-berkeley-objects.s3.amazonaws.com/archives/abo-listings.tar
tar -xf abo-listings.tar   # → listings/metadata/listings_*.json.gz

ABO_DIR=./listings/metadata ./run.sh
# knobs: ORGS=200 CUSTOMERS_PER_ORG=1000 ORDERS=1000000 LOG_ROWS=5000000
```

Defaults produce roughly: 147k products/listings, ~295k translations, ~400k image rows,
200k customers, 1M orders (~2M lines), ~1.9M reservations, ~800k fulfillments (~1.6M lines),
~650k invoices (+ lines), ~900k transactions/payments, ~600k allocations, ~30k refunds,
~10k credit notes, 5M inventory_log rows.

## Rules of the game (from the audit)

- **No index lands without a before-measurement here.** Dev-scale EXPLAIN proves nothing.
- Measure per audit-gap: `EXPLAIN (ANALYZE, BUFFERS)` warm, twice, keep the second; plus
  HTTP p50/p95 through the running app pointed at perfdb.
- Re-run the identical measurement after each index batch (P1 → P2 → P3), and record the
  write-side cost (index count × insert rate) alongside the read wins.
