# The customer read is a pre-commerce stub — make it a CRM record

> Branch `133_feat/org-customer-reads`, cut from **`master`** (highest PR/issue is #131 — re-verify
> at implementation time, and note story `platform_resend_verification.md` may take #132 first).
> Frontend pair: `frontst` story 80 (`80_st_org_customers.md`, branch `78_feat/…`) — **land this
> backend first**.
> **Probably no migration** — V62 already generated `customer.name_search` with a GIN trigram index
> and V75 added `customer (email)`. Confirm by measurement, not by assumption; see §Measurement.
>
> Named in the platform-console epic's "Open, and deliberately so": *"There is no customer surface
> anywhere in the admin app… whoever builds the org-plane customer screen also unblocks a real
> `searchResultPath('customer')`."* Slice 3 found the gap; this is half of closing it.

---

## The reality this corrects

"The backend read exists" is true and misleading. `GET /api/orgs/{orgId}/customers` has been there
since before storefront commerce, and it has not moved since:

```java
public record /* CustomerResponse */ (UUID id, UUID orgId, String email,
                                      OffsetDateTime createdAt, OffsetDateTime updatedAt)
```

The `Customer` model carries `name`, `phone`, `address` and `email_verified_at`. **None of them
cross the wire.** Anonymous checkout writes them, the portal's `PATCH /api/portal/me` edits them, the
review worklist displays customer names from its own join — and the customer's own record answers
with an email and two timestamps.

Three consequences, each of which independently blocks the screen story 80 wants to build:

1. **A directory that cannot show a name.** Every row would read as an email address. For an
   Arabic-locale merchant whose customers gave their names at checkout, that is the wrong column.
2. **No search.** `findAll(orgId, offset, limit)` is `created_at DESC` and nothing else. A merchant
   with 4 000 customers has a paged list and no way to find one — which is the only reason anyone
   opens a customer directory.
3. **No way to answer "what did they buy".** `SalesOrderHandler` has no `customer_id` filter and
   there is no `/customers/{id}/orders`. The single question a customer record exists to answer has
   no endpoint behind it.

## The change

### 1. `CustomerResponse` carries the record

Add `name`, `phone`, `address`, `email_verified_at` (nulls omitted, as everywhere).

**This is the org plane, and that is why it is fine.** Staff already see customer name and email on
the reviews worklist, the comments worklist and every invoice's frozen `customer_name`; the CRM
record is theirs. What must **not** move is the neighbouring whitelist: `PlatformSearchResult` still
carries no customer name, phone or address, pinned by `results_carryNoCustomerPii`, because that one
is **cross-org** and read by platform operators. Two different planes, two different rules, and this
change is not a precedent for relaxing the other. Say so in the DTO's Javadoc — the next person will
see one customer DTO carrying an address and another refusing to, and should find the reason there
rather than "fixing" the inconsistency.

### 2. `?q=` on the list

One parameter, matched against **name and email**, OR'd:

- the **name** leg goes through the DB's own `fold_search` on both sides against the generated
  `name_search` column — the `ProductRepositoryImpl.searchCondition` precedent, and the reason V62
  generated that column in the first place;
- the **email** leg normalizes with `Text.normalizeEmail` — the same function that wrote the column,
  which is slice 3's correction verbatim (*"normalize with the same function that wrote the column"*).

Blank/absent `q` → the unfiltered list, no error. Whitespace-only → treated as absent.

**Ordering does not change under search.** `created_at DESC` always. This is a directory, not a
worklist, so the queue-vs-ledger convention does not apply — and a list that re-sorts itself when the
operator types is disorienting for no gain. State it in the Javadoc, because every other paged read
in this codebase *does* switch ordering on a filter and the deviation will look like an oversight.

**The minimum length is a measurement, not a copied constant.** Slice 3 set a 2-character floor on
the cross-org search *as* its DoS defence and the epic's own lessons record that this failed: a
2-character `LIKE` extracts no trigram, so pg_trgm picks the GIN index and then rechecks the whole
table — 1706 ms across `perfdb`'s 200k customers. **This query is org-scoped**, so the recheck is
bounded by one tenant's customers rather than all of them, and the honest floor may well be lower
here. Measure it (§Measurement) and set the floor at what you measured, naming the number in the
400. Do not copy `2` because slice 3 has a `2`, and do not copy `3` because slice 3's *lesson*
mentions three — the shapes differ and the epic's rule is that a parameter which changes the plan is
its own measurement.

### 3. The customer's orders — a subresource, not a filter

`GET /api/orgs/{orgId}/customers/{id}/orders?page=&size=` (VIEWER), newest-first `PageResponse` of
the **lean order row** the orders worklist already serves — number, status, money meter, placed_at.
Unknown customer → 404 (a path segment names a thing; a customer with no orders → an empty page).

**Not** `?customer_id=` on `/sales-orders`, and the reason is that route's existing shape rather than
taste: a bare `GET /api/orgs/{orgId}/sales-orders` is a reserved **400**, and `?order_number=` returns
**a single object, not a list**. Adding a third mode that returns a `PageResponse` would give one URL
three incompatible response shapes keyed on which parameter you passed. Slice 3 established that a
route's shape is part of its honesty; this is the same rule applied to a route that already spent its
budget.

Newest-first because a customer's history is read as a ledger — the last thing they bought is the
thing being asked about.

### 4. What this slice deliberately does not do

**Staff cannot edit `name` / `phone` / `address`.** `CustomerService.update(orgId, id, email)` takes
only the email today, and it stays that way. Those three fields are written by the customer's own
checkout and portal profile, and letting staff overwrite them raises a question this slice has no
mandate to answer: at the next checkout upsert, whose value wins — the address the shopper just
typed, or the one an agent corrected last week? That is a real decision with a real failure mode
(silently reverting a customer's own correction), and it is separable. The read is what unblocks the
screen; the write is its own story.

## Measurement

Against `perfdb`, which seeds ~200k customers — and pick the **largest single org**, not a random
one, since the whole point is that the org filter bounds the scan:

- `EXPLAIN (ANALYZE, BUFFERS)`, warm-twice, for `?q=` at **1, 2, 3 and 4 characters**, name leg and
  email leg separately and then combined. Report a **cold→warm range** with the cache state named;
  the epic has now twice recorded that a single warm sample presented as a fact was wrong (slice 3's
  tables were 183 MB against a 128 MB `shared_buffers`, so nothing cached and the "before" number
  was 6707 ms, not 2567 ms).
- The same for the orders subresource on a customer with a long history.
- **Only then** decide whether any index is missing. The expectation is that none is: V62's
  `customer_name_search_trgm_idx` and V75's `customer (email)` btree are both already there. If the
  planner wants something else, add it and record the before/after; **if it declines a candidate,
  drop it and say so — skipping an index is a result.**
- Note that `platform_audit` was empty on `perfdb` when slice 4 looked (see
  [[perfdb-platform-tables-empty]]); `count(*)` the tables you are about to `EXPLAIN` before
  believing a fast plan.

## Tests

`CustomerReadsIT` (api module, TestContainers) — **two orgs throughout**:

- `listCarriesTheFullRecord` — a customer created by checkout (name/phone/address set) comes back
  with all of it; a customer created by `POST /customers` (email only) comes back with the nulls
  omitted rather than as empty strings.
- `search_matchesNameAndEmail` · `search_isDiacriticAndCaseInsensitive` — an Arabic name found via a
  differently-normalized spelling, which is what `fold_search` is for and the only assertion that
  proves the fold is actually being applied on both sides.
- `search_foldsArabicIndicDigitsInPhoneLikeTerms` — **only if** the phone leg is in scope; it is not
  in this story. Written here as an explicit non-goal so nobody adds it silently: `q` matches name
  and email, not phone. If phone search is wanted it is a decision with its own normalization
  (`Text.normalizeNumeric`) and its own index question.
- `search_belowMinimum_is400_namingTheMinimum` — with whatever floor the measurement chose.
- `search_neverCrossesOrgs` — the second org's identically-named customer never appears.
- `listOrderingIsStableUnderSearch` — `created_at DESC` with and without `q`.
- `customerOrders_newestFirst` · `customerOrders_unknownCustomerIs404` ·
  `customerOrders_noOrdersIsEmptyPage` · `customerOrders_neverCrossesOrgs`.
- `viewerCanRead` · `anonymous_is401` · `otherOrgMember_is403`.
- **`platformSearchStillCarriesNoCustomerPii`** — re-assert the cross-org whitelist from this branch.
  It is already pinned by `results_carryNoCustomerPii`, and re-asserting it here is the cheapest way
  to make sure enriching the org-plane DTO did not leak sideways through a shared mapper.

## Definition of done

- [ ] `mvn -o test` green; full `*IT` battery green. Export **both**
      `DOCKER_HOST=unix:///Users/ninja/.colima/default/docker.sock` and
      `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. **Do not use
      `TESTCONTAINERS_RYUK_DISABLED=true`.**
- [ ] `mvn spotless:apply` clean.
- [ ] The measured `EXPLAIN` table, cold→warm with the cache state named, and the chosen `q` floor
      justified by a number in it.
- [ ] Migration only if measurement demanded one; "no migration" is the expected and preferred
      outcome, recorded as an observation either way.
- [ ] `CLAUDE.md` §"Org-scoped resources" updates the `/customers` line — the enriched DTO, `?q=`,
      the orders subresource, and the explicit note that name/phone/address stay read-only here.
- [ ] `perfdb` and the dev `inventorydb` both intact. **Never `docker compose down -v`**, and never
      point Flyway at `perfdb`.
