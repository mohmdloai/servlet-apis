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
| `POST /auth/refresh` (both planes) | reuse ⇒ 401, family survives | reuse ⇒ 401 **and the family is revoked** |
| `POST /auth/login` | 400/401/403 | adds **429** after 10 failed attempts on one address |
| Concurrent duplicate checkout | 500 | 200 replay of the winner's order |
| Reissue on a delivered order | order stays FULFILLED | order rolls up to CLOSED when the replacement is PAID |

Frontend note: `POST /api/auth/login` can now answer **429**. Nothing breaks if it is treated as a
generic error — the body carries the message — but a dedicated "too many attempts, try again in a few
minutes" state is the right rendering.

## Not in scope

- **D4 follow-up** (send outside the DB transaction) — see above; still open in the plan.
- **D11's breadth** (a repository-level IT layer for `domain`/`common`/`repository`) — the plan scopes
  it as trailing and incremental. The part D11 calls "the specific regressions that would ship
  silently today" is done: every defect above lands with its test.

## No migration

Nothing in this story changes the schema.
