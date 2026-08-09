# Defect Remediation Plan

> **Source.** A multi-agent delivery review (2026-07-16) read the shipped code across auth/platform,
> order-to-cash, catalog/inventory/storefront, and notifications, plus overall test/build health.
> The verdict was that the backend is **delivery-quality** — the hard invariants (multi-tenant
> isolation, stock concurrency CAS, money accounting, auth revocation, public-DTO whitelisting) hold
> under adversarial inspection. This document tracks the concrete defects that review surfaced so they
> can be fixed deliberately rather than lost in prose. It is a **remediation plan, not a feature plan**
> — every item is a fix to already-shipped behavior.
>
> **Status: D1–D10 fixed**, merged as **PR #141** (`141_fix/delivery-review-defects`, story
> `stories/141_st_delivery_review_defects.md`), each with the test the item asked for. Two pieces are
> deliberately still open — **D4's follow-up** (moving the SMTP send outside the DB transaction; its
> must-do half, timeouts, and the lock-contention half, `SKIP LOCKED`, shipped) and **D11**, which
> the plan always scoped as trailing: its per-defect tests landed with their fixes, its broader
> repository-level IT layer has not.
>
> **D12 is new** and did not come from the 2026-07-16 review — it was found while wiring story 141's
> client-side follow-ups, and was the one item here whose symptom was user-visible. Everything above
> it is a review finding; it is kept in the same document because this is where outstanding backend
> defects get re-checked. Per-item status is marked below.
>
> File:line anchors were verified against the working tree on branch
> `77_fix/owner-org-seo-fields-readback` and the code has since moved — read the anchors as
> descriptions of *where the behaviour lives*, not as line numbers to jump to.

## Severity legend

| Sev | Meaning |
|-----|---------|
| **S1 — Security** | An authorization or session weakness reachable by a real actor. Fix first. |
| **S2 — Correctness** | Can leave inconsistent state, drop/duplicate work, or return a 500 where a clean result is defined. |
| **S3 — Reliability / robustness** | Availability, resource-pinning, or config-fragility risk under load or misconfiguration. |
| **S4 — Docs / hygiene** | Wrong or misleading documentation; test-coverage debt. |

## Milestones (recommended order)

1. **M1 — Security** (do first): D1 refund structuring, D2 refresh-reuse revocation, D7 distinct-secret assertion.
2. **M2 — Correctness & reliability**: D3 null-email txn poison, D4 SMTP timeouts + lock hold, D5 online idempotency race.
3. **M3 — Polish & hygiene**: D6 reissue roll-up, D8 CORS parse dedup, D9 login hardening, D10 CLAUDE.md staleness, D11 test-coverage debt, D12 approval-403 error shape.

Each defect below carries **Symptom · Evidence · Fix · Test · Effort** (S ≤ half-day, M ~1–2 days, L > 2 days).

---

## D1 — Refund/credit-note approval threshold can be structured around · **S1** · M

> **FIXED** (story 141). Both paths gate on the money source's running total via one `requireApproval`
> helper; the cumulative trade-off is stated in the code. Tests:
> `CreditNoteRefundIT.threshold_directRefundStructuringIsGatedOnTheAggregate` +
> `threshold_creditNoteStructuringIsGatedOnTheInvoiceTotal`.

**Symptom.** The OWNER approval gate on refunds and credit notes checks only the **single** amount of
the one call against `org.refund_approval_threshold`. A non-OWNER (MANAGER) can split one logical
payout into N sub-threshold calls and drain an above-threshold total with no OWNER approval:
- Split one payment's unallocated balance into N sub-threshold `POST /refunds` direct refunds.
- Issue N sub-threshold credit notes against one invoice (the cumulative cap is vs the invoice
  `grand_total`, not vs the threshold), then refund each.

**Evidence.**
- `service/.../RefundService.java:165` — direct-refund gate: `amount.compareTo(threshold) > 0` (single amount).
- `service/.../RefundService.java:315` — `createDirectPendingInTx` gate: same single-amount shape.
- `service/.../CreditNoteService.java:157` — issuance gate: single `total.compareTo(threshold)`.
- The correct pattern already exists for cancels: `service/.../OrderCancellationService.java:197-216`
  sums `totalToRefund` across all prepayments and gates on the **aggregate**, explicitly to defeat
  structuring (see the comment at `:197-198`). The standalone refund/credit-note paths never got it.
- A helper is already public and ready to reuse: `RefundService.approvalThreshold(txDsl, orgId)`
  (`RefundService.java:657`), and `RefundService.java:651-655` even documents this exact gap.

