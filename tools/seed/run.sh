#!/usr/bin/env bash
# Seed perfdb at production scale (phase 2, docs/query-index-audit.md §5).
# Idempotent: drops and recreates perfdb (schema cloned from inventorydb) every run.
#
#   ABO_DIR=/path/to/listings/metadata ./run.sh
#
# Knobs (env): ORGS=200 CUSTOMERS_PER_ORG=1000 ORDERS=1000000 LOG_ROWS=5000000 SEED=42
set -euo pipefail
cd "$(dirname "$0")"

CONTAINER=${CONTAINER:-inventory_db}
PGUSER=${PGUSER:-postgres}
ABO_DIR=${ABO_DIR:?set ABO_DIR to the extracted ABO listings/metadata directory}
ORDERS=${ORDERS:-1000000}
LOG_ROWS=${LOG_ROWS:-5000000}
OUT_DIR=${OUT_DIR:-./out}

psql_perf() { docker exec -i "$CONTAINER" psql -q -U "$PGUSER" -d perfdb "$@"; }

echo "── reset perfdb (schema clone of inventorydb)"
docker exec "$CONTAINER" psql -q -U "$PGUSER" -c "DROP DATABASE IF EXISTS perfdb" -c "CREATE DATABASE perfdb"
docker exec "$CONTAINER" sh -c "pg_dump -U $PGUSER --schema-only inventorydb | psql -q -U $PGUSER -d perfdb"

echo "── parse ABO → TSVs"
ABO_DIR="$ABO_DIR" OUT_DIR="$OUT_DIR" ORGS="${ORGS:-200}" \
  CUSTOMERS_PER_ORG="${CUSTOMERS_PER_ORG:-1000}" SEED="${SEED:-42}" python3 abo_catalog.py

echo "── COPY catalog"
copy() { psql_perf -c "COPY inventorydb.$1 ($2) FROM STDIN" < "$OUT_DIR/$1.tsv"; }
copy org                         "id, name, slug, active, default_locale"
copy app_user                    "id, email, password_hash, actor_type"
copy user_org_role               "user_id, org_id, role"
copy category                    "id, org_id, parent_category_id, slug"
copy product                     "id, name, description, base_price, sku, org_id, barcode"
copy product_listing             "id, org_id, product_id, slug, sales_price, status, published_at, featured_sort"
copy product_listing_translation "id, listing_id, language, title, marketing_copy"
copy product_listing_category    "listing_id, category_id"
copy product_listing_image       "id, org_id, listing_id, object_key, alt_text, sort_order"
copy inventory                   "product_id, stock_qty, reserved_qty, org_id"
copy customer                    "id, email, org_id, name, phone, address"

echo "── generate transactions (orders=$ORDERS, log_rows=$LOG_ROWS) — this is the long step"
psql_perf -v orders="$ORDERS" -v logrows="$LOG_ROWS" -f - < transactions.sql

echo "── row counts"
psql_perf -c "SELECT relname, n_live_tup FROM pg_stat_user_tables
              WHERE schemaname='inventorydb' AND n_live_tup > 0
              ORDER BY n_live_tup DESC LIMIT 25"
