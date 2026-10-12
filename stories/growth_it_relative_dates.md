# Fix: `PlatformGrowthIT` stamped fixed July dates into a 90-day window

> CI went red on 2026-10-12, on #202 (storefront revalidate hook) and its merge to `master`, with
> `PlatformGrowthIT.pathFilterSeparatesTheSeries:241 expected: <1> but was: <0>`. Nothing in #202
> touches the growth series; the test was a time bomb from slice 8 (`35c4738`). Branch
> `203_fix/growth-it-relative-dates`, test-only, no production change.

## What happened

The test stamps `FIRST_ORDER` at `2026-07-08` and `2026-07-09` (12:00 UTC), then reads
`?window=90d`. The window is `now − 90 d`, so the assertion held until 2026-10-06 and then failed:
on 2026-10-12 both stamps were 95–96 days old and the series came back empty.

## Fix

Stamp the Wednesday and Thursday of the ISO week three weeks back:

```
monday = (now − 21 d).with(MONDAY) @ 12:00 UTC
stamp(selfServe,   monday + 2 d)
stamp(provisioned, monday + 3 d)   -- same UTC week, different day
```

Both are always ≤ 24 days old (inside 90 d), never in the future, and share one Postgres
`date_trunc('week')` bucket — what the test is about (`all` = per-bucket sum of the paths).

The other fixed dates in this file (`bucketBoundariesAreUtc`, the week-gap test) read
`window=all`, so they never age out.

## Tests

`mvn -pl api verify -Dit.test=PlatformGrowthIT`: 17/17 green; `spotless:check` clean.
