# 90 — Fix: verify-email / resend-verification 401 at the auth filter (never reached the servlet)

> **Status: IMPLEMENTED.** Branch `90_fix/anonymous-auth-whitelist` (PR #90 — verify with
> `gh pr list --state all`). **Frontend pair: none.** The remaining prod defect behind the story
> 88/89 rollout: both new anonymous endpoints were dead on arrival.

---

## The defect

`JwtAuthFilter`'s anonymous whitelist enumerates the auth-bootstrap routes
(`login/refresh/register/forgot-password/reset-password/activate`) — story 88 added
`POST /api/auth/verify-email` and `POST /api/auth/resend-verification` to `AuthServlet` **without
adding them to the whitelist**. In production every call to either returned
`401 {"message":"Missing authentication token"}` before the servlet ever saw the request
(verified live against `api.yabta3.com`):

- clicking a (correctly-hosted) verification link → the frontend's dead-link state
  ("invalid or has expired") — the 401 is indistinguishable from a bad token to the client;
- every "Resend link" click → "Something went wrong" — so users could not recover, and nobody
  could obtain a fresh (post-89, admin-hosted) link either.

**Why no suite caught it:** the backend ITs drive `AccountService` directly (no Tomcat filter
chain) and the frontend e2e runs against the Node mock. The filter/servlet coupling had zero
coverage.

## The fix

- `JwtAuthFilter`: add the two paths to the anonymous whitelist. Both are anonymous *by design* —
  the caller has no session yet; verification is what unlocks login. Rate limiting still applies
  (`rl:auth-resend`; verify-email is deliberately unbucketed — 256-bit one-shot token).
- **Regression pin**: new `JwtAuthFilterAnonymousPathsTest` (plus a package-private test
  constructor on the filter, mirroring `RateLimitFilter`'s pattern) asserts every anonymous
  `/api/auth/*` route passes the filter without any token, the `/api/public/` + `/api/portal/`
  prefixes pass, and a guarded path without a token 401s without chaining. The path list in the
  test mirrors `AuthServlet`'s anonymous surface 1:1 — a future anonymous endpoint that forgets
  the whitelist fails this test instead of failing in prod.

## Deploy note (also covers the story-89 "still store links" report)

A store-hosted link in an already-received email is from the pre-89 build — old emails don't
rewrite themselves, and with resend 401-broken (this fix) nobody could get a fresh one. After this
deploys, confirm on the VPS that the backend container carries the 89 env
(`docker compose exec backend printenv ADMIN_BASE_URL` → `https://admin.<domain>`; if empty,
re-pull `deploy/docker-compose.prod.yml` and `docker compose up -d backend`), then do a **fresh
register (or resend)** — the new email must link to `admin.<domain>/verify-email?token=…`.

## Acceptance criteria

1. `POST /api/auth/verify-email` and `POST /api/auth/resend-verification` reach the servlet with
   no auth token (no more filter 401); all previously-whitelisted routes unchanged.
2. Guarded routes still 401 without a token.
3. The whitelist/servlet coupling is pinned by a unit test.