**Fix.** Gate on the aggregate against the same money source, mirroring `OrderCancellationService`:
- **Direct refunds** (both `create` and `createDirectPendingInTx`): sum the amounts of all
  not-CANCELLED refunds (PENDING + EXECUTED) already drawing on the same `payment_id`, add the new
  `amount`, and require OWNER when that sum exceeds the threshold — under `FOR UPDATE` on the payment
  so concurrent creates serialize.
- **Credit notes** (`CreditNoteService.issue`): the issuance path already computes the invoice's
  cumulative `credited_total` (ISSUED+SETTLED) for the `grand_total` cap; reuse it — require OWNER when
  `credited_total + this.total` exceeds the threshold.
- Trade-off to make explicit in the code comment: this means legitimately-separate refunds over the
  life of one payment/invoice can *cumulatively* cross the threshold and then require OWNER for the
  next one. That is the intended, safe posture (same as cancel) — the threshold is a ceiling on
  unattended payout per money source, not per API call.

**Test.** New `RefundThresholdStructuringIT` / extend `CreditNoteRefundIT`: N sub-threshold direct
refunds on one payment whose sum crosses the threshold → the crossing call is 403-needs-OWNER as a
MANAGER, succeeds as OWNER; same for N credit notes on one invoice. Assert the existing single-refund
threshold tests still pass.

---

## D2 — Refresh-token reuse does not revoke the family (both auth planes) · **S1** · M

> **FIXED** (story 141), as specified: rotation writes a hash→family tombstone (`rt:rotated:*` /
> `crt:rotated:*`, refresh-TTL) and a presented-but-inactive token that resolves to one revokes the
> family **and** denylists its access tokens. Tests:
> `AuthDeviceRevocationIT.reuseOfRotatedToken_revokesTheWholeFamily`,
> `PortalAuthIT.reuseOfRotatedToken_burnsTheWholeFamily`, plus the unknown-token no-op on both.
>
> **AMENDED** (story 141, same branch): the fix as first written could not tell theft from a
> **concurrent refresh**, and the clients have two independent refreshers over one cookie jar — the
> browser's per-context single-flight (two admin tabs = two of them) and a *server-side* bounce
> refresh the middleware runs on any navigation with an expired access token. Either pair can
> present the same token twice, and burning the family on the loser kills the **winner's** fresh
> token — signing the user out on every device over a race nobody could avoid. Rotation now also
> writes `rt:rotated-recent:{hash}` / `crt:rotated-recent:{hash}` with a **10-second TTL**: inside
> that window a re-presentation is a benign race (401, revoke nothing); outside it, the response is
> the full family burn exactly as above. The window is per **hash**, never per family, so a second
> stolen token is judged on its own key. A separate self-expiring key rather than a timestamp in the
> tombstone value: no value format to migrate, no clock arithmetic, and Redis does the cleanup.
> Deliberate cost: a thief who replays within 10 seconds of the legitimate rotation escapes the
> burn and gets a 401 — the same outcome as before D2, and the price of not logging honest users
> out. Tests: `concurrentRefresh_401sTheLoserButLeavesTheWinnersTokenAlive` (staff) /
> `concurrentRefresh_401sTheLoserButLeavesTheWinnersSessionAlive` (portal); the two burn cases above
> now `DEL` the `*-recent:` key through Jedis to elapse the window deterministically rather than
> sleeping for ten seconds.

**Symptom.** On detected reuse of a rotated-away refresh token, the code logs "possible reuse" and
returns 401 — but does **not** revoke the family. In classic rotation theft (the attacker presents the
stolen token first, rotating it), the victim's later presentation of the now-stale token is the only
thing that 401s; the attacker's freshly-minted token stays valid. Reuse detection is effectively a
no-op.

**Evidence.**
- `service/.../auth/AuthService.java:182-185` — `existing.isEmpty()` ⇒ log + throw 401, no family
  revoke. The only `revokeFamily` call in `refresh` (`:197`) is in the *disabled-account* branch, not
  the reuse branch.
- Rotation hard-deletes the old token: `AuthService.java:189` `refreshTokenStore.revoke(tokenHash)`.
  So a reused old token is simply "not found" — indistinguishable from a random invalid token, which
  is *why* the family can't be identified today.
- Identical shape on the customer plane: `service/.../auth/CustomerAuthService.java:196-202`
  (`orElseThrow` on not-found, then `sessionStore.revoke(hash)` on the happy path).

