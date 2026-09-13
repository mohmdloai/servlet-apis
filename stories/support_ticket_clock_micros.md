# Fix: a ticket's timestamps were stamped in nanoseconds and read back in microseconds

> CI on `master` went red at 2026-09-13T11:29Z, on the merge of #186 (support tickets), with
> `SupportTicketIT.theThread_walksTheMachine_andWritesAStatusRowPerTransition:253 stamped once ==>
> expected: <2026-09-13T11:24:31.524299170Z> but was: <2026-09-13T11:24:31.524299Z>`. Same instant,
> two precisions. Branch `188_fix/ticket-clock-micros`.

## What happened

Every support write takes one `now` and uses it for the message row, the status row and the
aggregate's stamps: the three verbs on `SupportTicketService` and the three on `SupportDeskService`
each opened with `OffsetDateTime.now(ZoneOffset.UTC)`. The view a write returns is built from
the **in-memory** aggregate, so the caller got that value verbatim; the next call loads the ticket
from Postgres, where `timestamptz` keeps **microseconds**. The JDK clock is nanoseconds on Linux and
microseconds on macOS — so on a developer's machine the two values are identical and on CI they
differ in the last three digits.

The test is right and the assertion is the real one: the desk's first reply stamps
`first_response_at`, a later reply must not re-stamp it. It compared the value the first reply
returned (ns) with the value the second reply read back (µs) and failed on the sub-microsecond tail
alone. The same drift rode on every other stamp of the write, unasserted and this time on the wire:
`status_since`, `last_activity_at`, `resolved_at`, `closed_at` and a message's `created_at`. A reply
to `/api/orgs/{orgId}/support-tickets/{id}/messages` answered with `last_activity_at` in nanoseconds
and the following `GET` reported the same instant three digits shorter.

## Fix

One clock for the ticket paths, truncated to the precision the database keeps:

```java
public static OffsetDateTime ticketClock() {
  return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
}
```

All six `now` sites (three merchant-plane, three desk-plane) take it. This is the same remedy
`PaymentTransactionService.claimClock()` already carries for the claim holds, and for the same
reason — a value returned to the caller must carry the precision it will read back with.

The IT gains a guard that does not depend on the clock of the machine running it:
`firstResponse.getNano() % 1000 == 0`. Without the fix that fails on CI's nanosecond clock; with it
it holds everywhere, so the invariant is stated rather than stumbled over.

No migration, no wire-shape change: the JSON is ISO-8601 either way, three fewer digits on it.

## Tests

- `mvn -o verify -pl api -Dit.test=SupportTicketIT`: 8/8 green, spotless clean.
- `mvn -o test -pl domain,service`: 296 green in `service`, the ticket machine's fixed-clock unit
  tests untouched (they pass their own `now`, so they never saw this).

Checked for siblings of the class: the other three `assertEquals(x.toInstant(), y.toInstant())` in
the test tree compare against fixed literals or a `withNano(0)` value, so none of them can drift.
