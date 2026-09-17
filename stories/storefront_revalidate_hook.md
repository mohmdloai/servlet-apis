# Slice: tell the storefront when a store's profile changed (revalidate hook)

> **Branch `199_feat/storefront-revalidate-hook` off `master`, no migration.** Backend half of
> frontend story 165 (`frontst` branch `168_fix/storefront-architecture-defects`, defect 3 —
> "caching policy is implicit"). The frontend tags its cached reads and exposes
> `POST /api/revalidate`; this slice is the caller.

---

## The reality this corrects

The storefront caches `GET /api/public/{orgSlug}` for 300 s (ISR, matching our own
`Cache-Control: max-age=300`). Nothing told it when the answer changed, so **the first production
Paymob connect was invisible for five minutes**: the OWNER connected, `payment_methods` became
`["instapay","card"]` here at once, and the store's own checkout kept offering InstaPay only until
the TTL ran out. The frontend patched the one page (a 60 s read on checkout) — a smaller number, not
a mechanism. A TTL is a guess about when data changes; the backend *knows*.

## Goal

Every committed write that changes the public profile tells the storefront to drop that org's
cached profile. The TTL stays as the backstop (a missed call costs five minutes, as today); it stops
being the knob.

Done means: connect Paymob → the hook fires once for that org's slug → the storefront's next render
shows card. No hook configured → byte-identical to today.

---

## The wire contract (shared with frontend story 165)

```
POST {STOREFRONT_REVALIDATE_URL}
Authorization: Bearer {STOREFRONT_REVALIDATE_SECRET}
Content-Type: application/json

{"org_slug":"mart-cairo","scopes":["profile"]}
```

- `scopes` is a closed set — `profile | catalog | content | reviews`. **This slice only ever sends
  `profile`**; the other three are reserved so the next write path (banners, pages, listings,
  reviews) adds a call site, not a protocol.
- The storefront answers `200 {"revalidated":[…tags]}`, `401` (bad secret), `503` (hook disabled on
  its side), `400` (malformed). **The backend treats anything but 2xx — and any timeout or refused
  connection — as one WARN line and nothing more.** No retry, no queue: the TTL is the retry.

## Design

```
write(tx) ── commit ──▶ notifier.profileChanged(org)      -- after transactionResult returns
                          slug ← org.slug  (or SELECT by id; never inside the write's tx)
                          revalidator.revalidate(slug, {PROFILE})
                            sendAsync(POST, 2 s connect + 2 s request)   -- caller never waits
                            non-2xx | timeout | IOException → log.warn   -- never thrown
rollback / validation failure ──▶ nothing fires
```

- **Port** `service/storefront/StorefrontRevalidator` — `revalidate(String orgSlug, Set<Scope>)`.
  `JdkStorefrontRevalidator` is the JDK `HttpClient` implementation (no dependency added);
  `StorefrontRevalidator.NOOP` is what `StorefrontRevalidatorFactory` returns when **either** env
  var is unset — the `WebPushSenderFactory` rule: the app boots and behaves exactly as before.
- **`StorefrontCacheNotifier`** is what services hold: `profileChanged(UUID orgId)` /
  `profileChanged(String slug)`. It resolves the slug on the root DSL *after* the commit and
  swallows every exception — a cache hint must never fail, slow or roll back a merchant's write.
  Same post-commit placement as `PlatformOrgService.suspend → orgStatus.invalidate`.
- **Over-invalidate, never under.** `OrgService.update` fires on every successful update, not only
  when a profile field moved: a wasted revalidation is one cheap re-fetch, a missed one is the bug.
- The secret is never logged; the URL is (once, at boot).
- Constructors: each wired service gains an overload taking the notifier; the existing signatures
  delegate to a no-op notifier, so the ~15 ITs that build these services by hand are untouched.

### Write sites wired (everything `StorefrontService.profile` reads)

| Profile field(s) | Write |
|---|---|
| `payment_methods` | `OrgPaymobService.connect`, `.disconnect` |
| `whatsapp_enabled` | `OrgWhatsAppService.connect`, `.setEnabled`, `.disconnect` |
| `name`, `logo_url`, `theme_color`, `default_locale`, `instapay_handle`, `payment_instructions`, `meta_*`, `og_image_version`, `tax_rate`, `shipping_fee`, `discoverable` | `OrgService.update` (tenant), `PlatformOrgService.updateOrg` (console) |
| 200 ↔ 404 (`org.active`) | `PlatformOrgService.suspend`, `.reactivate`; `OrgService.delete` (slug read before the delete) |

