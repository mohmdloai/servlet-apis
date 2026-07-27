# Cross-org search — find the thing an operator was told about

> Slice 3 of the platform-console epic. Branch `130_feat/platform-search`, cut from **`master`**
> (highest PR **is** #129 — re-verified 2026-07-27, no dependabot PR has landed above it since; check
> again at branch time). Frontend pair: `frontst` branch `75_feat/platform-search` (story 77) —
> **land this backend first**; the palette is a read of it.
> **Migration: V75, and it is not optional** (see §Indexes). Slices 1 and 2 established the pattern;
> this one repeats their central lesson in a new place, and then finds a second one underneath it.

---

## The reality this corrects

A customer emails support quoting an order number. A merchant asks about a bank transfer reference.
Today the operator's only move is to know which tenant it belongs to *first*, because every read in
this system is org-scoped. The org list searches nothing — it filters by status. That is the gap: the
console can answer "how is tenant X doing" but not "who does this number belong to."

### The proposal for this slice was wrong on both of its premises

It said: *"Type an order number, provider_ref, or an email → jump to the entity, across orgs. Your
uniques make every one of these lookups an index hit."* Neither half holds, and both change the
design.

**1. Most of these are not unique across orgs, so "jump to the entity" is not available.**

| identifier | constraint | globally unique? |
|---|---|---|
| `sales_order.order_number` | `UNIQUE (org_id, order_number)` (V17) | **no** |
| `sales_invoice.invoice_number` | `UNIQUE (org_id, invoice_number)` (V21) | **no** |
| `credit_note.credit_note_number` | `UNIQUE (org_id, credit_note_number)` (V25) | **no** |
| `customer.email` | `UNIQUE (org_id, email)` (V15, which *dropped* V2's global unique) | **no** |
| `payment_transaction.provider_ref` | `UNIQUE (provider, provider_ref)` (V22) | yes |
| `app_user.email` | `UNIQUE (email)` (V11) | yes |
| `org.slug` | `UNIQUE` (V15) | yes |

Order numbers are allocated per-org, so `SO-2026-00042` can exist in every tenant at once — and
will, because the counters start at the same place. A customer email is one row *per org*: the same
person shopping at three merchants is three customers. So for four of the seven, the honest answer
is **a list**, and a UI that jumped to the first match would silently hide the rest. That is the
console-that-lies failure this epic keeps refusing.

**Consequence: the response is always a grouped result set, never a redirect.** A single match is
just a result set of one — the *client* may choose to navigate straight through, but the server
never pretends a lookup was unambiguous when it was not.

**2. None of the identifier lookups is an index hit. Every one is a sequential scan.**

Every unique above puts a **scoping** column first — `org_id`, or `provider`. A cross-org
`WHERE order_number = ?` matches none of them on its leading column. Verified: no index anywhere in
the schema leads with `order_number`, `provider_ref`, `invoice_number` or `credit_note_number`.

This is **V73's lesson one slice later**, and it is not a coincidence: every index in this schema
was built for tenant-scoped reads, because tenancy is compiler-enforced and no cross-org read
existed until this epic. Expect to keep meeting it.

**Measured, not predicted.** `EXPLAIN (ANALYZE, BUFFERS)` on `perfdb` (200 orgs · 200 000 customers
· 1 000 000 orders · 846 117 transactions), 2026-07-27, on the `count(*)` shape — see §"`total` is
what you actually pay for", below, for why the count and not the top-5 fetch is the honest unit:

| probe | plan today | time |
|---|---|---|
| `sales_order.order_number = ?` | Parallel Seq Scan, 1M rows | **2567 ms** |
| `payment_transaction.provider_ref = ?` | Parallel Seq Scan, 846k rows | **257 ms** |
| `customer.email = ?` | Parallel Seq Scan, 200k rows | **33 ms** |
| `app_user.email = ?` | Index Only Scan `app_user_email_unique` | 0.24 ms |
| `org.slug = ? OR fold_search(name) LIKE …` | Seq Scan, 200 rows | 0.91 ms |

Those are the **before** numbers; V75 has to publish the **after** ones beside them. They are
recorded here so nobody re-derives them, not as a substitute for re-running them on the branch.

**What genuinely is free**, and should be used rather than re-indexed:
- `app_user.email` — `UNIQUE (email)` leads with `email`. A real hit, measured above.
- `org.slug` — `UNIQUE`; and `org.name` is unindexed but `org` is 200 rows, so a scan there is
  honest and cheap (0.91 ms *including* a `fold_search` call per row). Do not index it reflexively;
  say so in the PR with the plan attached.

### The third premise that was wrong: "V62's trigram indexes make name search free"

`customer.name_search` and `product.name_search` are V62 generated columns with GIN trgm indexes
that are deliberately **not** org-scoped, so they are usable cross-org exactly as they stand. That
much is true. What does not follow is that a **2-character** minimum protects them.

pg_trgm extracts no trigram from a pattern shorter than three characters, so `LIKE '%ah%'` gives the
GIN index nothing to narrow on — and the planner still *chooses* the index, then rechecks the entire
table through it. Measured on `perfdb`'s 200 000 customers:

| query | plan | time |
|---|---|---|
| `name_search LIKE '%zq%'` (2 chars) | Bitmap Index Scan, **Rows Removed by Index Recheck: 200 000**, 5249 buffer reads | **1706 ms** |
| `name_search LIKE '%zqx%'` (3 chars, no match) | Bitmap Index Scan, 3 buffers | 0.045 ms |
| `name_search LIKE '%ahm%'` (3 chars, 20 026 matches) | Bitmap Heap Scan, 4532 buffers | 10.6 ms |

**A 1.7-second query per keystroke is exactly the denial-of-service the minimum-length rule exists
to prevent, and a minimum of 2 does not prevent it.** This is the finding of the slice. It also
decides the next section.

### Decision: the fuzzy customer-name probe is out of v1

Four independent reasons, and the first alone would be enough:

1. **It is the only probe the length rule cannot make safe** without a special case (≥ 3 rather than
   ≥ 2), and a per-probe minimum is a rule that has to be explained on every screen that fires it.
2. **Its groups are unbounded and unactionable.** `ahm` matches 20 026 customers across 200 tenants.
   "Showing 5 of 20 026" is a true sentence that helps nobody.
3. **There is nowhere to send the other 20 021.** No cross-org customer surface exists, and the
   admin frontend has **no customer detail page at all** (verified: zero `/customers` routes in
   `frontst/apps/admin`). The truncation notice would have no destination, and the five results it
   *did* show would not be openable either.
4. **The support scenario supplies an email, not a name.** "A customer emailed us" is the whole
   premise of this slice; the email probe is exact, bounded and already cheap.

**So `customer` stays a type, matched on `email` (exact) only.** The fuzzy-name need is served where
it is bounded and useful — `org.name`, over 200 rows.

What would bring it back: a cross-org customer surface to link to, plus a ≥ 3 minimum on that probe
alone, plus a per-tenant narrowing (`?org_id=`) so the result set is a work list rather than a
census. State that in the handler Javadoc beside the other exclusions so the next person extends
deliberately rather than rediscovering the 1706 ms.

Note the consequence for the epic's ledger: **this slice consumes none of V62's trigram indexes.**
They remain right, and remain org-plane machinery.

## The change

`GET /api/admin/search?q=` — `requirePlatformRead` (ADMIN and SUPPORT, byte-identical output), on a
new `SearchAdminHandler` registered in `AdminServlet.init()`'s handler map beside `overview` /
`queues` / `orgs` / `users` / `audit` (a sixth `Map.of` pair; the 10-pair overload still fits).
`GET` only → 405; **404 on any subpath** — that is `OverviewAdminHandler`'s shape, not
`QueuesAdminHandler`'s. Mirror Overview here and say why in the Javadoc: `queues` 400s an unknown
segment because `{kind}` is an enum value that happens to sit in the path, whereas `/search` takes
no path segment at all, so anything after it is an unknown resource.

### Response shape

Grouped by type, each group capped, no pagination:

```
{ "query": "...", "groups": [ { "type": "sales_order", "total": 3, "results": [ … ] }, … ] }
```

- **No pagination, cap 5 per type.** This is a jump-to affordance, not a worklist. If a query
  matches more than five of anything, the right answer is the queue or the org list — say so with
  `total` so the client can render "5 of 17" honestly rather than truncating in silence. `total` is
  the true count, not `results.length`.
- **Every result carries `org: {id, name, slug, status}`** using slice 1.5's `OrgStatus`, except the
  `org` and `app_user` types which have no owning tenant. Same rule as the queues: the server names
  the state, no client re-derives it.
- **Results carry stable identity, not URLs.** `{type, id, label, sublabel}` plus `org`. Route
  construction is the frontend's job — story 75 already established `queueRecordPath(tab)` as the
  place that lives, and a server emitting `/admin/...` paths couples the API to a route table it
  cannot see. (Story 77 has since found that one of the five types has no route at all. That is the
  frontend's finding to act on and does not change this contract.)
- **Empty groups are omitted, never sent as `total: 0`.** Same reflex as `degraded[]`: a client
  branches on presence. A group present with zero results is a shape the UI would have to render
  around for no information.

### `total` is what you actually pay for

The cap is 5 and `total` is the true count, so **`LIMIT` buys nothing**: an exact count cannot
early-exit. Every probe runs its predicate over the whole matching set once, and the five rows are
almost free on top. This is why the measurement table above counts rather than fetches, and it is
the number every `EXPLAIN` in the DoD has to be taken on. Measuring `… LIMIT 5` instead would show a
`sales_order` probe finishing in a millisecond on a lucky early match and hide the 2567 ms.

If a probe's count ever turns out to be the thing that cannot be made fast, the honest retreat is to
drop `total` for *that type* and say "5+" — not to keep the field and fill it with `results.length`,
which is the truncating filter defect wearing a number.

### Types in v1, and what is deliberately out

**In:** `org` (slug exact, name fuzzy over 200 rows) · `app_user` (email exact) · `customer` (email
exact) · `sales_order` (order_number exact) · `payment_transaction` (provider_ref exact).

**Out, with reasons rather than silence:**
- **Customer names** — §"Decision", above. The one exclusion that is a measurement, not a judgement.
- **`sales_invoice` / `credit_note` numbers** — genuinely quoted by customers, but each costs
  another cross-org index and both are reachable in two clicks from the order that owns them; add
  them when an operator asks, not speculatively.
- **Products and listings** — merchant catalog, not operator triage; the merchant searches their own
  catalog on the org plane.

State all three exclusions in the handler Javadoc.

### Classify the query before probing

Do **not** run every probe on every keystroke. The query's shape says what it can possibly match:

| query looks like | probe |
|---|---|
| contains `@` | `app_user.email`, `customer.email` (exact, normalized) |
| starts with `SO-` (case-insensitive, after digit folding) | `sales_order.order_number` (exact) |
| anything else | `org.slug` (exact) / `org.name` (folded fuzzy), `payment_transaction.provider_ref` (exact) |

`provider_ref` is free-form — a bank reference can look like anything — so it stays in the
catch-all bucket as an **equality** probe, which is cheap once indexed.

**Classify on the `SO-` prefix, not on a format regex.** The allocator mints `String.format("SO-%d-%05d", …)`
→ `SO-2026-00042` (`SalesOrderService`), but that is not the only real shape: `perfdb`'s seeded
corpus is `SO-000001`, six digits and no year, and CLAUDE.md already records those two as a disjoint
namespace. A strict `^SO-\d{4}-\d{5}$` would silently drop half the order numbers that exist. The
probe is an equality match either way, so a loose classifier costs one wasted index lookup and a
strict one costs a missing answer.

Put the classification in **one** documented place with the rules as data, not scattered `if`s —
the `PlatformQueuePredicates` precedent. A reviewer must be able to see which probes a query fires.

### Normalize the input with the same normalizers the write path used

Lower-casing is not enough, and this is an Arabic-first product. Every column being probed was
written through `common/.../text/Text`, so the query has to go through the same function or the
equality will miss on input a real operator will really paste:

| probe | normalizer | why |
|---|---|---|
| `app_user.email`, `customer.email` | `Text.normalizeEmail` | what `AccountService`/`SalesOrderService`/`MemberService` wrote; V62 backfilled the rest to `lower(btrim(normalize(…, NFC)))` |
| `payment_transaction.provider_ref` | `Text.normalizeNumeric` | what `PaymentTransactionService` writes (two call sites) and what V62 backfilled: **Arabic-Indic and Persian digits fold to ASCII**. A reference pasted out of a bank SMS in an Arabic locale misses without this |
| `sales_order.order_number` | `Text.normalizeNumeric`, then upper-case | same digit fold; upper-case because the number is always minted upper and a customer quoting it in an email often is not. This deliberately diverges from the org-plane `?order_number=` lookup, which is case-sensitive — note the divergence in the Javadoc rather than leaving it to be found |
| `org.name` | `fold_search` on **both sides** | the `ProductRepositoryImpl.searchCondition` precedent: `DSL.field("fold_search({0})", …)` against the folded column, so the stored key and the query fold are consistent by construction. `org` has no `name_search` column, so both sides are function calls — 0.91 ms over 200 rows, measured |

- **Minimum length 2** (after trim and normalization) → 400 naming the minimum. With the fuzzy
  customer probe cut, 2 is now genuinely sufficient: every remaining probe is an equality match or a
  200-row scan. Record *that* as the reason in the Javadoc, so nobody re-adds a trgm probe under a
  limit that no longer covers it.
- **Blank/missing `q`** → 400. Not an empty result set: the caller made a mistake.
- **Ordering:** exact identifier matches before fuzzy name matches, because an exact match is
  unambiguous and a name match is a guess. Within a group, newest first.

### Where the rows live — a third sibling

**Do not add search to `PlatformStatsRepository` (counts only) or `PlatformQueueRepository`
(queue rows).** Add `PlatformSearchRepository`, the third member of that family, under the same
three rules those two state: **platform-gated, read-only, and rows against a declared per-type field
whitelist**. Each result carries identity, one label, one sublabel and its tenant — never a customer
phone or address, never an order's lines, never a payment-proof key.

This is a **widening of default exposure, not of capability** — the same framing slice 2 used, and
it needs restating because search makes exposure *effortless* in a way a worklist does not.

**One disclosure worth deciding on purpose:** searching an email reveals which tenants that person
shops at. A platform ADMIN can already learn this by other means, so it is not new capability — but
it is newly one keystroke. It is in scope because a support operator handed an email genuinely needs
it, and the alternative (search per-tenant, 200 times) is worse. Pin it with a test so removing it
later is a visible decision, exactly as `to_address` was pinned in slice 2.

### Suspended tenants are searchable

Nothing filters on org status. A suspended merchant's orders are the ones most likely to generate
support contact, and the badge on the row tells the operator why nobody else is minding it. Same
rule, same reason as the queues. IT-pinned.

### Indexes

**Measure first, on `perfdb`, then write V75.** Same protocol as V73, which is the reason this
section can be short. The before-numbers are in §"Measured, not predicted"; the branch owes the
after-numbers on the same `count(*)` shape.

Candidates — confirm each with `EXPLAIN (ANALYZE, BUFFERS)` warm-twice before adding it, and **drop
any the planner declines** (V73 declined one that was correctly built):

- `sales_order (order_number)` — 2567 ms today. The one index this slice cannot ship without.
- `payment_transaction (provider_ref)` — 257 ms today.
- `customer (email)` — 33 ms today, currently reachable only via `(org_id, email)`. The smallest
  win of the three; if the planner or the write cost argues against it, skipping it is a defensible
  result — say so with the plan.

Do **not** add anything for `app_user.email`, `org.slug`, `org.name`, `customer.name_search` or
`product.name_search`. The first two are already served (measured); `org.name` is 200 rows; the last
two are not probed by this slice at all. Prove the first two with a plan rather than asserting it.

Record the before/after table **in V75's header comment**, not only in the PR body — V73 established
that as the better home and it is where the next person will look.

#### perfdb safety

`tools/seed/README.md` §"Running the full stack against perfdb" and §"Migration mismatches" are the
runbook. The rules that bite this slice:

- **`perfdb` and the dev `inventorydb` share one volume.** `docker compose down -v` destroys both,
  permanently. Never run it. Ask first.
- **Never point Flyway at `perfdb`.** Apply candidate indexes by hand in psql, keep the ones V75
  keeps, drop the ones it does not, and note the out-of-band DDL in the PR body.
- V75 is the "applied but missing" trap on branch switches. That is never a reason to reset — scratch
  clone, repair the history row, or temp-copy the V-file.
- The dev `inventorydb` is at **V72** (confirmed 2026-07-27) and will apply V73/V74/V75 together on
  next boot or codegen. Expected, not a fault.

## Tests

`PlatformSearchIT` (api module, TestContainers), **two orgs throughout**:

- `sameOrderNumberInTwoOrgs_returnsBoth` — **the story's whole point.** Seed `SO-…-00001` in both
  orgs; assert two results, each naming its own tenant. A single-org fixture cannot fail this way,
  which is why the pitch's "jump to the entity" survived until the schema was read.
- `sameCustomerEmailInTwoOrgs_returnsBoth` — the same shape one table over, and the deliberate
  cross-tenant disclosure, asserted on purpose.
- `providerRef_isGloballyUniqueAndReturnsOne` · `userEmail_returnsOne` · `orgSlug_returnsOne` — the
  three that genuinely are unambiguous, pinned so a future schema change that scopes them shows up.
- `suspendedOrgIsSearchable` — suspend one org mid-fixture; its order still resolves and its result
  reports `org.status = SUSPENDED`.
- `queryShapeSelectsProbes` — an `@` query returns no `sales_order` group; an `SO-` query returns no
  `payment_transaction` group. Assert on results, not on internals, so the test survives a refactor.
- `customerNameIsNotSearchable` — the **exclusion, pinned**. A customer named exactly like the query
  produces no `customer` group; only their email finds them. Without this test the probe grows back
  in six months and takes the 1706 ms with it.
- `arabicIndicDigitsInProviderRef_match` · `arabicIndicDigitsInOrderNumber_match` — the normalizer
  rows in the table above, as behaviour. Store an ASCII reference, query it with `٠١٢٣`.
- `lowercaseOrderNumber_matches` — the deliberate divergence from the org-plane lookup.
- `arabicOrgNameVariantsMatch` — `احمد` finds an org named `أحمد`, via the `fold_search`-on-both-
  sides rule. The one place fuzzy matching survives in this slice.
- `results_carryNoCustomerPii` — the whitelist pin, mirroring slice 2's. Fixture customer has a
  phone and address; neither appears anywhere in the JSON.
- `capIsFive_andTotalTellsTheTruth` — seed 17 matches; assert `results.length == 5` and
  `total == 17`. A cap that lies about what it hid is the same defect as a truncating filter.
- `emptyGroupsAreOmitted` — a query matching exactly one type returns exactly one group.
- `blankQuery_is400` · `singleCharQuery_is400NamingTheMinimum` · `post_is405` · `subpath_is404`.
- `support_reads200` · `orgOwner_is403` · `anonymous_is401`.

`PlatformSearchServiceTest` (Mockito) — the classification rules and input normalization as a unit:
mixed-case email, untrimmed query, `so-2026-00042` in lower case, `SO-000001` (the seed shape, which
a format regex would reject), an Arabic-Indic-digit reference, and a query whose only `@` is inside
a `provider_ref`-shaped string.

## Definition of done

- [ ] `mvn -o test` green; full `*IT` battery green (`DOCKER_HOST` at the colima socket).
- [ ] `mvn spotless:apply` clean.
- [ ] `EXPLAIN ANALYZE` before/after for every probe on `perfdb`, **on the `count(*)` shape**, in
      **V75's header** and the PR body, with the verdict on each index added *or skipped*. Skipping
      is a result — V73 skipped two and that was the most useful part of it.
- [ ] The already-served lookups (`app_user.email`, `org.slug`) confirmed already-served **by plan**,
      not by assertion.
- [ ] `perfdb` and the dev `inventorydb` both intact at the end, stated as an observation; any
      hand-applied candidate index that V75 does not keep is dropped again, stated too.
- [ ] Classification rules readable in one place; a reviewer can tell which probes a query fires
      without running it.
- [ ] The customer-name exclusion and its 1706 ms are in the handler Javadoc, not only in this story.
- [ ] `CLAUDE.md` §Platform admin gains the `GET /api/admin/search` entry (gitignored here).
