# Slice: Public rate limiting (throttle the anonymous storefront surface)

> The safety rail the public write endpoint can't ship without. [`public_storefront_read_api.md`](public_storefront_read_api.md)
> deliberately deferred this — *"Public rate-limiting on `/api/public/*` (the `RateLimitFilter`
> currently guards only auth)."* That was fine while `/api/public/*` was **read-only** (a DRAFT can't
> leak, a GET can't spam an inbox). It stops being fine the moment [`public_checkout.md`](public_checkout.md)
> adds an **anonymous, unauthenticated POST that upserts a customer, reserves stock, mints a magic
> link, and queues an email** — an unbounded write on the open internet. This slice extends the
> existing `RateLimitFilter` to cover `/api/public/*` so B5 can ship safely.
>
> Canonical contract & decisions: [`frontst/docs/storefront-commerce-epic.md`](../../frontst/docs/storefront-commerce-epic.md) §B4.
> Reuses the mechanism from the auth rate limiter (Redis fixed-window counter, `RateLimitFilter`).
> **Must ship with/before** [`public_checkout.md`](public_checkout.md) (B5) — the epic's dependency
> graph pins `B4 → B5` on the can-transact critical path.

---

## Goal

Extend `RateLimitFilter` (`api/.../filter/RateLimitFilter.java`) — today mapped **only** to
`/api/auth/*`, throttling `login` (10/min) and `refresh` (30/min) per IP via a Redis 60s fixed-window
counter — to **also** cover `/api/public/*`, branching by method + path:

- `POST /api/public/{orgSlug}/checkout` → bucket **`rl:pub-checkout:{ip}`**, **strict** default
  **5/min** (a checkout is expensive: customer upsert + stock reservation + magic link + email).
- every other `/api/public/*` request (the GET read model — listings, detail, categories, profile,
  availability, order-view) → bucket **`rl:pub-read:{ip}`**, **generous** default **120/min**.
- over limit → **429** with the existing filter's JSON body (`ApiError.of(429, "Too many requests.
  Try again later.")`). The two auth buckets (`rl:login:`, `rl:refresh:`) are **untouched**.

Done means: the 121st GET from one IP inside a minute is a `429`; the 6th checkout POST from one IP
inside a minute is a `429`; the read and checkout buckets are independent (hammering reads never
locks out a checkout, and vice-versa); `login`/`refresh` throttling is unchanged; the limits are
env-tunable; and — the operationally load-bearing part — **behind a reverse proxy, real client IPs
key the buckets** (not the single shared proxy IP), when the trusted-proxy resolution is configured.

---

## Why now

`public_checkout.md` cannot ship an unbounded anonymous write to the public internet. Without a limit
on `/api/public/*`:

- **Order/inbox spam** — each checkout POST upserts a customer row and **queues a customer email**
  (the `ORDER_PLACED` magic-link mail). An unthrottled loop is a spam cannon pointed at arbitrary
  email addresses, from *our* domain, plus unbounded `customer` / `sales_order` / `inventory_log`
  growth.
- **Stock-reservation DoS** — every accepted checkout **reserves stock** (`inventory.reserved_qty`
  bumped, `inventory_reservation` rows, per-org TTL). A script can reserve a merchant's entire
  catalog to zero available, starving real shoppers until the TTL sweeper releases the holds.
- **Catalog enumeration / scraping** — the read model is anonymous and paginated; an unthrottled
  crawler can scrape every org's full published catalog and hammer the presign path at will.

Rate limiting is the difference between "B5 is a checkout" and "B5 is an abuse endpoint." It is on
the critical path *for* B5, not after it.

---

## Design

