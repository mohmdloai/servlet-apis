# Story 141 — close the delivery-review defects (D1–D10)

**Type:** fix · **Branch:** `141_fix/delivery-review-defects` · **Plan:**
`docs/defect-remediation-plan.md`

The 2026-07-16 multi-agent delivery review found the backend delivery-quality and listed eleven
concrete defects behind that verdict. This story implements **D1–D10** — every code defect in the
plan — plus the per-defect tests D11 names as the coverage that would otherwise ship silently. Each
item below states what changed and what now proves it.

Nothing here is a feature. Every change is a fix to already-shipped behaviour, and every user-visible
contract that moves is called out under *Contract changes*.

---

## M1 — Security

### D1 · The refund/credit-note approval threshold is now an aggregate

**Was:** the OWNER gate compared the **single amount of one call** against
`org.refund_approval_threshold`. A MANAGER could split one payout into N sub-threshold calls —
N direct refunds against one payment, or N credit notes against one invoice (whose only cap was the
invoice's grand total) — and drain an above-threshold total with no OWNER involved.

**Now:** both paths gate on the running total of the same money source, the shape
`OrderCancellationService` already used for exactly this reason.

- `RefundService.create` and `RefundService.createDirectPendingInTx` sum every **non-CANCELLED**
  refund already drawing on that `payment_id` (PENDING + EXECUTED — a PENDING refund is an accepted
  obligation) and add the new amount. Both callers hold `FOR UPDATE` on the payment, so concurrent
  creates cannot both read a stale total.
- `CreditNoteService.issue` gates on `credited_total + this note` — the cumulative sum the
  grand-total cap already computed under the invoice's row lock. One read, two guards.
- Both go through one `requireApproval` helper, so the two paths cannot drift.

**The trade-off, deliberate and documented in the code:** legitimately separate refunds over the life
of one payment or invoice now *cumulate*, so a later small refund can be the one that needs OWNER.
The threshold is a ceiling on unattended payout per money source, not a per-call allowance — the same
posture cancel has had all along.

**Proof:** `CreditNoteRefundIT.threshold_directRefundStructuringIsGatedOnTheAggregate` (300 + 300
crosses a 500 bar; OWNER clears it; cancelling one gives its room back) and
`threshold_creditNoteStructuringIsGatedOnTheInvoiceTotal`. The existing single-refund threshold tests
still pass unchanged.

### D2 · Refresh-token reuse revokes the family, on both planes

**Was:** on reuse of a rotated-away token the code logged "possible reuse" and returned 401 — and
revoked nothing. Rotation hard-deleted the old hash, so a reused token was indistinguishable from
garbage and there was no family to burn. In the classic theft ordering (the thief refreshes first)
the **victim's** later presentation is what 401s, while the thief's fresh token stays valid. Reuse
detection was a no-op.

**Now:** rotation leaves a tombstone — `rt:rotated:{hash} → userId:familyId` (staff),
`crt:rotated:{hash} → orgId:customerId:familyId` (customer), TTL = the refresh-token TTL, hash only,
never the raw token. On a refresh whose token is not active, the tombstone is consulted: if it
resolves, `revokeFamily` kills every token in that family **and** `denyFamilyAccess` kills its
outstanding access tokens, then 401. No tombstone ⇒ an ordinary unknown token ⇒ plain 401, nothing
revoked. The existing per-device denylist and fail-closed `token_version` are untouched.

**Proof:** `AuthDeviceRevocationIT.reuseOfRotatedToken_revokesTheWholeFamily` +
`unknownToken_401sButRevokesNothing`; `PortalAuthIT.reuseOfRotatedToken_burnsTheWholeFamily` +
`unknownRefreshToken_401sButLeavesTheSessionAlone`. The portal's old
`refreshRotates_andReuseOfOldTokenIsRejected` **encoded the defect** (it asserted the rotated-current
token still worked *after* a reuse) and was split into the two tests above.

### D2a · …but a concurrent refresh is not reuse — a 10-second grace window

**The regression D2 introduced.** Burning the family on any presentation of a rotated-away token is
right for theft and wrong for concurrency, and the clients are concurrent by construction. One
cookie jar is worked by **two independent refreshers**:

- the browser client's single-flight guard (`frontst/packages/shared/src/api/client.ts`), which is
  per-JS-context — so two admin tabs are two guards, not one;
- a **server-side** bounce refresh (`apps/admin/app/api/auth/bounce/route.ts`, driven by
  `middleware.ts` on any navigation whose access token is expired or inside its 30 s skew; the
  storefront has the portal twin).

Two tabs, or one tab where a bounce redirect overlaps an in-page 401, present the same token twice.
Only one can win. Before D2 the loser simply got a 401; after it, the loser's presentation burned
the family — **killing the winner's brand-new token and signing the user out on every device**, over
a race nobody did anything wrong in. A `navigator.locks` fix in the browser cannot close this,
because the bounce refresh runs in the Next server process and shares no lock manager with any tab.

**Now:** `rotateAway` writes a second key beside the tombstone — `rt:rotated-recent:{hash}` (staff)
/ `crt:rotated-recent:{hash}` (customer), TTL **10 seconds**, value `"1"`. On a presented-but-
inactive token the order is: `rotated-recent` present ⇒ **401, revoke nothing**; else tombstone
present ⇒ revoke the family + denylist its access tokens + 401; else ⇒ plain 401. Ten seconds is
wide enough for a redirect plus a round trip and narrow enough that a replay minutes later still
meets the full response.

Three decisions worth keeping:

- **A second self-expiring key, not a timestamp inside the tombstone value.** No value format to
  migrate, no clock arithmetic on either plane, and Redis does the cleanup.
- **The window is per hash, never per family.** It is a statement about *one* rotation, so a second
  stolen token presented later is judged on its own key and still burns.
- **The two planes share one constant** (`RefreshTokenStore.ROTATION_GRACE_SECONDS`, read by
  `CustomerSessionStore`), so they cannot drift to different windows.

**The cost, stated:** a thief who replays inside 10 seconds of the legitimate rotation escapes the
burn and gets only a 401 — exactly the pre-D2 outcome, for a 10-second sliver. That is the price of
not logging honest users out, and it is the right trade: the benign race is routine and the
10-second replay is not the shape real token theft takes.

**Proof:** `AuthDeviceRevocationIT.concurrentRefresh_401sTheLoserButLeavesTheWinnersTokenAlive` and
`PortalAuthIT.concurrentRefresh_401sTheLoserButLeavesTheWinnersSessionAlive` — rotate, immediately
re-present the old token, assert 401 **and** that the new token still refreshes and the device was
never denylisted. The two D2 burn tests keep asserting the burn, made deterministic by `DEL`-ing the
`*-recent:` key straight through Jedis to elapse the window — no ten-second `Thread.sleep`.

### D7 · The two signing secrets must differ

**Was:** `JWT_SECRET` and `CUSTOMER_JWT_SECRET` were validated for presence and length,
independently, and never compared. Setting them equal silently collapses the cryptographic half of
the customer/staff plane isolation, leaving only the `aud` claim.

**Now:** `AppConfig.requireDistinctSigningSecrets` throws at boot when the two **decode to the same
bytes** — a string compare would miss one key spelled two ways in Base64. An undecodable value is
left to `JwtUtil`'s own message.

**Proof:** `SigningSecretIsolationTest` (distinct passes, identical throws, padded-vs-unpadded of the
same key throws, garbage is not this guard's problem).

---

## M2 — Correctness & reliability

### D3 · A customer with no email no longer poisons a business transaction

**Was:** `resolveCustomerEmail` threw `IllegalStateException` from inside the **caller's business
transaction**. So a customer row with a blank email did not fail an email — it rolled back the order
placement, or the PENDING_PAYMENT→PAID flip, that produced the notification.

**Now:** it returns `Optional` and never throws. An unresolvable address is a **suppressed channel**,
exactly like an opt-out preference: the email leg is skipped with a warning *before* any delivery row
is written, the in-app leg still lands, and a notification left with no deliverable channel finalizes
`DISPATCHED` — the shape the opt-out path already produced.

Knock-on, removed: `FulfillmentService` guarded its REVIEW_REQUESTED notification on
`customer.email != null && !isBlank()` **because** `notify()` used to throw and would have rolled back
the whole deliver transaction. That guard is gone; such a customer now gets the in-app feed row like
everyone else.

**Proof:** `NotificationAdversarialIT.blankCustomerEmail_suppressesTheEmailLegAndCommits` (replaces
`blankCustomerEmail_throwsAtProduce`, which asserted the defect) and `nullCustomerEmail_isNotStorable`
— `customer.email` is `NOT NULL` (V2), so blank is the worst a row can carry; the service treats null
and blank identically anyway, and that test records *why* the null case cannot be exercised here
rather than leaving a silent gap.

### D4 · SMTP timeouts, and ticks that no longer queue behind one row

**Was:** `mail.properties` set no timeouts — Jakarta Mail's default is "wait forever" — while
`dispatchOneEmail` held `SELECT … FOR UPDATE` on the delivery row *and* a pooled DB connection for
the entire SMTP round-trip. A hung peer pinned both indefinitely; a handful exhausts the pool. The
two compounded.

**Now:**
- `mail.smtp.connectiontimeout=5000`, `mail.smtp.timeout=10000`, `mail.smtp.writetimeout=10000` — a
  hung peer fails fast into the existing RETRIED/FAILED accounting.
- The per-delivery claim is `FOR UPDATE **SKIP LOCKED**`: a second tick that picked the same id now
  returns empty and moves on to the next delivery instead of blocking for the length of the winner's
  send. The exclusion (never double-send) is unchanged — it was always the row lock.

**Explicitly not done:** moving `Transport.send()` outside the transaction. That needs a claimed
state (`PENDING → SENDING`) — a migration against the `status` CHECK plus a lease/reaper for rows
stranded mid-send by a crash — i.e. a new failure mode to design, not a mechanical change. With the
lock hold now bounded at ~25 s worst case and losers no longer serializing, the remaining risk is
small; the item stays open in the plan rather than being quietly dropped.

**Proof:** `SmtpTimeoutConfigTest` (the timeouts reach a `Session` built the way the factory builds
one, and each is a positive millisecond value) and
`NotificationAdversarialIT.concurrentTicks_sendExactlyOnce`, which now also asserts the second tick
has **finished** while the winner is still inside `send()`.

### D5 · A concurrent duplicate placement replays instead of 500ing

**Was:** the idempotency short-circuit is a read, and reads do not serialize. Two genuinely
concurrent submits with the same `Idempotency-Key` both saw "absent"; the loser met the
`(org_id, idempotency_key)` UNIQUE on insert as an uncaught `DataAccessException` → **500**, the one
outcome the header exists to prevent. Sequential retries were always fine.

**Now:** build + insert run behind a **savepoint**. A unique violation *on that constraint only*
rolls back to it — which is what makes recovery possible at all, since a constraint violation
poisons the whole Postgres transaction and the outer transaction may not even be ours (the portal
checkout owns it) — and the winner's order is re-read and returned as a replay. One caller gets 201,
the other 200. The rollback also un-claims the order number, so a race leaves no gap in the sequence.

**Proof:** `PlacementIdempotencyRaceIT` — the race is **forced deterministically** (a proxied
repository holds both callers at a latch *after* their idempotency read), not hoped for. Asserts both
resolve to one order, exactly one order row exists, and the loser reserved no stock. The run log
confirms the recovery path really fired.

### D6 · A reissue that settles the last invoice closes the order

**Was:** `reissue` re-runs `issueForFulfillment`, which re-allocates prepayment and can flip the
replacement invoice PAID — but FULFILLED → CLOSED only ever ran on a delivery event. An order
corrected after its final shipment could sit FULFILLED with nothing left to deliver.

**Now:** the two roll-ups live in `OrderRollUp`, shared: `afterDelivery` (unchanged behaviour, now
one call site in `FulfillmentService`) and `closeIfFullyPaid`, which the reissue path runs in the
same transaction after issuance.

**Proof:** `InvoiceVoidReissueIT.reissue_thatFullyPaysTheReplacement_closesTheOrder` (delivered
unpaid → FULFILLED; payment arrives late; reissue auto-allocates → PAID → CLOSED) and
`reissue_thatLeavesTheReplacementUnpaid_doesNotCloseTheOrder`.

---

## M3 — Polish & hygiene

### D8 · One CORS allowlist

`CorsFilter` re-parsed `CORS_ALLOWED_ORIGINS` with `Set.of(env.split(","))` and **no trim**, so a
configured `"a, b"` stored `" b"` and never matched — while the portal's CSRF `Origin` check, fed by
`AppConfig.corsAllowedOrigins`, matched it fine. Two parsers over one env var is two answers to one
question. The filter now takes the set from `AppConfig`; the parsing rule is one pure, tested
function.

**Proof:** `CorsOriginAllowlistTest` — `"a, b"` matches both, a foreign origin still gets no headers,
blank falls back to the dev defaults.

### D9 · Login hardening

Three separate weaknesses:

1. **Timing oracle.** An unknown email returned before any hashing while a known one paid for bcrypt
   — measurable from outside, on a public endpoint. `PasswordHasher.verifyDummy` now burns one
   bcrypt compare against a fixed internal hash on the no-such-user branch.
2. **No per-account throttle.** Per-IP fixed windows never see a distributed attack on one account,
   or credential-stuffing spread across many. `LoginThrottle` adds a Redis per-account counter:
   10 consecutive failures ⇒ a 15-minute lockout, cleared by a successful login, answered with a
   **429** (`TooManyAttemptsException`). It is keyed on the **presented address, not a user id** —
   otherwise the lockout becomes the enumeration oracle the timing fix just closed. The trade-off
   (someone can lock a known address out for 15 minutes) is why the window is minutes and the
   threshold is well above human mistyping.
3. **Strandable rate-limit keys.** `RateLimitFilter` did `INCR` then `EXPIRE` only when the counter
   came back 1: a crash between them leaves a TTL-less key that blocks that IP **for good**. It now
   writes the TTL first with `SET key 0 NX EX 60`, then `INCR` — `NX` means a live window is never
   reset.

**Proof:** `LoginThrottleIT` — lockout across ten different source IPs (and the *right* password
refused once locked), a success wiping the budget, an unknown address throttling identically to a
known one, and every failure key carrying a TTL.

### D9a · The lockout 429 now says how long — `Retry-After`

The 429 above carried no `Retry-After`, so a client could say "too many attempts" and nothing more.
Fifteen minutes and fifteen seconds render identically, and every caller has to invent a guess.

`TooManyAttemptsException` now carries the number and `AuthServlet` writes the header. **The value
is the failure counter's live TTL, not the flat `LOCKOUT_SECONDS`** — the TTL is written on the
*first* failure of a streak (that is what makes the key un-strandable, item 3 above), so an account
that reaches ten failures slowly is already partway through its window at the moment it locks;
quoting the full fifteen minutes there tells someone to wait longer than they actually must. It
falls back to the full window when the key has no TTL or expired between the `isLocked` check and
the read — over-stating a window that is already open is a harmless race, and the only outcome that
cannot advise hammering — and `getRetryAfterSeconds()` floors at 1, because `Retry-After: 0` invites
a hot loop.

**The envelope does not move.** `AuthServlet`'s three `catch (AppException)` arms now go through one
`writeAppError`, which sets the header and then writes the same `ApiError.of(status, message)` body
as before. A status that carries extra protocol information adds a header; it does not grow a field.

**Proof:** `LoginThrottleIT.lockoutCarriesRetryAfterSeconds` (positive, and inside the window rather
than an invented constant) and `retryAfterFallsBackWithoutACounter`.

**Not pinned, deliberately:** the servlet's header write itself. `AuthServlet.init()` pulls its
collaborators from a live `AppConfig`, whose only constructor boots Postgres + Flyway + Redis, so
there is no cheap way to drive the servlet in a test — and the mapping under test would be a
three-line `instanceof` in one shared writer. The wire contract is covered from the client side in
`frontst` story 96, whose mock answers the real 429 + `Retry-After` shape.

### D10 · CLAUDE.md matches the shipped sales-order route

The doc said a bare `GET /api/orgs/{orgId}/sales-orders` returns **400**, "reserved for the future
unfiltered list slice". The worklist shipped: it returns a `PageResponse`. A second line cited that
false 400 as precedent for the credit-notes route, propagating the staleness. Both rewritten — the
credit-notes 400 now stands on its own reasoning (a credit note only means something against the
invoice it credits, so there is no cross-invoice worklist to serve). `OrderLookupHandlerAuthTest`
already asserts the bare GET is a 200 worklist, so code and doc cannot silently diverge again.

While in there, four other CLAUDE.md entries were brought in line with what this story changed: the
aggregate threshold rule (`refund_approval_threshold`), refresh-reuse family revocation
(`POST /api/auth/refresh`), the new login 429 and the enforced distinct-secret rule, the
suppressed-channel semantics for an unsendable customer address, and the concurrent-duplicate replay
on `POST /api/public/{orgSlug}/checkout`.

> ⚠️ **CLAUDE.md is gitignored in this repo** (`.gitignore:5`), so the D10 edit — and the five
> above — exist in the working tree but are **not** in this branch's commit. Whoever syncs that file
> across machines needs to carry them by hand; nothing else in this story is affected.

---

## Contract changes

| Surface | Before | After |
|---|---|---|
| `POST /refunds` (direct) | 403 when *this* amount > threshold | 403 when the payment's **live refund total** > threshold |
| `POST /credit-notes` | 403 when *this* note > threshold | 403 when the invoice's **cumulative credited total** > threshold |
| `POST /auth/refresh` (both planes) | reuse ⇒ 401, family survives | **proven** reuse ⇒ 401 **and the family is revoked**; a re-presentation within 10 s of the rotation ⇒ 401, nothing revoked |
| `POST /auth/login` | 400/401/403 | adds **429** after 10 failed attempts on one address, carrying **`Retry-After`** (seconds remaining) |
| Concurrent duplicate checkout | 500 | 200 replay of the winner's order |
| Reissue on a delivered order | order stays FULFILLED | order rolls up to CLOSED when the replacement is PAID |

Frontend note: `POST /api/auth/login` can now answer **429** with `Retry-After`. Built in `frontst`
story 96 (`96_fix/login-lockout-and-refresh-race`): the admin error normaliser had **no 429 branch
at all**, so the lockout was rendering "Something went wrong" — that branch, the copy behind it, and
the header lift all land there.

## Not in scope

- **D4 follow-up** (send outside the DB transaction) — see above; still open in the plan.
- **D11's breadth** (a repository-level IT layer for `domain`/`common`/`repository`) — the plan scopes
  it as trailing and incremental. The part D11 calls "the specific regressions that would ship
  silently today" is done: every defect above lands with its test.
- **A machine-readable D1 threshold body** — considered and **deliberately skipped**; the reasoning
  is worth keeping because it will come up again.

  The 403 D1 raises says *why* in its message string (running total, threshold), but the frontend
  renders no raw backend message by policy, so the UI cannot explain the escalation. The shared
  client already declares the fields for it —
  `{ kind: 'approval_required', requiredRole?, thresholdEgp?, requestedEgp? }` — reading
  `required_role` / `threshold_amount` / `requested_amount` off the body.

  **Finding, and it is worse than "the numbers are missing": that whole branch is unreachable
  today.** `ApiError` has no `error_code` field and no approval fields, and nothing in `api` or
  `service` ever writes `required_role`. The normaliser's guard is
  `status === 403 && (code === 'APPROVAL_REQUIRED' || env.required_role)`, so it never fires — every
  above-threshold 403 currently renders as `errors.forbidden` ("You don't have permission to do
  that."), which for an escalation is not merely vague but *wrong*: the manager does have
  permission, it just needs a second signature.

  **Why it was still skipped.** Adding the fields to `ApiError` is cheap and precedented (the
  `ofShortages` pattern; Jackson omits nulls, so no existing client sees a change). The cost is
  structural: this codebase has **no central error writer**. Each handler owns a private
  `writeError(resp, AppException)`, and the approval 403 can surface from five of them —
  `RefundHandler`, `CreditNoteHandler`, `SalesOrderHandler` (cancel), `FulfillmentHandler`
  (failed-fulfillment refund) and `PaymentTransactionHandler` (orphan refund) — because
  `RefundService.createDirectPendingInTx` is reached from four call paths and
  `OrderCancellationService` gates on its own aggregate. Five hand-written branches across the money
  routes, where missing one leaves the same 403 machine-readable on some and not others — a worse
  state than uniformly not, and a poor trade for display copy on a branch nobody is reading yet.

  **What to do instead, when it is picked up:** give `AppException` subclasses a way to contribute
  fields to `ApiError` **once**, at a single writer, rather than teaching five handlers about a
  sixth exception type. That is a small refactor with its own justification, and it makes this
  change three lines instead of fifteen. Until then the honest interim is the frontend's: the 403
  renders as `forbidden`, and story 96 leaves `approval_required` untouched rather than pretending.

## No migration

Nothing in this story changes the schema.