**Fix.** Make rotation leave a traceable tombstone so reuse can be pinned to a family:
- On rotation, instead of (or in addition to) deleting the old hash, write a short-lived record
  `rt:rotated:{oldHash} → familyId` with TTL = refresh-token TTL, in both `RefreshTokenStore` and
  `CustomerSessionStore`.
- In `refresh`, when the presented token is not an active token, look up the tombstone; if it resolves
  to a family, call `revokeFamily(familyId, userId)` (kills every device-token in that family) and then
  401. Unknown/garbage tokens (no tombstone) stay a plain 401.
- This preserves the existing per-device denylist and fail-closed `token_version` semantics — it only
  adds the missing "burn the family on proven reuse" step.

**Test.** Extend `AuthDeviceRevocationIT` and `PortalAuthIT`: rotate a token (T1→T2), then present T1
again → assert 401 **and** that T2 is now also rejected (family revoked), for both the staff and
customer planes.

---

## D3 — A customer with a null/blank email can poison a business transaction · **S2** · S

> **FIXED** (story 141). `resolveCustomerEmail` returns `Optional` and never throws; the email leg is
> skipped before any delivery row is written. `FulfillmentService`'s emailable-customer guard on
> REVIEW_REQUESTED — which existed only to dodge this throw — is gone. Test:
> `NotificationAdversarialIT.blankCustomerEmail_suppressesTheEmailLegAndCommits`. Note `customer.email`
> is `NOT NULL` (V2), so *blank* is the reachable case; null is guarded anyway and pinned as unstorable.

**Symptom.** `resolveCustomerEmail` throws inside the **caller's business transaction** during the
email branch of `notify()`. A customer row with a null/blank email therefore rolls back the whole
business event (order placement, or the PENDING_PAYMENT→PAID flip) instead of merely skipping the email
leg. CLAUDE.md documents "silent when the order has no customer" — but *no-customer* and
*customer-with-null-email* are different paths, and the latter aborts the txn.

**Evidence.**
- `service/.../NotificationService.java:257-259` — `resolveCustomerEmail` throws
  `IllegalStateException` on null/blank email.
- Called at `NotificationService.java:202`, inside the business `txDsl` passed to `notify(...)`.

**Fix.** Treat a missing/blank customer email as a suppressed channel, not an error: skip the email
delivery for that notification (log a warn), keep the in-app leg, and if no deliverable channel remains
finalize the notification `DISPATCHED` with zero email deliveries — exactly as the opt-out path already
does. Return `Optional<String>` from `resolveCustomerEmail` (empty ⇒ skip) rather than throwing.

**Test.** Extend `NotificationAdversarialIT`: place an order for a customer whose email is null →
assert the order commits, the in-app leg exists, no email delivery row is PENDING, and the notification
is finalized DISPATCHED.

---

## D4 — SMTP has no timeouts and the delivery row lock is held across the send · **S3** · S (+ M follow-up)

> **PARTLY FIXED** (story 141). Shipped: the three timeouts (`SmtpTimeoutConfigTest`), and
> `FOR UPDATE **SKIP LOCKED**` on the per-delivery claim so a second tick moves on instead of blocking
> for the winner's whole round-trip (`NotificationAdversarialIT.concurrentTicks_sendExactlyOnce` now
> asserts the loser has finished while the winner is still sending).
>
> **Still open — the send is still inside the txn.** Moving it out needs a claimed state
> (`PENDING → SENDING`: a migration against the `status` CHECK) plus a lease/reaper for rows stranded
> mid-send by a crash — a new failure mode to design, not a mechanical change. With the lock hold now
> bounded at ~25 s and losers no longer serializing, this is a smaller risk than it was; it is left as
> a deliberate decision rather than done badly.

**Symptom.** `mail.properties` sets no connect/read/write timeout, and `dispatchOneEmail` holds
`SELECT … FOR UPDATE` on the delivery row **plus** a pooled DB connection for the entire SMTP
round-trip. A hung Gmail socket pins both the connection and the row lock indefinitely; a few of these
can exhaust the pool. The two findings compound.

**Evidence.**
- `common/src/main/resources/mail.properties` — has `host/port/auth/starttls` but no
  `mail.smtp.connectiontimeout`, `mail.smtp.timeout`, or `mail.smtp.writetimeout`.
- `service/.../NotificationService.java:378-379` (in-app) and the email dispatch path re-fetch the
  delivery via `findDeliveryById` under `FOR UPDATE` and run `send()` inside that per-delivery txn.