- **Map the existing filter to a second URL pattern.** In `EmbeddedTomcatLauncher`, `RateLimitFilter`
  is currently mapped only to `/api/auth/*` (line 43). Add a second URL pattern for `/api/public/*`
  on the **same** filter (a `FilterMap` can carry multiple `addURLPattern` calls, or add a second
  `addFilter(...)` mapping). One filter class, two prefixes — no new class, no per-endpoint wiring.
  The filter already runs after `corsFilter` and (for public) before request handling; `JwtAuthFilter`
  bypasses `/api/public/*` so anonymity is preserved.
- **Branch on method + path inside `doFilter`.** The filter already computes
  `path = getServletPath() + pathInfo` and `ip = getRemoteAddr()`. Extend the existing if/else ladder:
  keep the `endsWith("/login")` / `endsWith("/refresh")` branches; add a `path.startsWith("/api/public/")`
  arm that sub-branches on the HTTP method + suffix:
  - `POST` **and** `path.endsWith("/checkout")` → `keyPrefix = "rl:pub-checkout:"`, `limit = PUBLIC_CHECKOUT_LIMIT`.
  - anything else under `/api/public/*` → `keyPrefix = "rl:pub-read:"`, `limit = PUBLIC_READ_LIMIT`.
  - the final `else` (a path that is neither auth nor public — shouldn't occur given the two mappings)
    stays `chain.doFilter(...)` (fail-open, no limit), exactly as today.
- **Reuse the counter verbatim.** Same three lines that guard auth: `incr(key)`; if `current == 1`
  → `expire(key, WINDOW_SECONDS)`; if `current > limit` → write the 429 body and return. Fixed 60s
  window, per-IP, per-bucket. No change to the mechanism — only new keys and env-driven limits.
- **Client-IP resolution behind a proxy** (see §Trusted proxy — the crux of this slice). `ip` becomes
  a resolved value: `getRemoteAddr()` by default, or the trusted `X-Forwarded-For` first hop when
  `TRUST_PROXY` is on. This is the one non-trivial addition; everything else is a copy of the auth path.
- **Config read in `init(...)`.** The filter already pulls `jedisPool` + `objectMapper` from
  `AppConfig` in `init`. Read the limits and the proxy-trust flag there too (via `AppConfig` /
  env), so `doFilter` stays allocation-free on the hot path. Fall back to the documented defaults
  when a var is unset or unparseable (log once, don't fail startup — a bad limit env shouldn't take
  the storefront down).

---

## Configuration & limits

| Env var | Bucket / effect | Default | Notes |
|---|---|---|---|
| `PUBLIC_READ_LIMIT` | `rl:pub-read:{ip}` — all `/api/public/*` GETs | `120` | requests per 60s window per IP |
| `PUBLIC_CHECKOUT_LIMIT` | `rl:pub-checkout:{ip}` — `POST …/checkout` | `5` | strict; a checkout is an expensive write |
| `TRUST_PROXY` | resolve client IP from `X-Forwarded-For` instead of `getRemoteAddr()` | `false` | **turn on in any proxied deployment** (see §Trusted proxy) |

- The window stays a fixed **60s** (`WINDOW_SECONDS`), matching the auth limiter; not env-exposed in
  v1 (out of scope — see below).
- The auth limits (`LOGIN_LIMIT = 10`, `REFRESH_LIMIT = 30`) are left as-is — this slice does not
  touch, rename, or env-ify the auth buckets. (Making *them* env-tunable is a trivial follow-up but
  is deliberately not bundled here, to keep the diff scoped to the public surface.)
- Documented in the repo's **Required environment variables** style (`CLAUDE.md`), alongside
  `JWT_SECRET`, `CORS_ALLOWED_ORIGINS`, etc. All three are optional with safe defaults, so dev/CI
  need no new config.

---

## Behavior & the 429 shape

Per request under a mapped prefix: resolve the client IP, pick `(keyPrefix, limit)` by method+path,
`incr` the Redis key, set a 60s TTL on first hit, and 429 once the count exceeds `limit`:

```
HTTP/1.1 429 Too Many Requests
Content-Type: application/json

{ "status": 429, "error": "Too Many Requests", "message": "Too many requests. Try again later." }
```

This is the **existing** `ApiError.of(429, …)` body the auth limiter already returns — reused
verbatim, so clients see one consistent 429 shape across auth and public. Under-limit requests pass
through to the servlet unchanged (`chain.doFilter`). The counter is a fixed window: the first request
in a window stamps `EXPIRE key 60`, and the whole window resets 60s later (so a throttled IP recovers
after at most 60s without any per-request TTL bookkeeping). The read and checkout buckets use
**distinct key prefixes**, so their counters are fully independent.

---

## Trusted proxy — the crux (call it out loudly)

**`getRemoteAddr()` behind a CDN/load balancer is the proxy's IP, not the shopper's.** A storefront is
*normally* fronted by a CDN/LB (TLS termination, caching, DDoS scrubbing). If the filter keys buckets
on `getRemoteAddr()` in that topology, **every shopper on earth shares one `rl:pub-*` bucket** — the
proxy's IP — and the store collapses under its own success: the 121st *aggregate* GET (or 6th
*aggregate* checkout) across *all* visitors trips the limit and throttles everyone at once. The rate
limiter becomes a self-inflicted global outage. This is not a theoretical edge — it is the default
outcome the moment the app sits behind a proxy, which is the normal production topology for a
storefront.

