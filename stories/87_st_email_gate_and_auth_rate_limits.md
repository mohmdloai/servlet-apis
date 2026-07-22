# 87 — Story: Email quality gate (disposable blocklist + MX check) & close the auth rate-limit gap

> **Status: IMPLEMENTED.** Branch `87_feat/email-gate-and-auth-rate-limits` (PR #87 — verify with
> `gh pr list --state all`). **Frontend pair: none** — server-side only; rejections surface
> through the existing `ApiError` 400 body that forms already render. Ships dark: the MX leg is
> behind `EMAIL_MX_CHECK_ENABLED` (default off); the blocklist leg is active immediately.
>
> **What this is.** Slice A of the register-spam pair (slice B = story 88, verify-to-activate).
> Today every email ingress point validates **syntax only** (`Text.normalizeEmail` →
> `EmailAddresses.isSingleValid`) — a gibberish-but-well-formed address at `@nosuchdomain.xyz` or
> `@mailinator.com` sails through, creates rows, and burns SMTP sends. This story adds one shared
> **`EmailGate`** (disposable-domain blocklist + DNS MX check via dnsjava) applied per-flow with
> per-flow strictness, and closes an unrelated-but-adjacent hole found while mapping the ingress
> points: **`POST /api/auth/register` and `/api/auth/forgot-password` are completely unthrottled**
> (they fall through `RateLimitFilter`'s final fail-open `else`).

**As** the operator of a public storefront platform,
**I want** obviously-undeliverable and known-throwaway email addresses stopped at the door — and the
open auth endpoints rate-limited like every other public surface —
**so that** spam registrations don't mint live accounts, junk addresses don't pollute the CRM, and
the SMTP sender isn't an anonymous free-mail cannon.

---

## The reality this corrects

1. **Syntax is the only gate.** `EmailAddresses.isSingleValid` (Jakarta Mail strict parse +
   header-injection guard) is applied at every ingress — register, portal OTP, checkout, forgot
   password — but nothing checks the domain can receive mail or isn't a burner service.
2. **Two auth endpoints are unthrottled.** `RateLimitFilter` buckets `/api/auth/login` and
   `/refresh`, but `/register` and `/forgot-password` hit the fail-open `else`
   (`RateLimitFilter.java:178`). Register creates a live account + org per call; forgot-password
   emails an arbitrary-ish address per call. Both are scriptable at line rate today.
3. **Checkout and OTP already have per-IP buckets** (`rl:pub-checkout`, `rl:portal-otp-req`) and OTP
   has a per-email send throttle — those stay untouched; this story adds the *quality* dimension.

## The design

### 1 · `EmailGate` (`service/.../email/EmailGate.java`)

One constructor-wired service (manual DI via `AppConfig`, like everything else) with a single
entry point taking an **already-normalized** email (callers keep calling `Text.normalizeEmail` +
`EmailAddresses.isSingleValid` first — the gate is layer 2/3, not a replacement):

```
enum Verdict { OK, DISPOSABLE, UNDELIVERABLE }
Verdict check(String normalizedEmail)
```

Composed of two checks, in order (cheap first):

- **Disposable blocklist.** A vendored snapshot of
  `disposable_email_blocklist.conf` from `disposable-email-domains/disposable-email-domains`
  (CC0 — no attribution obligations) at
  `service/src/main/resources/email/disposable_email_blocklist.conf`, loaded once at construction
  into a `HashSet<String>`. **Registrable-domain matching**: test the full domain, then strip the
  leftmost label and retest until one label remains (`a.b.mailinator.com` → `b.mailinator.com` →
  `mailinator.com`). `EMAIL_BLOCKLIST_PATH` env overrides the classpath resource (drop an updated
  file on the VPS without a rebuild); a missing/unreadable override → empty set + WARN (fail-open,
  never a startup failure).
- **MX deliverability** — behind a `MxResolver` interface so the DNS dependency stays swappable and
  unit tests need no network:
  ```
  enum MxResult { DELIVERABLE, UNDELIVERABLE, UNKNOWN }
  MxResult lookup(String domain)
  ```
  `DnsJavaMxResolver` (new dep **`dnsjava:dnsjava` 3.6.x** — zero transitive deps): MX lookup;
  **no MX records → fall back to A/AAAA** (RFC 5321 implicit MX); **null MX** (single record `.`,
  RFC 7505) → `UNDELIVERABLE`; NXDOMAIN / empty everything → `UNDELIVERABLE`; timeout / SERVFAIL /
  any resolver exception → `UNKNOWN`. Resolver timeout **`EMAIL_MX_TIMEOUT_MS`, default 2000**.
  Wrapped in a Redis verdict cache (same Jedis pool as everything else): key `email:mx:{domain}`,
  TTL **1h for DELIVERABLE, 5min for UNDELIVERABLE**, `UNKNOWN` never cached; a Redis error →
  uncached live lookup (fail-open).

**Verdict mapping is deliberately asymmetric:** `UNKNOWN` maps to `OK`. A flaky resolver must never
block a real signup or sale — only *proven* undeliverability rejects. Master toggle
**`EMAIL_MX_CHECK_ENABLED`, default `false`** (ships dark): when off, the MX leg short-circuits to
`OK` and the blocklist still applies.

### 2 · Per-flow wiring (three call sites, three strictnesses)

| Flow | Call site | Behaviour on `DISPOSABLE` / `UNDELIVERABLE` |
|---|---|---|
| Merchant register | `AccountService.register` (after the existing syntax check) | **Strict** — 400 `ValidationException`, cause-naming: "disposable email addresses are not accepted" / "email domain cannot receive mail" |
| Portal OTP request | `CustomerAuthService.requestCode` (before the customer lookup) | **Silent, MX-only** — on `UNDELIVERABLE` skip the send but still return the uniform `200 {sent:true}` (no oracle; the code could never arrive anyway). **Blocklist deliberately not applied** — a disposable-email customer can legitimately exist via lenient checkout and must still be able to log in |
| Anonymous + portal checkout | `SalesOrderService.resolveCustomer` | **Lenient** — evaluate both legs, WARN-log the verdict + domain, **never reject** (a sale with a throwaway email beats no sale) |

Forgot-password needs no gate — it only ever mails addresses of *existing, active* accounts.

### 3 · `RateLimitFilter` — close the fail-open gap

Two new per-IP fixed-window buckets, same Redis INCR + 60s TTL pattern, env read in `init()` with
defaults on unset/unparseable:

- `rl:auth-register` — `POST /api/auth/register`, **`AUTH_REGISTER_LIMIT`, default 3/min**
- `rl:auth-forgot` — `POST /api/auth/forgot-password`, **`AUTH_FORGOT_LIMIT`, default 5/min**

`/reset-password` and `/activate` stay unbucketed on purpose: they redeem 256-bit single-use tokens
— the token space is the rate limit.

### 4 · `AppConfig` wiring

Construct `DnsJavaMxResolver` → Redis-cached wrapper → `EmailGate` (blocklist stream + resolver +
`JedisPool` + toggles); inject into `AccountService`, `CustomerAuthService`, `SalesOrderService`.
All three new env vars documented in CLAUDE.md's env section.

## Scope

### In
`EmailGate` + `MxResolver`/`DnsJavaMxResolver` + Redis MX cache; the vendored blocklist resource +
`EMAIL_BLOCKLIST_PATH` override; the three call-site wirings; the two `RateLimitFilter` buckets;
env plumbing in `AppConfig`; CLAUDE.md env docs; unit + filter tests.

### Out
- **Verify-to-activate registration** — story 88 (slice B).
- **A public "is this email valid?" pre-check endpoint** — enumeration/abuse surface; validation
  happens at submit, the frontend regex covers as-you-type UX.
- **SMTP-level verification (RCPT TO probing)** — hostile to deliverability reputation, widely
  blocked; MX presence is the right depth.
- **Runtime blocklist fetching / auto-update** — snapshot updates are a deploy concern; the env
  override covers hotfixes.
- **Per-domain send throttling in the notification sweeper** — separate concern, not touched.

## Acceptance criteria

1. **Register, disposable:** `POST /api/auth/register` with `x@mailinator.com` (and
   `x@sub.mailinator.com`) → 400 naming disposable emails; no `app_user` row created.
2. **Register, dead domain (MX on):** with `EMAIL_MX_CHECK_ENABLED=true`, a syntactically valid
   email at an NXDOMAIN domain → 400 naming deliverability; with the toggle off (default) → passes.
3. **Fail-open:** resolver timeout/SERVFAIL (`UNKNOWN`) → register succeeds; Redis down → gate
   still answers (live lookup or `OK`), never a 500.
4. **OTP, silent skip:** `request-code` for an existing customer at an `UNDELIVERABLE` domain →
   `200 {sent:true}`, `EmailSender` never invoked; for a disposable-domain **existing** customer →
   code sent normally (blocklist not consulted).
5. **Checkout never blocked:** anonymous checkout with a disposable/undeliverable email → 201,
   customer row upserted, WARN logged.
6. **Rate limits:** the 4th `POST /api/auth/register` and 6th `POST /api/auth/forgot-password`
   from one IP inside 60s → 429; other `/api/auth/*` paths keep their existing buckets/fail-open.
7. **Verdict caching:** two gate checks for the same domain inside the TTL perform one DNS lookup.

## Tests

- **`EmailGateTest` (service, unit, fake `MxResolver`):** blocklist exact / subdomain-strip / miss;
  verdict precedence (disposable wins over MX); `UNKNOWN → OK`; toggle-off short-circuit; override
  path missing → empty set, no throw.
- **`DnsJavaMxResolverTest` (unit):** result mapping (null MX, no-MX-with-A fallback, NXDOMAIN,
  exception → `UNKNOWN`) with the dnsjava `Lookup` seam stubbed; the impl stays thin.
- **`AccountServiceTest`:** strict rejection messages; gate stubbed.
- **`CustomerAuthServiceTest`:** AC 4 both branches.
- **`SalesOrderServiceTest`:** AC 5 — verdict never alters the placement result.
- **`RateLimitFilter` IT (api module, Testcontainers Redis):** AC 6, mirroring the existing bucket
  tests.

## New dependencies

`dnsjava:dnsjava` 3.6.x (managed in the root `pom.xml`, used by `service`). Blocklist file is a
static resource, not a dependency.

## Definition of done

All ACs green; `mvn test` green across modules; ships **dark** (MX toggle off, blocklist active —
the blocklist alone is zero-risk); CLAUDE.md documents `EMAIL_MX_CHECK_ENABLED`,
`EMAIL_MX_TIMEOUT_MS`, `EMAIL_BLOCKLIST_PATH`, `AUTH_REGISTER_LIMIT`, `AUTH_FORGOT_LIMIT`.

## Guard rails (do not regress)

- **Never** let the gate throw out of a checkout or OTP path — lenient/silent modes swallow
  everything; strict mode throws only `ValidationException`.
- **Never** cache `UNKNOWN` — a transient resolver blip must not stick for an hour.
- **Never** apply the blocklist to portal OTP while checkout stays lenient — that combination locks
  real customers out of their own orders.
- The `200 {sent:true}` uniformity on `request-code` is an anti-enumeration property — no gate
  outcome may change the response shape or status.
