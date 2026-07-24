#!/usr/bin/env python3
"""HTTP-level latency benchmark for the hot org-scoped reads (phase 3b of the case study).

Measures what a user actually feels: full servlet stack — JWT filter, Redis token check,
jOOQ query, Jackson serialization — per endpoint. Sequential sampling (no client-side
queueing) after a warmup, reporting p50/p95/p99/max.

Stdlib only. Usage:
  BASE=http://localhost:8081 ORG=<uuid> ORDER_ID=<uuid> LABEL=after \
    python3 http_bench.py > ../seed/results/http_after.txt

Env: BASE, ORG, ORDER_ID (a CLOSED order of ORG), EMAIL/PASSWORD (bench user),
WARMUP (default 15), SAMPLES (default 100).
"""

import http.client
import json
import os
import statistics
import time
from urllib.parse import urlparse

BASE = os.environ.get("BASE", "http://localhost:8081")
ORG = os.environ["ORG"]
ORDER_ID = os.environ["ORDER_ID"]
EMAIL = os.environ.get("EMAIL", "bench@bench.test")
PASSWORD = os.environ.get("PASSWORD", "benchpass-123")
WARMUP = int(os.environ.get("WARMUP", "15"))
SAMPLES = int(os.environ.get("SAMPLES", "100"))
LABEL = os.environ.get("LABEL", "run")

u = urlparse(BASE)
HOST, PORT = u.hostname, u.port or (443 if u.scheme == "https" else 80)

ENDPOINTS = [
    ("order-detail (lines)",      f"/api/orgs/{ORG}/sales-orders/{ORDER_ID}"),
    ("order-money-story",         f"/api/orgs/{ORG}/sales-orders/{ORDER_ID}/payments"),
    ("order-shipment-story",      f"/api/orgs/{ORG}/sales-orders/{ORDER_ID}/fulfillments"),
    ("order-billing-story",       f"/api/orgs/{ORG}/sales-orders/{ORDER_ID}/invoices"),
    ("payments ?DISPUTED",        f"/api/orgs/{ORG}/payments?status=DISPUTED&page=0&size=25"),
    ("payments ledger",           f"/api/orgs/{ORG}/payments?page=0&size=25"),
    ("transactions ledger",       f"/api/orgs/{ORG}/payment-transactions?page=0&size=25"),
    ("fulfillments ?SHIPPED",     f"/api/orgs/{ORG}/fulfillments?status=SHIPPED&page=0&size=25"),
    ("invoices ?ISSUED",          f"/api/orgs/{ORG}/invoices?status=ISSUED&page=0&size=25"),
    ("refunds ?PENDING",          f"/api/orgs/{ORG}/refunds?status=PENDING&page=0&size=25"),
    ("health rollup",             f"/api/orgs/{ORG}/health"),
]


def login():
    conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
    body = json.dumps({"email": EMAIL, "password": PASSWORD})
    conn.request("POST", "/api/auth/login", body, {"Content-Type": "application/json"})
    resp = conn.getresponse()
    if resp.status != 200:
        raise SystemExit(f"login failed: {resp.status} {resp.read()[:200]}")
    cookies = [h.split(";")[0] for (k, h) in resp.getheaders() if k.lower() == "set-cookie"]
    resp.read()
    conn.close()
    return "; ".join(cookies)


def bench(cookie, path):
    conn = http.client.HTTPConnection(HOST, PORT, timeout=60)  # keep-alive across samples
    headers = {"Cookie": cookie}
    lat = []
    for i in range(WARMUP + SAMPLES):
        t0 = time.perf_counter()
        conn.request("GET", path, headers=headers)
        resp = conn.getresponse()
        data = resp.read()
        dt = (time.perf_counter() - t0) * 1000
        if resp.status != 200:
            conn.close()
            return None, f"HTTP {resp.status}: {data[:120]!r}"
        if i >= WARMUP:
            lat.append(dt)
    conn.close()
    return lat, None


def pct(lat, p):
    s = sorted(lat)
    return s[min(len(s) - 1, int(round(p / 100 * len(s) + 0.5)) - 1)]


def main():
    cookie = login()
    print(f"# label={LABEL} base={BASE} org={ORG} samples={SAMPLES} warmup={WARMUP}")
    print(f"{'endpoint':<28}{'p50':>9}{'p95':>9}{'p99':>9}{'max':>9}  (ms)")
    for name, path in ENDPOINTS:
        lat, err = bench(cookie, path)
        if err:
            print(f"{name:<28} SKIP {err}")
            continue
        print(f"{name:<28}{statistics.median(lat):>9.2f}{pct(lat, 95):>9.2f}"
              f"{pct(lat, 99):>9.2f}{max(lat):>9.2f}")


if __name__ == "__main__":
    main()
