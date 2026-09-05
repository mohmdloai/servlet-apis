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

## Running the full stack against perfdb

perfdb boots the real app: after seeding, copy the Flyway history in (so startup doesn't
try to re-migrate) and create the bench login once:

```bash
docker exec inventory_db sh -c \
  "pg_dump -U postgres --data-only -t inventorydb.flyway_schema_history inventorydb | psql -q -U postgres -d perfdb"
# bench user: see "Login" below — created with a known bcrypt hash, email pre-verified,
# OWNER of three stores + platform ADMIN (the run.sh output names the granted slugs).
```

### perfdb schema state: hand-migrated to V96 (2026-09-05)

**perfdb's schema and Flyway history are both at V96** (V95 cash shift + V96 Web Push applied
2026-09-05 by the standing procedure below; V87–V94 on 2026-09-04; history 96 rows, row-for-row
with dev; V95 adds `cash_shift`/`cash_movement` + `payment_transaction.cash_shift_id` +
`org.shift_required`, V96 `push_subscription` + `notification_delivery_push` + the widened channel
CHECKs and the partial unique on `notification_delivery` — no data touched by any of them; the
V96 measurement is `results/failed_emails_channel_177.txt`). Before that: It got there by hand in psql — Flyway is
still never pointed at it, and the history-copy recipe above is for a *fresh* reseed only (on an
already-populated perfdb it PK-collides with the existing rows; see the gotcha below). What was
applied, and how:

- **V73 + V75 indexes** were already physically present — created by hand during slices 2 and 3's
  measurements and kept. That is exactly why booting the app against perfdb used to crash:
  startup Flyway saw history at V72, tried V73, and hit `relation "refund_pending_global_idx"
  already exists` (42P07). The failed migration rolled back cleanly; no repair was ever needed.
- **V74** (JobRunr v016 view), **V76** (`platform_audit.org_id` + two indexes; the three backfills
  are no-ops here — `platform_audit` is empty) and **V77** (`org_milestone` + backfill, ~4 s) were
  applied 2026-07-28 by running the migration files verbatim through
  `psql --single-transaction -f`, with `PGOPTIONS="-c search_path=inventorydb"`.
- **History rows 73–77** were then INSERTed, copied verbatim from the dev `inventorydb`'s
  `flyway_schema_history` (`pg_dump --column-inserts`, filtered to those versions) so the
  checksums match the files in the jar and startup validation passes.
- **V78–V86** (notification claim lease, `customer.phone_e164` + backfill over all 200,001 seeded
  customers ~9 s, order delivery contact, `customer.locale`, WhatsApp tables + widened channel CHECKs,
  `org.discoverable`, V84 deadline clearing (1 order, 0 reservations), category/collection image keys)
  were applied 2026-08-26 by the standing procedure below, one version at a time — DDL first, then
  that version's history row. History is now row-for-row identical to dev (86 rows).

**The standing procedure when a new migration lands on dev** (V87 and beyond), before benching:

```bash
# 1. apply the DDL by hand, never via Flyway:
docker cp repository/src/main/resources/db/migration/V87__*.sql inventory_db:/tmp/
docker exec -e PGOPTIONS="-c search_path=inventorydb" inventory_db \
  psql -U postgres -d perfdb -v ON_ERROR_STOP=1 --single-transaction -f /tmp/V87__*.sql
# 2. copy that version's history row from the dev DB (checksum must match the jar):
docker exec inventory_db sh -c \
  "pg_dump -U postgres --data-only --column-inserts -t inventorydb.flyway_schema_history inventorydb" \
  | grep "VALUES (87, '87'" \
  | docker exec -i inventory_db psql -U postgres -d perfdb -v ON_ERROR_STOP=1
```

Gotcha on the fresh-reseed recipe above: it assumes an empty history table. On a live perfdb the
piped `pg_dump --data-only` collides with the existing rows on the `installed_rank` PK — copy only
the missing versions' rows (step 2's shape) instead.