**Fix (must-do, S).** Add to `mail.properties`:
`mail.smtp.connectiontimeout=5000`, `mail.smtp.timeout=10000`, `mail.smtp.writetimeout=10000`
(bounded so a hung peer fails fast into the existing RETRIED/FAILED accounting).

**Fix (follow-up, M).** Shorten the lock hold: claim the delivery (flip PENDING→SENDING under a brief
txn, or use `SKIP LOCKED` with a lease column), perform `Transport.send()` **outside** the DB txn/lock,
then re-open a short txn to record SENT/FAILED. This also unblocks a future second delivery node
(today two ticks fetch the same id set and the loser blocks rather than moving on).

**Test.** Unit-test the timeout properties are applied to the `Session`. For the lock-hold change, an
IT asserting two concurrent sweeper ticks don't double-send and don't serialize on the same row
(`NotificationDeliveryIT` extension).

---

## D5 — Online concurrent-duplicate placement returns 500 instead of a 200 replay · **S2** · S

> **FIXED** (story 141). Build + insert run behind a savepoint — necessary, because a constraint
> violation poisons the whole PG transaction and the outer one may belong to the portal checkout. The
> violation on `(org_id, idempotency_key)` re-reads the winner and replays it; the rollback also
> un-claims the order number, so a race leaves no gap. Test: `PlacementIdempotencyRaceIT`, which forces
> the race deterministically (a proxied repository holds both callers after their idempotency read).

**Symptom.** The idempotency short-circuit runs *before* the order-number counter serializes. Two
genuinely-concurrent submits with the same `Idempotency-Key` both see "absent"; the loser then hits the
`(org_id, idempotency_key)` UNIQUE on insert as an uncaught `DataAccessException` → 500. Sequential
retries replay correctly; only the true race is affected. (In-store deliberately returns 409 here, so
this is an online/storefront-path defect only.)

**Evidence.**
- `service/.../SalesOrderService.java:290-291` — `findByIdempotencyKey` short-circuit.
- `service/.../SalesOrderService.java:329` — `repo.insert(order, orderLines)`, whose unique violation
  is not caught on the online path. The in-store path already documents/handles the analogous case at
  `SalesOrderService.java:421-424`.

**Fix.** Wrap the online `repo.insert` so a unique-violation on `(org_id, idempotency_key)` is caught
and converted to a replay: re-read `findByIdempotencyKey` and return the existing order as a 200, the
same contract a sequential replay already gets.

**Test.** New `PlacementIdempotencyRaceIT`: fire two concurrent `POST /public/{slug}/checkout` (or
portal checkout) with the same key → assert exactly one order created and both responses resolve to it
(one 201, one 200), never a 500.

---

## D6 — Reissue can leave an order FULFILLED-not-CLOSED · **S2 (narrow)** · S

> **FIXED** (story 141). Both roll-ups moved into `OrderRollUp`; `reissue` runs `closeIfFullyPaid` in
> the same txn. Tests: `InvoiceVoidReissueIT.reissue_thatFullyPaysTheReplacement_closesTheOrder` and
> the negative `reissue_thatLeavesTheReplacementUnpaid_doesNotCloseTheOrder`.

**Symptom.** `reissue` re-runs `issueForFulfillment`, which can re-allocate prepayment and flip the new
invoice PAID — but the FULFILLED→CLOSED roll-up only runs on delivery events
(`FulfillmentService.maybeRollUpOrder`). An order can be left FULFILLED after a reissue makes its last
live invoice PAID. Reachability is low (ship requires `prepaid ≥ grand_total`, and reissue requires a
zero-allocation invoice), but the state-machine edge is genuinely missing.

**Evidence.**
- `service/.../InvoiceAdminService.java` `reissue` re-invokes `InvoiceService.issueForFulfillment`
  (which can flip the invoice PAID) with no subsequent order roll-up.
- `service/.../FulfillmentService.java` `maybeRollUpOrder` (the only FULFILLED→CLOSED transition) is
  invoked on delivery only.

**Fix.** After a reissue that (re-)allocates the replacement invoice to PAID, re-evaluate the order
roll-up — factor `maybeRollUpOrder` into a shared helper callable from both the delivery path and the
reissue path, run inside the same txn.

**Test.** Extend `InvoiceVoidReissueIT` with a reissue that fully pays the replacement invoice → assert
the order advances to CLOSED (today it asserts no close).

---

