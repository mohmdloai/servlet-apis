#!/usr/bin/env bash
# Load an off_catalog.py TSV set into perfdb as ONE ADDITIONAL org.
#
# Additive and non-destructive by design — the opposite of run.sh, which drops the database. It
# adds an org beside the 200 ABO ones and touches nothing that exists, so it is safe to run against
# a populated perfdb. Re-running is safe too: the org id is derived from its slug, and the script
# deletes that org's own rows first (FKs cascade from `org`).
#
#   ./off_load.sh <tsv-dir> [org-slug]
set -euo pipefail
cd "$(dirname "$0")"

DIR=${1:?usage: off_load.sh <tsv-dir> [org-slug]}
SLUG=${2:-mart-cairo}
CONTAINER=${CONTAINER:-inventory_db}
PGUSER=${PGUSER:-postgres}
DB=${DB:-perfdb}

psql_() { docker exec -i "$CONTAINER" psql -q -v ON_ERROR_STOP=1 -U "$PGUSER" -d "$DB" "$@"; }
copy() { psql_ -c "COPY inventorydb.$1 ($2) FROM STDIN" < "$DIR/$1.tsv"; }

ORG_ID=$(cut -f1 "$DIR/org.tsv")
echo "── org $SLUG ($ORG_ID) → $DB"

# Idempotence: drop a previous load of THIS org only. The FKs to `org` are plain references with
# no ON DELETE CASCADE, so a single `DELETE FROM org` is rejected — the children go first, in
# dependency order. Scoped to this one org id throughout; it can never touch an ABO tenant.
psql_ <<SQL
BEGIN;
DELETE FROM inventorydb.product_listing_image       WHERE org_id = '$ORG_ID';
DELETE FROM inventorydb.product_listing_translation WHERE listing_id IN
       (SELECT id FROM inventorydb.product_listing WHERE org_id = '$ORG_ID');
DELETE FROM inventorydb.product_listing_category    WHERE listing_id IN
       (SELECT id FROM inventorydb.product_listing WHERE org_id = '$ORG_ID');
DELETE FROM inventorydb.product_listing             WHERE org_id = '$ORG_ID';
DELETE FROM inventorydb.inventory                   WHERE org_id = '$ORG_ID';
DELETE FROM inventorydb.product                     WHERE org_id = '$ORG_ID';
DELETE FROM inventorydb.category_translation        WHERE category_id IN
       (SELECT id FROM inventorydb.category WHERE org_id = '$ORG_ID');
DELETE FROM inventorydb.category                    WHERE org_id = '$ORG_ID';
DELETE FROM inventorydb.user_org_role               WHERE org_id = '$ORG_ID';
DELETE FROM inventorydb.org                         WHERE id     = '$ORG_ID';
COMMIT;
SQL

copy org                         "id, name, slug, active, default_locale"
copy user_org_role               "user_id, org_id, role"
copy category                    "id, org_id, parent_category_id, slug"
copy category_translation        "id, category_id, language, name"
copy product                     "id, name, description, base_price, sku, org_id, barcode"
copy product_listing             "id, org_id, product_id, slug, sales_price, status, published_at, featured_sort"
copy product_listing_translation "id, listing_id, language, title, marketing_copy"
copy product_listing_category    "listing_id, category_id"
copy product_listing_image       "id, org_id, listing_id, object_key, alt_text, sort_order"
copy inventory                   "product_id, stock_qty, reserved_qty, org_id"

psql_ -c "SELECT
  (SELECT count(*) FROM inventorydb.product        WHERE org_id='$ORG_ID') AS products,
  (SELECT count(*) FROM inventorydb.product        WHERE org_id='$ORG_ID' AND barcode IS NOT NULL) AS with_barcode,
  (SELECT count(*) FROM inventorydb.product_listing WHERE org_id='$ORG_ID' AND status='PUBLISHED') AS published,
  (SELECT count(*) FROM inventorydb.category       WHERE org_id='$ORG_ID') AS categories"
