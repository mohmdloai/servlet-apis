# Fix: `VapidSignerTest` verified a fixed-date token against the wall clock

> CI on `master` went red at 2026-09-06T00:14Z, on the merge of #181 (inventory filters), with
> `VapidSignerTest.theHeaderParsesAsVapidTAndK_andTheTokenVerifiesWithThePublicKey » ExpiredJwt
> JWT expired 8090919 milliseconds ago at 2026-09-05T22:00:00.000Z`. Nothing in #181 touches Web
> Push; the test was a time bomb from `web_push.md` (#177). Branch `182_fix/vapid-test-clock`,
> test-only, no production change.

## What happened

The test signs a VAPID token with a fixed vector `now = 2026-09-05T10:00:00Z` and the signer's
12 h `TOKEN_TTL`, so its `exp` is `2026-09-05T22:00:00Z`. It then verifies the token with
`Jwts.parser().verifyWith(pub).build()`, whose clock is the JVM's. The assertion held for the 12
hours after the vector's date and failed on the first CI run after that — a failure that depends on
the wall clock, not on the code.

## Fix

Give the parser the same fixed clock the token was signed with:
`Jwts.parser().verifyWith(pub).clock(() -> Date.from(now)).build()`. The test is about the header's
shape (`vapid t=…, k=…`), the audience (the origin), the subject and the lifetime being exactly
`TOKEN_TTL` and within RFC 8292's 24 h — none of which is a wall-clock question. The lifetime
assertion (`exp − now == TOKEN_TTL`) still runs on the vector's `now`.

No other test parses a signed JWT (`parseSignedClaims` occurs in this one test file); the other
fixed dates in the tree (`PlatformAuditServiceIT`, `PlatformOverviewServiceTest`) are data, never
compared against the clock.

## Tests

`mvn -o test -pl common`: 75/75 green, regardless of the date the run happens on.
