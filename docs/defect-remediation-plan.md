# Defect Remediation Plan

> **Source.** A multi-agent delivery review (2026-07-16) read the shipped code across auth/platform,
> order-to-cash, catalog/inventory/storefront, and notifications, plus overall test/build health.
> The verdict was that the backend is **delivery-quality** — the hard invariants (multi-tenant
> isolation, stock concurrency CAS, money accounting, auth revocation, public-DTO whitelisting) hold
> under adversarial inspection. This document tracks the concrete defects that review surfaced so they
> can be fixed deliberately rather than lost in prose. It is a **remediation plan, not a feature plan**
> — every item is a fix to already-shipped behavior.
>
> **Status: D1–D10 fixed** on branch `141_fix/delivery-review-defects` (story
> `stories/141_st_delivery_review_defects.md`), each with the test the item asked for. One piece is
> deliberately still open — **D4's follow-up** (moving the SMTP send outside the DB transaction); its
> must-do half (timeouts) and the lock-contention half (`SKIP LOCKED`) shipped. **D11** stays open as
> the plan always scoped it: its per-defect tests landed with their fixes, its broader
> repository-level IT layer is still trailing work. Per-item status is marked below.
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
3. **M3 — Polish & hygiene**: D6 reissue roll-up, D8 CORS parse dedup, D9 login hardening, D10 CLAUDE.md staleness, D11 test-coverage debt.

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