## D7 — `CUSTOMER_JWT_SECRET != JWT_SECRET` is never asserted at boot · **S1 (defense-in-depth)** · S

> **FIXED** (story 141). `AppConfig.requireDistinctSigningSecrets` compares the **decoded bytes**, so
> one key spelled two ways in Base64 is caught too. Test: `SigningSecretIsolationTest`.

**Symptom.** The two signing keys are validated for presence/length but never checked to differ. A
misconfiguration that sets them equal silently collapses the cryptographic half of the customer/staff
plane isolation. (The `aud` claim check is the backstop, so this is defense-in-depth, not a live
breach — but it's one missing `if`.)

**Evidence.**
- `api/.../config/AppConfig.java:257-270` — reads and length-checks `JWT_SECRET` and
  `CUSTOMER_JWT_SECRET` independently; no equality assertion. The comment at `:265` says "never the
  staff JWT_SECRET" but nothing enforces it.

**Fix.** After both are read/decoded, throw at boot if the decoded bytes are equal:
`IllegalStateException("CUSTOMER_JWT_SECRET must differ from JWT_SECRET")`. Fail-fast, single guard.

**Test.** Unit test on the config validation: equal secrets → startup throws.

---

## D8 — CORS origin parsing is duplicated with inconsistent trimming · **S3** · S

> **FIXED** (story 141) by the first option: `CorsFilter` takes `AppConfig.corsAllowedOrigins` instead
> of re-parsing the env var. Test: `CorsOriginAllowlistTest`.

**Symptom.** Two code paths parse `CORS_ALLOWED_ORIGINS`. `AppConfig.resolveAllowedOrigins` trims each
entry; `CorsFilter.init` uses `Set.of(envOrigins.split(","))` with **no trim**. A configured value like
`"a, b"` makes the staff CORS filter fail to match `b` (stored as `" b"`) while the portal CSRF Origin
allowlist (fed by the trimmed `AppConfig` set) matches it — two sources of truth that can drift.

**Evidence.**
- `api/.../filter/CorsFilter.java:22` — `Set.of(envOrigins.split(","))`, untrimmed (it does trim the
  *incoming* origin at `:35`, which papers over the bug asymmetrically).
- `api/.../config/AppConfig.java` `resolveAllowedOrigins` (trimmed) + `corsAllowedOrigins` field
  (`:165`, `:275`).

**Fix.** Single source of truth: inject `AppConfig.corsAllowedOrigins` into `CorsFilter` instead of
re-parsing the env var. If the filter must stay env-driven, at minimum trim each split entry.

**Test.** Unit test `CorsFilter` with `"a, b"` → both `a` and `b` match.

---

## D9 — Login is an enumeration oracle with no per-account throttle · **S3 / hardening** · M

> **FIXED** (story 141), all three parts: `PasswordHasher.verifyDummy` on the unknown-email branch;
> `LoginThrottle` (Redis, 10 failures ⇒ 15-min lockout ⇒ **429**), keyed on the presented address so
> the lockout is not itself an oracle; and `SET key 0 NX EX 60` before `INCR` in `RateLimitFilter`, so
> no crash can strand a TTL-less key. Tests: `LoginThrottleIT`, `RateLimitFilterTest.verifyTtlThenCount`.
> **Frontend note:** `POST /api/auth/login` can now answer 429.
>
> **EXTENDED** (story 141, same branch): that 429 now carries **`Retry-After`**. It said "try again
> in a few minutes" and gave a client no way to be more specific, so every caller had to invent a
> guess. The value is the failure counter's **live TTL**, not the flat 15 minutes: the TTL is
> written on the *first* failure of a streak (that is what makes the key un-strandable, above), so
> an account that reaches the limit slowly is already partway through its window when it locks —
> quoting the full length there tells a locked-out person to wait longer than they must. It falls
> back to the full window if the key has no TTL or expired between the `isLocked` check and the
> read, and is floored at 1 second so it can never advise a hot retry loop.
> `TooManyAttemptsException` carries the number and `AuthServlet` writes the header from **one**
> shared `writeAppError`, so the JSON envelope is untouched (`{status, error, message}`) — the
> useful number is a header, not a new field. Tests:
> `LoginThrottleIT.lockoutCarriesRetryAfterSeconds`, `retryAfterFallsBackWithoutACounter`.
> **Not pinned:** the servlet's header write itself. `AuthServlet.init()` reads a live `AppConfig`,
> whose only constructor boots Postgres, Flyway and Redis, so there is no cheap way to drive the
> servlet in a test; the mapping is a three-line `instanceof` in that single shared writer. The
> frontend covers the wire contract from its side (`frontst` story 96).

**Symptom.** Unknown email returns immediately with no hashing while a known email runs bcrypt — a
measurable timing difference for account enumeration. Rate limiting is per-IP fixed-window only (10/min),
so distributed brute-force against one account (rotating IPs) or credential-stuffing across many
accounts is unthrottled. The fixed-window `incr`-then-`expire` can also strand a key with no TTL if the
process dies between the two ops (that IP stays blocked). Note the discipline exists elsewhere — the
forgot-password path is correctly enumeration-safe.

**Evidence.**
- `service/.../auth/AuthService.java` `login` — early return on unknown email before any hash; no
  per-account failed-attempt counter.
- `api/.../filter/RateLimitFilter.java` — per-IP fixed window; `incr` then `expire` as two ops.

**Fix.**
- Run a dummy bcrypt compare against a fixed hash for unknown emails so the timing profile matches.
- Add a per-account failed-attempt throttle/lockout in Redis (keyed by user/email), independent of the
  per-IP bucket, with exponential backoff or a short lockout window.
- Make the rate-limit `incr`+`expire` atomic (SET-with-TTL-then-INCR, or a small Lua script / `SET NX`)
  so a crash can't strand a TTL-less key.

**Test.** Unit/IT: unknown-vs-known email timing bounded within a tolerance; N failed attempts on one
account → locked out regardless of source IP; a stranded-key scenario cannot occur (TTL always set).

---

## D10 — CLAUDE.md is stale about the sales-order list endpoint · **S4** · S

> **FIXED** (story 141). Both lines rewritten; the credit-notes 400 now stands on its own reasoning
> instead of citing the sales-order route. The suggested guard already existed —
> `OrderLookupHandlerAuthTest` asserts the bare GET is a 200 worklist.

**Symptom.** CLAUDE.md says the bare `GET /api/orgs/{orgId}/sales-orders` (no `order_number`) returns
**400**, "route reserved for the future unfiltered list slice." The worklist has actually shipped — the
handler returns a paged `PageResponse` of orders. A second doc line cites the false 400 as precedent,
so the staleness propagates.

**Evidence.**
- Actual behavior: `api/.../servlet/handler/SalesOrderHandler.java:50-51` and `:227-229` — a bare GET
  returns the order worklist; `:246` calls `service.list(orgId, status, page, size)`.
- Stale doc: `CLAUDE.md:173` — "a bare `GET` without the param is a 400 — the route is reserved for the
  future unfiltered list slice."
- Knock-on: `CLAUDE.md:191` (credit-notes) — "same convention as the bare `GET /sales-orders`" cites
  that now-false precedent.

**Fix.** Update `CLAUDE.md:173` to describe the shipped worklist: bare `GET` returns a `PageResponse`
worklist with `?status=&page=&size=` (filtered = queue oldest-first, unfiltered = ledger newest-first,
unknown status → 400); `?order_number=` remains the single-object lookup. Reword `CLAUDE.md:191` so the
credit-notes bare-GET-400 stands on its own rather than citing the sales-order route as precedent.
(Spot-check confirmed the surrounding notification/opt-out/magic-link/env-default lines are accurate.)

**Test.** N/A (docs). Optionally add an assertion in the sales-order handler test that the bare GET is a
200 worklist, so the doc/code can't silently diverge again.

---

## D11 — Test-coverage debt · **S4** · L (ongoing)

> **PARTLY ADDRESSED** (story 141). Every "specific regression that would ship silently today" listed
> below now has a test, written with its fix — except the two direct filter unit tests
> (`JwtAuthFilter`/`CustomerAuthFilter` branch logic) and the `NotificationService` retry/suppression
> unit test, which pair with no defect above. The broader ask — a repository-level IT layer for
> `domain`/`common`/`repository` — is untouched and remains trailing work, as scoped.
>
> Worth recording for whoever picks this up: `*IT.java` classes are **not** run by a plain `mvn test`
> (default surefire includes only `*Test.java`, and no failsafe execution is configured). They run only
> when named — `mvn test -pl api -Dtest='*IT'`. That is a coverage fact in its own right.

**Symptom.** The delivered code is protected by careful implementation but thinly by automated tests.
The suite is ~100 classes, **97 in the `api` module**, following a "thin service tests + heavy
TestContainers ITs" style. Concretely:
- **`domain`, `common`, and `repository` modules have zero tests of their own** — the stock version-CAS,
  the idempotent-restock `ON CONFLICT`, and the PUBLISHED+ORG_ID predicates are verified only
  transitively through Docker-dependent ITs.
- **`service` module has 3 unit tests total** (`OrgBillingProfileValidationTest`, `ReportServiceTest`,
  `DocumentRenderServiceTest`); none of the money or catalog services has a service-level unit test.
- No `@Disabled`/skipped tests exist, and `mvn -o -pl service test` is green (38/38) — the debt is
  breadth, not rot.

**The specific regressions that would ship silently today** (each pairs with a defect above — write
these alongside the fix):
- Concurrent-placement idempotency race (D5).
- Aggregate/structuring threshold on direct refunds and multi-credit-note (D1).
- Reissue-then-CLOSED roll-up (D6).
- Refresh-reuse family revocation, both planes (D2).
- Direct unit tests for `JwtAuthFilter` and `CustomerAuthFilter` branch logic (aud rejection, CSRF
  header/Origin) — currently only exercised transitively.
- A `NotificationService` unit test for the retry/attempt-counter and suppression state machine.

**Fix.** Treat the per-defect tests above as required with their fixes. Separately, add a thin
repository-level IT layer for the three untested modules' hardest guarantees (version-CAS, idempotent
restock, PUBLISHED/org predicates) rather than relying solely on api-level ITs. This is incremental and
can trail M1–M3.

