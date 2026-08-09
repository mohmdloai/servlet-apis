# Story 144 — the SMTP send leaves the transaction (D4's follow-up)

**Type:** fix · **Branch:** `144_fix/smtp-send-outside-transaction` (stacked on `143_fix/…`) ·
**Plan:** `docs/defect-remediation-plan.md` §D4 · **Migration:** V78

Story 141 closed D4's must-do half — SMTP timeouts, and `FOR UPDATE SKIP LOCKED` so a second
sweeper tick stops queueing behind the winner. It left the harder half open, with its reasoning
written down: the send still ran *inside* the per-delivery transaction, and moving it out needs a
claimed state plus a lease and a reaper — a new failure mode to design rather than a mechanical
change. This is that design.

---

## The problem the timeouts only bounded

`dispatchOneEmail` was one transaction wrapping read → send → write. The provider call therefore ran
holding the delivery row's `FOR UPDATE` lock **and** a pooled DB connection for the entire
round-trip. The timeouts cap that at ~25 s (5 s connect + 10 s read + 10 s write) instead of
forever, which is the difference between an outage and a slowdown — but a handful of slow peers
still pins a real slice of the pool, and it is the reason running a **second delivery node** was
never a safe thing to do: both nodes would spend their ticks holding rows rather than draining them.

The blocker was that `PENDING → SENT/FAILED` has no state meaning *"a worker has this and is talking
to the provider right now"*. Without one, the only thing that says "mine" is the row lock, and a
lock only exists inside a transaction. So the send had to be in there with it.

## The claimed state

**V78** adds `SENDING` to the `notification_delivery` status CHECK and a nullable `claimed_at`,
plus a partial index on `(claimed_at) WHERE status = 'SENDING'` for the reaper. Dispatch is now
three steps:

1. **Claim** — short transaction. `PENDING → SENDING` under the same `FOR UPDATE SKIP LOCKED`,
   stamping `claimed_at`, and reading the frozen content. Commits immediately.
2. **Send** — **no transaction, no connection, no row lock.** The slow part runs on its own.
3. **Settle** — short transaction. `SENT`, or back to `PENDING` for retry, or terminal `FAILED`.

The claim is what makes the split possible: once it commits, it is the row's **state** rather than a
held lock that keeps other workers off it. Everything that used to be guaranteed by holding the lock
across the send is now guaranteed by a state nobody else will claim.

`markDeliveryRetry` had to start writing the status explicitly. It previously left the row PENDING
by *not writing* — which was correct when PENDING was where the row already was, and silently wrong
once it arrives SENDING.

## The failure mode it introduces, and the lease

A worker that dies between claiming and settling leaves the row `SENDING`. The drain only looks for
`PENDING`, so without a second mechanism that is a **permanent silent loss** — strictly worse than
the lock-holding it replaced. That is precisely why story 141 declined to do this half in passing.

`reapStrandedEmail(leaseSeconds, batchLimit)` returns claims older than the lease to the queue, and
the sweeper tick **reaps before it drains**, so a recovered row can go out on the same tick.

Three decisions:

- **120-second lease** (`NotificationDeliverySweeperJob.EMAIL_CLAIM_LEASE_SECONDS`), roughly 5× the
  ~25 s the SMTP timeouts bound a send at. It must exceed the worst-case send or a live send is
  reaped underneath itself and the message goes twice — being late costs a delayed retry, being
  early costs a duplicate email, so the margin is deliberately lopsided.
- **A stranded claim spends an attempt.** Otherwise a message that reliably kills the process is
  reclaimed forever, which is the one failure the retry budget exists to prevent. Exhausting the
  budget while stranded is terminal `FAILED`, same as exhausting it while failing.
- **Every path out of SENDING increments exactly once.** The claim deliberately does *not* touch
  `attempts` — settle counts one, the reaper counts one for a claim that never settled.

## The delivery guarantee did not change

Still **at-least-once**. A crash after the provider accepted the message but before the settle
commits leaves the row SENDING; the reaper returns it and it sends again. The old code had the
identical window — it could crash between `send()` and its transaction commit and re-send on the
next tick. The window moved; it did not open. Exactly-once would need provider-side idempotency this
codebase does not have and SMTP does not offer.

## Proof

- `NotificationAdversarialIT.sendRunsOutsideTheTransaction_theRowIsClaimedButUnlocked` — the direct
  assertion. While the provider call is held open, the row reads `SENDING` from an independent
  connection **and** `SELECT … FOR UPDATE NOWAIT` succeeds against it. `NOWAIT` throws if anyone
  holds the row, so it passing is proof the transaction committed and released before the send
  began. This is the test that would have been impossible to write before the change.
- `aStrandedClaimIsReturnedToTheQueue` — a claim older than the lease rejoins the queue, spends an
  attempt, and really sends; a claim *inside* its lease is left alone (an in-flight send is not a
  stranded one).
- `aStrandedClaimOutOfAttemptsFailsTerminally` — stranding is bounded by the same retry budget.
- `concurrentTicks_sendExactlyOnce` **passes unchanged**, which is the interesting part: what turns
  the loser away moved from `SKIP LOCKED` to the `SENDING` state, and the observable guarantee —
  exactly one provider hit, one attempt — is identical. Its comment was updated to describe the
  mechanism that now does the work.
- Full battery: **1012 api ITs** green.

## What this unblocks

A second delivery node. Two workers can now drain concurrently without either holding rows across
its provider calls — the claim excludes them, and anything a crashed node abandons is recovered by
the lease instead of waiting for that node to come back.

## Migration

**V78** — `SENDING` on the status CHECK, `claimed_at`, and the partial reaper index. Additive: no
existing row is mid-send at migration time, so every one gets `claimed_at = NULL`, which means "not
currently claimed" rather than an unknown.