**Resolution — config-gated `X-Forwarded-For`:**

- **`TRUST_PROXY=false` (default, dev/local):** key on `getRemoteAddr()`. Correct when the app is
  hit directly — the connecting socket *is* the client. This keeps local dev and the existing tests
  behaving exactly as today.
- **`TRUST_PROXY=true` (proxied prod):** key on the **first hop** of `X-Forwarded-For` — the
  originating client IP the proxy recorded — falling back to `getRemoteAddr()` if the header is
  absent/blank/unparseable. Now two real shoppers behind the same CDN key **independent** buckets.

**Security caveat (why it's gated, not always-on):** `X-Forwarded-For` is a client-supplied header and
is **trivially spoofable** — a caller can send `X-Forwarded-For: 1.2.3.4` and mint a fresh bucket per
forged IP, evading the limit entirely. It is only trustworthy when a proxy **we control** *overwrites*
(not appends to) the inbound header with the true connecting IP before forwarding. Hence the flag: an
operator sets `TRUST_PROXY=true` **only** after confirming their edge proxy strips/overwrites inbound
XFF. Turning it on in a direct-exposure deployment would let anyone bypass the limiter; leaving it off
behind a proxy throttles everyone collectively. Both failure modes are documented next to the var.

**Recommendation: implement this in *this* slice, do not defer it.** A storefront lives behind a proxy
in production essentially by definition (CDN + TLS + WAF is table stakes for a public commerce site).
Shipping the rate limiter without XFF resolution would mean the limiter is either **useless** (one
shared bucket → collective throttling, so operators disable it) or **actively harmful** (a traffic
spike self-DoSes the store). Deferring it defeats the purpose of the slice — the whole point is to make
B5 *safe to expose*, and it isn't safe if the limiter can't tell two shoppers apart. It's ~15 lines
(read a header, split on first comma, trim) gated behind one boolean; the cost of building it now is
negligible against the cost of a store-wide outage on launch day.

---

## Scope

### In
- Map `RateLimitFilter` to `/api/public/*` (second URL pattern in `EmbeddedTomcatLauncher`).
- Method+path branching in `doFilter`: `POST …/checkout` → `rl:pub-checkout:`, else `/api/public/*`
  → `rl:pub-read:`; reuse the existing `incr`/`expire`/429 counter.
- Env-configurable `PUBLIC_READ_LIMIT`, `PUBLIC_CHECKOUT_LIMIT` (read in `init`, safe defaults).
- Config-gated trusted-proxy client-IP resolution: `TRUST_PROXY` → `X-Forwarded-For` first hop,
  else `getRemoteAddr()`.
- Documentation of the three env vars in the **Required environment variables** style.

### Out (deferred)
- **Per-org limits** (`rl:pub-checkout:{orgSlug}:{ip}` or an org-wide cap) — v1 is per-IP only; a
  noisy org can't be isolated yet.
- **Sliding-window / token-bucket / leaky-bucket** algorithms — v1 keeps the existing **fixed-window**
  counter (a burst at a window boundary can briefly do up to 2× the limit; acceptable for v1).
- **CAPTCHA / WAF / bot-scoring / device fingerprinting** — infrastructure/edge concerns, not this
  Redis counter.
- **Distributed coordination beyond the single Redis counter** — no cross-node token leasing, no rate
  smoothing; the existing shared-Redis `INCR` is the whole coordination model.
- **`Retry-After` header / rate-limit headers** (`X-RateLimit-Remaining` etc.) — the 429 body is
  reused as-is; response-header hinting is a later polish.
- **Env-ifying the auth limits** (`LOGIN_LIMIT`/`REFRESH_LIMIT`) — untouched here.

---

## Authorization

**N/A — this is an infrastructure boundary, not an authorization one.** The filter runs before any
servlet, keys off IP (or trusted XFF), and applies to the anonymous public surface where there is no
`SecurityContext` at all (`JwtAuthFilter` bypasses `/api/public/*`). It grants nothing and reads no
roles; it only shapes request volume. The auth buckets it already guards are likewise pre-auth. No
role gate, no org scope, no actor.

---

## File layout

| Module | New / changed |
|---|---|
| `api` | **Changed**: `filter/RateLimitFilter.java` — add the `/api/public/*` method+path branch (`rl:pub-checkout:` / `rl:pub-read:`), read `PUBLIC_READ_LIMIT` / `PUBLIC_CHECKOUT_LIMIT` / `TRUST_PROXY` in `init`, and the trusted-proxy client-IP resolver. Auth branches unchanged. |
| `api` | **Changed**: `EmbeddedTomcatLauncher.java` — map `rateLimitFilter` to `/api/public/*` as well as `/api/auth/*` (second `addFilter`/URL pattern). |
| `api` | **Changed** (if config is centralized): `config/AppConfig` — surface the two limits + `TRUST_PROXY` (env read), mirroring how it exposes `jedisPool`/`objectMapper`. |
| docs | **Changed**: `CLAUDE.md` **Required environment variables** — document `PUBLIC_READ_LIMIT`, `PUBLIC_CHECKOUT_LIMIT`, `TRUST_PROXY`. |

No migration, no new domain/service/repository code — this is a filter + wiring slice.

---

## Acceptance criteria

- [ ] With `PUBLIC_READ_LIMIT=120`: the 1st–120th `GET /api/public/{orgSlug}/listings` from one IP in a
      60s window → `200`; the **121st → `429`** with the standard `ApiError` body.
- [ ] With `PUBLIC_CHECKOUT_LIMIT=5`: the 1st–5th `POST /api/public/{orgSlug}/checkout` from one IP in a
      60s window → its normal status (201/200/4xx); the **6th → `429`** (the limiter fires **before** the
      servlet runs, so it counts attempts regardless of checkout outcome).
- [ ] **Buckets independent:** exhausting `rl:pub-read:` (GETs → 429) does **not** 429 a checkout POST
      from the same IP, and exhausting `rl:pub-checkout:` does **not** 429 a GET. (Keyed on distinct
      prefixes.)
- [ ] **Auth buckets unchanged:** `login` still 429s at 11 and `refresh` at 31 per IP per minute;
      `rl:login:` / `rl:refresh:` behavior and limits are byte-for-byte as before this slice.
- [ ] **Trusted-proxy isolation:** with `TRUST_PROXY=true`, two requests carrying different
      `X-Forwarded-For` client IPs (same connecting socket, i.e. same `getRemoteAddr()`) get
      **independent** buckets — one can be throttled while the other still passes. With
      `TRUST_PROXY=false`, the `X-Forwarded-For` header is **ignored** and both share the
      `getRemoteAddr()` bucket.
- [ ] **Limits env-tunable:** setting `PUBLIC_READ_LIMIT=2` / `PUBLIC_CHECKOUT_LIMIT=1` changes the trip
      points accordingly (the 3rd GET / 2nd checkout → 429); an unset/unparseable var falls back to the
      120/5 defaults without failing startup.
- [ ] **Window resets:** after the 60s window elapses (Redis key TTL expires), a previously-throttled
      IP is served again (the counter starts fresh).
- [ ] A `429` carries `Content-Type: application/json` and the exact body
      `{status:429, error:"Too Many Requests", message:"Too many requests. Try again later."}` — the
      same shape the auth limiter returns.

---

## Tests

**`api/src/test/java/.../filter/RateLimitFilterTest.java`** (unit; mock `Jedis`/`JedisPool`,
`HttpServletRequest`/`Response`, `ObjectMapper` — mirrors any existing filter unit test):
- `POST …/checkout` selects `rl:pub-checkout:` + `PUBLIC_CHECKOUT_LIMIT`; a `GET /api/public/…`
  selects `rl:pub-read:` + `PUBLIC_READ_LIMIT`; `login`/`refresh` still select their auth buckets.
- `incr` returning `1` triggers `expire(key, 60)`; returning `limit+1` writes the 429 and does **not**
  call `chain.doFilter`; returning `≤ limit` calls `chain.doFilter` and writes nothing.
- IP resolution: `TRUST_PROXY=false` → key contains `getRemoteAddr()` and the XFF header is ignored;
  `TRUST_PROXY=true` → key contains the **first** `X-Forwarded-For` hop; blank/absent XFF under
  `TRUST_PROXY=true` falls back to `getRemoteAddr()`.
- limits read from config: overriding `PUBLIC_READ_LIMIT`/`PUBLIC_CHECKOUT_LIMIT` moves the trip point.

**`api/src/test/java/.../filter/PublicRateLimitIT.java`** (TestContainers — real Redis, driven through
Tomcat like the existing public ITs; small limits injected via env/config so the test is fast):
- read limit: N+1th `GET /api/public/{orgSlug}/listings` → 429 (real Redis counter + TTL).
- checkout limit: M+1th `POST …/checkout` → 429 **before** placement (assert no extra `sales_order`
  row was created by the throttled attempt).
- independence: exhaust reads → a checkout POST still passes (and vice-versa).
- auth untouched: `login` regression — the existing auth-limit IT stays green.
- window reset: after the key TTL, the same IP is served again (drive with a short window or an
  explicit Redis key expiry).

**Regression:** the existing auth `RateLimitFilter` tests, `StorefrontIT`,
`StorefrontProfileIT`, and (once landed) `PublicCheckoutIT` stay green — the read/checkout paths are
unchanged below the limit, and the auth buckets are unchanged entirely.

Verified live through Tomcat: with small limits, a `curl` loop of anonymous GETs starts returning
`429` after the read limit; a loop of `POST …/checkout` returns `429` after the checkout limit while
GETs from the same IP still succeed; with `TRUST_PROXY=true`, two `curl`s differing only in
`-H "X-Forwarded-For: …"` throttle independently.

---

## What this unblocks

| Next | Depends on this |
|---|---|
| **`public_checkout.md` (B5) ships safely** | the anonymous checkout POST is bounded — no order/inbox spam, no stock-reservation DoS from an open endpoint. B4 must land with/before B5 per the epic graph. |
| **Storefront read surface hardened** | catalog enumeration / presign-hammering is throttled per real client IP. |
| **Per-org limits, `Retry-After`, sliding window** (later) | build on this filter's bucketing once per-IP fixed-window is proven in production. |