---

## D12 — The approval 403 is not machine-readable, so the client renders the wrong refusal · **S3** · S (after a small refactor)

> **FIXED** (story 143 / `frontst` story 94). The shared writer landed first — `ApiErrors` is now
> the one place an `AppException` decides its wire shape, replacing **33** per-handler copies —
> then `ApprovalRequiredException` carries `required_role` / `threshold_amount` /
> `requested_amount` from the three throw sites. Tests: `CreditNoteRefundIT`'s three D12 cases
> (both money sources plus the serialized body, and an ordinary 403 proven unchanged);
> `useApiErrorMessage.test.tsx` on the client. 1012 api ITs green after the refactor.
>
> **One correction to the symptom below, found while fixing it:** the refusal did *not* render the
> same way everywhere, which is worse than the single wrong string this entry first described. Only
> the order-cancel dialog reached `errors.forbidden`; the refund and credit-note sheets prefer the
> backend's raw message (`e.apiError.message || getErrorMessage(e)`, 24 sites across the admin app),
> so they showed untranslated English naming an internal payment UUID. The "no raw backend message
> reaches a user" rule is real in `useApiErrorMessage` and bypassed at those call sites. Fixing all
> 24 is out of scope — several *parse* the message deliberately — so the approval refusal alone is
> now always localised, via a `useMoneyErrorMessage` hook that keeps the raw preference for every
> other kind.