`slug` is immutable (no update path writes it), so there is no "old slug" to chase.

---

## Scope

### In

- The port, the JDK client, the factory, the notifier; `AppConfig` wiring from
  `STOREFRONT_REVALIDATE_URL` + `STOREFRONT_REVALIDATE_SECRET`.
- The write sites in the table above.
- `deploy/`: compose passthrough for both services, `env.prod.example`, a README note.

### Out

- **`catalog` / `content` / `reviews` call sites** (listings, categories, collections, banners,
  pages, reviews, comments). The protocol reserves the scopes; those reads keep their 60–300 s TTLs
  until a story wires them. None of them has produced a "my change is invisible" report.
- **PENDING → active on email verification** (`activateRegistrationPendingOrgs`) and **provision**:
  a store that has never resolved has no cached `200` to correct.
- Retry / outbox. A hint that is lost costs what every change costs today.
- Multiple storefront replicas (one URL is one instance). Production is a single container.

---

## Tests

- `JdkStorefrontRevalidatorTest` (service, JDK `HttpServer` stub): method, path, bearer header,
  content type and exact JSON body; a `500`, a hung server (timeout) and a refused connection each
  complete without throwing.
- `StorefrontRevalidatorFactoryTest`: URL or secret unset/blank → `NOOP`; both set → the JDK client.
- `StorefrontCacheNotifierTest`: a throwing revalidator or a throwing slug lookup never escapes;
  an unknown org id fires nothing.
- `StorefrontRevalidateHookIT` (api, Testcontainers, a recording fake): Paymob connect, reconnect
  and disconnect; WhatsApp connect / disable / disconnect; tenant update; console update, suspend,
  reactivate; org delete — each fires **exactly one** `profile` revalidation for the right slug. A
  validation failure, a 409 (no credential key) and an update of an unknown org fire **none**.

## Acceptance criteria

1. With both env vars set, a Paymob connect produces one `POST` to the URL carrying the bearer
   secret and `{"org_slug":…,"scopes":["profile"]}`.
2. With either unset, no request is ever built and every wired write behaves exactly as before.
3. A storefront that is down, slow (> 2 s) or answering `401/503/500` never fails, delays or rolls
   back the write; one WARN line names the slug and the status, never the secret.
4. A write that throws or rolls back fires nothing.
5. The hook fires after commit, so a revalidated read can never race ahead of the data it re-reads.

## Operator

`STOREFRONT_REVALIDATE_SECRET` — `openssl rand -hex 32`, the **same value** on the backend and the
storefront service (compose passes one `.env` variable to both). The URL is compose-wired to
`http://storefront:3000/api/revalidate` on the internal network. Note `store.<domain>` proxies every
path to the storefront, so the route is publicly reachable — the bearer secret is its only guard.

---

## Built (2026-09-17, branch `199_feat/storefront-revalidate-hook`, no migration)

- `service/storefront/`: `StorefrontRevalidator` (port + `Scope` + `NOOP`),
  `JdkStorefrontRevalidator` (`sendAsync`, 2 s connect + 2 s request, WARN on anything but 2xx),
  `StorefrontRevalidatorFactory`, `StorefrontCacheNotifier`.
- Wired after commit in `OrgPaymobService.connect/disconnect`, `OrgWhatsAppService.connect/
  setEnabled/disconnect`, `OrgService.update/delete`, `PlatformOrgService.updateOrg/suspend/
  reactivate`. The two `update` bodies are untouched — each public method became a thin wrapper over
  a private `…InTx`, so the diff is the hook and nothing else.
- `AppConfig` builds one notifier from `STOREFRONT_REVALIDATE_URL` + `STOREFRONT_REVALIDATE_SECRET`.
- Tests: 11 unit (`service`), 6 IT (`StorefrontRevalidateHookIT`); the ITs of every wired service
  re-run green on the old constructors (the no-op path).
- The storefront side is frontend story 165; until it ships the hook answers 404 → one WARN per
  change, nothing else.
