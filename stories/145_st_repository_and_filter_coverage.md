# Story 145 — the three coverage gaps that paired with no defect (D11)

**Type:** test · **Branch:** `145_test/repository-and-filter-coverage` (stacked on `144_fix/…`) ·
**Plan:** `docs/defect-remediation-plan.md` §D11

D11 listed six things worth testing. Story 141 wrote four of them alongside the fixes they belonged
to. The remaining three paired with no defect, so they had nowhere to land — which is exactly how
coverage debt survives a remediation pass. This is those three.

Nothing here changes behaviour. The only production edits are a package-private test constructor and
a `pom.xml` test dependency.

---

## G1 — The repository module gets its first tests

`repository` had **zero** tests of its own. Its guarantees were verified transitively, through `api`
ITs that assert a business outcome several layers up.

That is real coverage and it is not nothing — but it is indirect in a way that matters: an IT proves
*"the order did not oversell"*, not *"the CAS rejected a stale write"*. A guarantee that starts
holding for a different reason, or stops holding in a case no business flow happens to reach, reads
green either way.

`RepositoryGuaranteesIT` pins the three the plan names, at the layer that implements them:

- **The stock version-CAS.** Two callers read version 0; the second is refused — and, the part worth
  asserting, **changes nothing**: not a partial write, not a second decrement. Plus: the `org_id` is
  part of the CAS predicate, not a filter applied after it, so another tenant cannot move your stock
  even holding a correct version.
- **The idempotent restock.** `ON CONFLICT DO NOTHING` returns the row on first use and **empty** on
  every replay — the caller reads "empty" as "someone already did this, do not move stock again", so
  the emptiness *is* the contract. The key is claimed per org, so two tenants may legitimately use
  the same string.
- **The `PUBLISHED` + `org_id` predicates.** A draft, an archived row, and another tenant's published
  row are all unreachable through the published read; the admin read still sees its own drafts.
  Both predicates live in one `WHERE`, so this pins that neither can be dropped alone.

Deliberately thin — this is not a second home for business rules.

It needed `junit-jupiter` added to `repository/pom.xml`. The module had no tests, so the JUnit 5 API
had never been on its test classpath at all; that is its own small statement about the gap.

## G2 — The filters are tested directly

`JwtAuthFilter` and `CustomerAuthFilter` decide who reaches the two planes, and their branches were
only ever exercised end-to-end. Same problem as above, sharper: a filter test that asserts "401"
through six layers cannot tell you *which* check produced it, so a rejection that starts happening
for a different reason still passes.

- **`CustomerAuthFilterTest`** (13) — one refusal per cause: the missing CSRF header (400 *before*
  any token is read), a mutation from a foreign origin (403), the refresh/logout bypass that skips
  the access token but **not** CSRF, a staff token that cannot verify on this key, a correctly-signed
  token with the wrong audience, both revocation checks, and the happy path publishing the
  `CustomerPrincipal` that scopes every portal read. Plus: a refused request leaves no principal
  behind.
- **`JwtAuthFilterAudienceTest`** (6) — the staff-plane mirror. The `aud=customer` rejection is
  asserted with a token signed by the **staff** key on purpose, so the audience check is proven on
  its own rather than letting the key difference do the work. The missing-`aud` grace window is
  pinned too, so closing it stays a decision someone makes.
- **`PortalCsrfTest`** (8) — the rules as pure decisions, including the two that look like holes and
  are not: `Origin` beats `Referer` (a foreign origin cannot be laundered by an allowlisted
  referer), and neither-header-present is allowed because that is a non-browser client, where the
  required custom header and `SameSite=Strict` already carry the guard.

`CustomerAuthFilter` gained a package-private test constructor, following `CorsFilter` and
`JwtAuthFilter` — `init()` reads a live `AppConfig`, which boots Postgres and Redis.

## G3 — The notification state machine, as a unit test

`NotificationDeliveryStateMachineTest` (11) covers the retry/attempt ladder, the claim and settle
transitions, and story 144's reaper — with no Docker. The transaction boundary is mocked by running
the callable inline, which is what a committed transaction looks like from that code's point of
view.

The argument for doing it this way: which outcome a given *(attempts, budget, provider result)*
triple produces is **arithmetic**. The ITs should prove the SQL; arithmetic should not need a
database, and it especially should not need one to test the boundary cases — the attempt that
*reaches* the budget, the row already past it, the undeclared `RuntimeException` that must count
exactly like a declared one, the 900-character provider message truncated before it meets a
500-character column.

## What is still open

`domain` and `common` breadth (beyond `TextTest`), and service-level unit tests for the money and
catalog services. Still scoped as trailing, and the debt was always breadth rather than rot.

## Proof

- `RepositoryGuaranteesIT` 10/10 · `CustomerAuthFilterTest` 13/13 · `JwtAuthFilterAudienceTest` 6/6
  · `PortalCsrfTest` 8/8 · `NotificationDeliveryStateMachineTest` 11/11
- `common`/`domain`/`repository`/`service` unit suites and the full `api` battery green.

## No migration