**What the platform-plane data honestly shows on perfdb** — the seeder populates commerce only, so:
`platform_audit` is empty (no `ORG_CREATE` rows → the funnel's `path=provisioned` cohort is empty
and `path=self_serve` is all 200 orgs), and `GET /api/admin/funnel` reports **ACTIVATED = 3** — the
only orgs whose OWNER has a verified email are the bench user's three stores. That is the truth of
the seeded data, not a bug in the funnel; REGISTERED/CATALOGUED/PUBLISHED/FIRST_ORDER/FIRST_PAYMENT
all read 200. Verified end-to-end 2026-07-28: the app boots against perfdb with no migration
attempt and the funnel serves under the bench login.

**Login** (works for the org dashboards *and* the platform console):
- email `bench@bench.test` · password `benchpass-123`
- OWNER of `store-102`, `store-103`, `store-104`; ADMIN sees all 200 orgs under `/admin`

**Backend** (beside the dev stack — the launcher takes `SERVER_PORT`):

```bash
# from servlet-apis/
set -a; source .env.local; set +a
DB_URL="jdbc:postgresql://localhost:5433/perfdb?currentSchema=inventorydb" \
SERVER_PORT=8081 ORDER_SWEEPER_BACKGROUND_ENABLED=false mvn exec:java -pl api
```

**Frontends** (from `frontst/`). Two things bite here:

- The `/api` rewrite target is baked at **build** time (`BACKEND_API_URL` build arg), so a
  different backend port means a rebuild, not just a restart.
- Both apps are built with `output: 'standalone'` (for the Docker image). **`next start`
  does not work with standalone** — it boots a degraded server whose *server-side* `fetch`
  throws `transformAlgorithm is not a function`, so RSC loaders (`/api/me`, `/api/orgs`)
  fail and the platform console shows "Console unavailable" even though the browser and
  login work. Run the standalone `server.js` instead, and set `BACKEND_API_URL` at
  **runtime** too (server-side `apiBaseUrl()` reads it live; without it, RSC fetches hit
  the dead default `:8080`).

```bash
# build (bakes the rewrite target)
BACKEND_API_URL=http://localhost:8081 pnpm --dir apps/admin build
BACKEND_API_URL=http://localhost:8081 pnpm --dir apps/storefront build

# standalone needs static + public copied in once per build (the Dockerfile does this in prod)
for app in admin storefront; do
  SA=apps/$app/.next/standalone/apps/$app
  cp -r apps/$app/.next/static "$SA/.next/static"
  [ -d apps/$app/public ] && cp -r apps/$app/public "$SA/public"
done

# run the standalone servers (runtime BACKEND_API_URL + PORT)
BACKEND_API_URL=http://localhost:8081 PORT=3000 node apps/admin/.next/standalone/apps/admin/server.js
BACKEND_API_URL=http://localhost:8081 PORT=3200 node apps/storefront/.next/standalone/apps/storefront/server.js
# admin      → http://localhost:3000  (login bench@bench.test / benchpass-123; console at /en/admin)
# storefront → http://localhost:3200/en/store-102  (org slug required; store-0 … store-199)
```

Note: `run.sh` seeds image *rows* with real ABO object keys and never uploads bytes to
MinIO — bytes don't touch query plans, so the benchmark path must not pay for them. Every
listing image therefore renders as the app's broken-image affordance. If you are driving
perfdb as a **demo storefront** rather than a benchmark, fill one org (below).

### Image bytes for one org

One org is enough — the point is a store that renders, not 657,998 objects. `image_tiles.py`
writes one placeholder per image row, carrying the product's **own name**, so the picture can
never contradict the title beside it. They are SVG (no image library needed, ~700 B each) served
under the existing `.jpg` keys, so the sync **must** override Content-Type — a browser renders by
Content-Type, not by extension.

