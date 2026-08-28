# Payment claims — the hold's clock at Postgres precision (CI hotfix)

> Branch `170_fix/payment-claim-hold-micros` off `master` (after #169). PR #170 expected. No migration.

**Symptom.** `PaymentClaimNotFoundIT.notFound_marksTheClaim_reArmsTheHoldSixHours_andTellsTheShopper`
failed on CI (Linux) and on the master deploy while passing locally (macOS):
`expected: <…24.815680Z> but was: <…24.815680348Z>`.

**Cause.** The claim paths (`claim`, `verifyClaim`, `markClaimNotFound`) take
`OffsetDateTime.now(ZoneOffset.UTC)` and hand `now + 48h` / `now + 6h` both to the row and to the
caller. Postgres keeps `timestamptz` at microseconds; the JDK clock is nanoseconds on Linux
(microseconds on macOS), so the value returned in the response carried 348 ns the row never had.

**Fix.** One clock for the claim paths — `claimClock()` = `now(UTC).truncatedTo(MICROS)` — so
`held_until` in the not-found response (and the hold written on claim / verify) equals what any later
read returns. The assertion compares both sides at microsecond precision as well.

**Tests.** `PaymentClaimNotFoundIT`, `PaymentClaimVerifyIT`, `ShopperPaymentClaimIT` green locally;
CI is the real check (Linux clock).