**Symptom.** A MANAGER who trips the refund/credit-note approval threshold is told **"You don't have
permission to do that."** That is not merely vague, it is wrong: they *do* have permission — the
payout needs a second signature. The admin app already ships the accurate string in both locales
(`errors.approval_required` → "This needs OWNER approval." / "يتطلب هذا موافقة المالك.") and it is
**unreachable**.

**Evidence.**
- `frontst/packages/shared/src/api/errors.ts:137` fires the state on
  `status === 403 && (code === 'APPROVAL_REQUIRED' || env.required_role)`, and reads
  `required_role` / `threshold_amount` / `requested_amount` off the body.
- The backend emits none of them. `ApiError` (`api/.../dto/ApiError.java`) carries only
  `{status, error, message}` — there is no `error_code` field anywhere in `api`, and no
  `required_role` in `api` or `service`. Both halves of that guard are permanently false, so
  execution falls through to the next line, `if (status === 403) return { kind: 'forbidden' }`.
- The numbers exist, but only inside the human-readable message string ("…exceed approval threshold
  N; requires OWNER"), which the frontend never renders by policy (§3.5 — no raw backend message
  reaches a user).

**Why it bites harder since D1.** It predates D1 and is independent of it, but D1 changed the gate
from *this amount* to the money source's **running total** while the client-side pre-warning
(`frontst/packages/shared/src/lib/approval.ts` `needsOwnerApproval`) still compares one amount. The
two used to agree, so a MANAGER normally saw the warning before submitting and rarely met the raw
403. Now the gate trips in cumulative cases the client cannot predict — no warning, then a flat
permission error. The helper is correctly documented as a pre-warning and never the gate, so
nothing is *broken*; the refusal copy is simply wrong in exactly the case D1 exists for.

