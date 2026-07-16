#!/usr/bin/env bash
# Nightly Postgres backup for the prod stack. Dumps the DB from the running `db` container to a
# timestamped, gzipped file and prunes dumps older than RETENTION_DAYS.
#
# Install on the VPS (as the deploy user):
#   chmod +x /opt/ststore/backup.sh
#   crontab -e   →   15 3 * * *  /opt/ststore/backup.sh >> /opt/ststore/backup.log 2>&1
#
# Restore (destructive — targets a fresh/empty DB):
#   gunzip -c inventorydb-YYYYmmdd-HHMMSS.sql.gz | \
#     docker compose -f /opt/ststore/docker-compose.prod.yml exec -T db psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"
set -euo pipefail

cd "$(dirname "$0")"
set -a; [ -f .env ] && . ./.env; set +a

BACKUP_DIR="${BACKUP_DIR:-/opt/ststore/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
COMPOSE="docker compose -f docker-compose.prod.yml"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="${BACKUP_DIR}/${POSTGRES_DB}-${STAMP}.sql.gz"

mkdir -p "$BACKUP_DIR"
echo "[$(date -Is)] dumping ${POSTGRES_DB} → ${OUT}"
$COMPOSE exec -T db pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" | gzip > "$OUT"

echo "[$(date -Is)] pruning dumps older than ${RETENTION_DAYS} days"
find "$BACKUP_DIR" -name "${POSTGRES_DB}-*.sql.gz" -mtime "+${RETENTION_DAYS}" -delete

echo "[$(date -Is)] done ($(du -h "$OUT" | cut -f1))"
