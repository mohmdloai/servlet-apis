#!/usr/bin/env bash
# Fetch the Open Food Facts product photos named by off_catalog.py's manifest and push them into
# MinIO under the exact keys the seeded rows claim.
#
#   ./off_images.sh <tsv-dir> [work-dir]
#
# Politeness: the photos come from OFF's image CDN (not the rate-limited search API), but it is a
# volunteer-run service — this fetches serially with a short delay and skips anything already on
# disk, so a re-run costs nothing. Licence: OFF photos are CC-BY-SA; fine for a local dev seed,
# and NOT a licence to publish them in marketing material without attribution.
set -euo pipefail
cd "$(dirname "$0")"

DIR=${1:?usage: off_images.sh <tsv-dir> [work-dir]}
WORK=${2:-/tmp/off-images}
BUCKET=${BUCKET:-catalog-images}
ENDPOINT=${ENDPOINT:-http://localhost:9100}
UA="ststore-seed/1.0 (dev seeding)"
DELAY=${DELAY:-0.15}

MANIFEST="$DIR/images_manifest.tsv"
total=$(wc -l < "$MANIFEST" | tr -d ' ')
echo "── fetching $total photos → $WORK"

n=0; got=0; skipped=0; failed=0
while IFS=$'\t' read -r key url; do
  n=$((n + 1))
  dest="$WORK/$key"
  if [ -s "$dest" ]; then skipped=$((skipped + 1)); continue; fi
  mkdir -p "$(dirname "$dest")"
  if curl -sS -f -m 30 --retry 2 --retry-delay 3 -A "$UA" "$url" -o "$dest"; then
    got=$((got + 1))
  else
    # A missing photo is ordinary in a crowdsourced dataset — drop the file so the row simply has
    # no bytes (the app's broken-image affordance), never a zero-byte object pretending to be one.
    rm -f "$dest"; failed=$((failed + 1))
  fi
  sleep "$DELAY"
  [ $((n % 50)) -eq 0 ] && echo "  $n/$total · got=$got skipped=$skipped failed=$failed"
done < "$MANIFEST"
echo "  done: got=$got skipped=$skipped failed=$failed"

echo "── sync → s3://$BUCKET/"
AWS_ACCESS_KEY_ID=${S3_ACCESS_KEY:-minioadmin} \
AWS_SECRET_ACCESS_KEY=${S3_SECRET_KEY:-minioadmin} \
AWS_DEFAULT_REGION=us-east-1 \
  aws --endpoint-url "$ENDPOINT" s3 sync "$WORK/" "s3://$BUCKET/" \
      --content-type image/jpeg --only-show-errors
echo "  objects now in bucket: $(AWS_ACCESS_KEY_ID=${S3_ACCESS_KEY:-minioadmin} \
  AWS_SECRET_ACCESS_KEY=${S3_SECRET_KEY:-minioadmin} AWS_DEFAULT_REGION=us-east-1 \
  aws --endpoint-url "$ENDPOINT" s3 ls "s3://$BUCKET/" --recursive | wc -l | tr -d ' ')"