**Why it wasn't done in story 141.** Adding the fields to `ApiError` is cheap and precedented — the
`ofShortages` pattern, and Jackson omits nulls so no existing client sees a change. The cost is
structural: **there is no central error writer.** Each handler owns a private
`writeError(HttpServletResponse, AppException)`, and the approval 403 can surface from **five** of
them, because the three throw sites fan out:

| Throw site | Reaches | Route |
|---|---|---|
| `RefundService.create` | `RefundHandler` | `POST /refunds` |
| `CreditNoteService.issue` | `CreditNoteHandler` | `POST /credit-notes` |
| `OrderCancellationService` | `SalesOrderHandler` | `POST /sales-orders/{id}/cancel` |
| `RefundService.createDirectPendingInTx` | `FulfillmentHandler` | `POST /fulfillments/{id}/refund` |
| `RefundService.createDirectPendingInTx` | `PaymentTransactionHandler` | `POST /payment-transactions/{id}/refund` |

Five hand-written branches across the money routes, where covering four of five leaves the same 403
machine-readable on some and not others — a worse state than covering none, and a poor trade for
display copy on a branch nobody was reading yet.

**Fix — the refactor first, then this is three lines.** Give `AppException` subclasses one place to
contribute fields to `ApiError`, instead of teaching five handlers about a sixth exception type:

1. Add an `ApprovalRequiredException extends AuthorizationException` carrying `requiredRole`,
   `thresholdAmount`, `requestedAmount`; throw it from the one shared `RefundService.requireApproval`
   helper and from `CreditNoteService` / `OrderCancellationService` (D1 already funnels refunds and
   credit notes through one helper — extend that, don't add a second).
2. Give `ApiError` the three optional fields plus a factory, following `ofShortages`.
3. Collapse the per-handler `writeError(resp, AppException)` bodies onto **one** shared writer that
   both handles this and leaves room for the next status that carries extra data. `AuthServlet`
   already took this shape for `Retry-After` in D9a (`writeAppError`) — the same move, applied to
   the org-scoped handlers.

Step 3 has its own justification independent of this defect (five copies of one error mapping), and
it is what makes this change small rather than five-fold.

**Test.**
- Backend: an IT per plane-of-entry asserting the 403 body carries `required_role` /
  `threshold_amount` / `requested_amount` — at minimum `RefundHandler` and `CreditNoteHandler`
  (extending `CreditNoteRefundIT`, which already drives the D1 aggregate cases), plus one of the
  three `createDirectPendingInTx` routes so the shared writer is proven, not assumed.
- Frontend: a component test that an above-threshold 403 renders `approval_required`, not
  `forbidden`. **No test anywhere asserts the approval path today** — that is why the dead branch
  survived. The 403 assertions that do exist are all about ordinary permission denials on unrelated
  routes (`client.test.ts` "normalises a 403 into a forbidden ApiError" over a PDF read,
  `DocumentActions.test.tsx`, `LoginForm.test.tsx`'s reserved unverified-email 403), and each is
  correct for its own case — none of them would change.

---

## Quick reference

| ID | Title | Sev | Effort | Milestone | Status |
|----|-------|-----|--------|-----------|--------|
| D1 | Refund/credit-note threshold structuring | S1 | M | M1 | **fixed** |
| D2 | Refresh-reuse doesn't revoke family | S1 | M | M1 | **fixed** |
| D7 | Distinct-secret not asserted at boot | S1 | S | M1 | **fixed** |
| D3 | Null-email customer poisons business txn | S2 | S | M2 | **fixed** |
| D4 | SMTP no timeouts + lock held across send | S3 | S (+M) | M2 | **partly** — timeouts + SKIP LOCKED done; send-outside-txn open |
| D5 | Online duplicate placement → 500 not replay | S2 | S | M2 | **fixed** |
| D6 | Reissue leaves order FULFILLED-not-CLOSED | S2 | S | M3 | **fixed** |
| D8 | CORS origin parsing duplicated/untrimmed | S3 | S | M3 | **fixed** |
| D9 | Login enumeration + no per-account throttle | S3 | M | M3 | **fixed** |
| D10 | CLAUDE.md stale sales-order list | S4 | S | M3 | **fixed** |
| D11 | Test-coverage debt | S4 | L | trailing | **partly** — per-defect tests done; repo-level IT layer open |
| D12 | Approval 403 not machine-readable → client renders "forbidden" | S3 | S (after the shared-writer refactor) | M3 | **fixed** |
