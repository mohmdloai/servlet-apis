#!/usr/bin/env python3
"""Fetch the Open Food Facts slice for one country into a JSONL cache.

Polite by construction: OFF's search endpoint is rate-limited (their docs say ~10 req/min), so
this sleeps between pages and sends a contact User-Agent as they ask. Cached to disk so the
seeder can be re-run without re-fetching.
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request
import subprocess

COUNTRY = os.environ.get("OFF_COUNTRY", "egypt")
OUT = sys.argv[1]
PAGE_SIZE = 100
DELAY = float(os.environ.get("OFF_DELAY", "6.5"))
UA = "ststore-seed/1.0 (dev seeding; contact hussin25soli@gmail.com)"

FIELDS = ",".join([
    "code", "product_name", "product_name_ar", "product_name_en", "generic_name",
    "brands", "categories_tags", "quantity", "image_front_url", "image_front_small_url",
])


def page(n):
    # curl, not urllib: the same request from urllib gets a 503 (the edge in front of OFF is
    # picky about client fingerprints), while curl with the contact UA is served fine.
    q = urllib.parse.urlencode({
        "countries_tags_en": COUNTRY, "page_size": PAGE_SIZE, "page": n, "fields": FIELDS,
    })
    # -f so an HTTP 503 is a non-zero exit rather than an HTML body that fails to parse as JSON.
    # OFF rate-limits search at roughly 10 req/min; --retry-all-errors backs off through it.
    out = subprocess.run(
        ["curl", "-sS", "-f", "-m", "90", "--retry", "5", "--retry-delay", "15",
         "--retry-all-errors", "-A", UA,
         f"https://world.openfoodfacts.org/api/v2/search?{q}"],
        capture_output=True, check=True,
    )
    return json.loads(out.stdout)


for attempt in range(4):
    try:
        first = page(1)
        break
    except Exception as e:
        print(f"page 1 attempt {attempt + 1} failed: {e}", flush=True)
        time.sleep(20 * (attempt + 1))
else:
    raise SystemExit("could not fetch page 1")
total = first["count"]
pages = (total + PAGE_SIZE - 1) // PAGE_SIZE
print(f"{total} products in '{COUNTRY}' → {pages} pages", flush=True)

with open(OUT, "w", encoding="utf-8") as fh:
    written = 0
    for p in first["products"]:
        fh.write(json.dumps(p, ensure_ascii=False) + "\n")
        written += 1
    for n in range(2, pages + 1):
        time.sleep(DELAY)
        for attempt in range(3):
            try:
                body = page(n)
                break
            except Exception as e:  # transient 5xx / timeout — back off and retry
                print(f"  page {n} attempt {attempt + 1} failed: {e}", flush=True)
                time.sleep(10 * (attempt + 1))
        else:
            print(f"  page {n} GIVEN UP", flush=True)
            continue
        for p in body["products"]:
            fh.write(json.dumps(p, ensure_ascii=False) + "\n")
            written += 1
        if n % 5 == 0 or n == pages:
            print(f"  page {n}/{pages} · {written} products", flush=True)

print("cached:", written, flush=True)