```bash
ORG=store-102        # 728 listings / 3272 images ≈ 13 MB, ~10 s to sync
docker exec inventory_db psql -U postgres -d perfdb -tAF$'\t' -c "
  select pli.object_key, pli.sort_order, coalesce(t.title,'Item')
    from inventorydb.product_listing_image pli
    join inventorydb.org o on o.id = pli.org_id
    left join inventorydb.product_listing_translation t
           on t.listing_id = pli.listing_id and t.language='en'
   where o.slug='$ORG'" > /tmp/keys.tsv

python3 tools/seed/image_tiles.py /tmp/keys.tsv /tmp/tiles
AWS_ACCESS_KEY_ID=minioadmin AWS_SECRET_ACCESS_KEY=minioadmin AWS_DEFAULT_REGION=us-east-1 \
  aws --endpoint-url http://localhost:9100 s3 sync /tmp/tiles/ s3://catalog-images/ \
      --content-type image/svg+xml --only-show-errors
```

They are placeholders and look like it. For a tenant that looks like an actual shop — real
photos, real barcodes — seed the Open Food Facts org instead (below).

## The demo org: Open Food Facts (`mart-cairo`)

`off_catalog.py` is a **sibling** of `abo_catalog.py`, not a replacement. ABO gives 200 orgs of
bulk for query plans; this gives **one** org that behaves like an Egyptian mini-market, and it is
**additive** — `off_load.sh` adds a tenant beside the 200 and touches nothing else, so it is safe
against a populated perfdb (unlike `run.sh`, which drops the database).

Why it exists: **`abo_catalog.py` writes NULL for every barcode**, so `GET /products?barcode=`,
scan-to-stock and the in-store sale have no seeded data at all. OFF is keyed on EAN-13/UPC.

```bash
# 1. cache the country slice (OFF search is ~10 req/min — this is slow and polite; the cache means
#    you do it once). See off_fetch.py in the story branch, or use any OFF export.
# 2. category display names (optional but worth it)
curl -A "ststore-seed/1.0" https://world.openfoodfacts.org/data/taxonomies/categories.json \
  -o /tmp/off_categories.json

OWNER=$(docker exec inventory_db psql -U postgres -d perfdb -tAc \
  "select id from inventorydb.app_user where email='bench@bench.test'")
python3 tools/seed/off_catalog.py /tmp/off_egypt.jsonl /tmp/offout \
        --owner "$OWNER" --taxonomy /tmp/off_categories.json
./tools/seed/off_load.sh   /tmp/offout mart-cairo     # additive, idempotent
./tools/seed/off_images.sh /tmp/offout /tmp/off-images  # real photos → MinIO
```

Storefront: `http://localhost:3200/ar/mart-cairo` · admin: the bench user is its OWNER.

**Licence:** OFF data is ODbL, its photos CC-BY-SA. Fine for a local dev seed; **not** a licence to
put those photos in marketing material without attribution — verify the terms before publishing.

## Migration mismatches: NEVER delete the volume

perfdb and the dev `inventorydb` live in **one Postgres cluster on one volume**
(`servlet-apis_postgres_data`). `docker compose down -v` destroys BOTH — dev data and the
seeded benchmark database — permanently. This has happened once; don't repeat it.

The situation that tempts it: you switch to a branch whose migration files don't include a
version the shared DB already has applied ("applied but missing"), and Flyway validation
fails. The non-destructive playbook, in order of preference:

1. **Scratch database** (best): run codegen/tests against a clone instead of the shared DB —
   `CREATE DATABASE scratch; pg_dump --schema-only inventorydb | psql scratch` — the same
   trick this harness uses for perfdb. The shared DB should only ever hold **master-lineage**
   migrations.
2. **Repair**: delete the stray row from `flyway_schema_history` and revert its DDL by hand
   (usually one column/table), or `flyway repair`.
3. **Temporarily copy** the missing `V*_.sql` file in from the other branch just for codegen.

And at merge time: whichever branch merges **second** must verify its migration number is
still above every applied version (renumber if not) — otherwise Flyway on prod silently
skips it.
