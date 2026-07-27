-- Cross-org search indexes for GET /api/admin/search?q= (stories/platform_search.md, slice 3).
--
-- Why any of this is needed: every unique in this schema puts a SCOPING column first, because
-- tenancy is compiler-enforced and no cross-org read existed before the platform-console epic.
--   sales_order_org_id_order_number_key            (org_id, order_number)          -- V17
--   payment_transaction_provider_provider_ref_key  (provider, provider_ref)        -- V22
--   customer_org_email_unique                      (org_id, email)                 -- V15
-- A cross-org `WHERE order_number = ?` matches none of them on its leading column. Verified: before
-- this migration no index anywhere in the schema led with order_number, provider_ref,
-- invoice_number or credit_note_number. This is V73's lesson one slice later, and not a
-- coincidence.
--
-- MEASURED ON THE count(*) SHAPE, not the top-5 fetch. The response promises a true `total`, and an
-- exact count cannot early-exit, so LIMIT 5 buys nothing: each probe pays its full predicate once
-- regardless. Measuring `… LIMIT 5` would show the sales_order probe finishing in a millisecond on
-- a lucky early match and hide everything below.
--
-- `EXPLAIN (ANALYZE, BUFFERS)` on the seeded `perfdb` (200 orgs / 200k customers / 1M orders /
-- 846,117 transactions, per tools/seed/README.md), 2026-07-27:
--
--   probe                              before (cold → warm)      after     plan after
--   sales_order.order_number = ?       6707 → 56-90 ms           0.07 ms   Index Only Scan
--   payment_transaction.provider_ref   2266 → 47-104 ms          0.05 ms   Index Only Scan
--   customer.email = ?                  524 → 12-29 ms           0.055 ms  Index Only Scan
--   app_user.email = ?                    1.19 → 0.041 ms        —         Index Only Scan (V11)
--   org.slug = ? OR fold_search(name)     5.39 → 1.44 ms         —         Seq Scan, 200 rows
--
-- ON THE BEFORE-NUMBERS, AND WHY THEY ARE A RANGE. The story recorded 2567 / 257 / 33 ms for the
-- first three. Those did not reproduce as single figures: the same query on the same data measured
-- 6707 ms on first touch and settled to 56-90 ms after five or six repeats. The cause is visible in
-- BUFFERS — `sales_order` and `payment_transaction` are 183 MB each against a 128 MB
-- shared_buffers, so neither ever fully caches and every run's cost depends on how much of it the
-- OS page cache happens to be holding. The invariant that matters is the PLAN, which was identical
-- to the story's every time: Parallel Seq Scan over the whole table, no index available. The range
-- is recorded rather than a single number because a console search is exactly the query that
-- arrives cold — nobody keeps 183 MB of order history warm for an occasional support lookup — so
-- the cold end is the honest one to design against, and it is the end this migration removes.
--
-- ALL THREE CANDIDATES WERE KEPT. Unlike V73, the planner declined none of them: each was chosen
-- immediately as an Index Only Scan on a warm-twice re-measure, and the sales_order probe fell four
-- orders of magnitude (6707 ms → 0.07 ms). Recorded because "skipping is a result" cuts both ways.
--
-- NOTHING IS ADDED FOR:
--   * app_user.email — already served, BY PLAN not by assertion: `Index Only Scan using
--     app_user_email_unique`, 3 buffers, 0.041 ms. V11's UNIQUE (email) leads with email, which is
--     the one identifier in this schema whose unique is not scope-first.
--   * org.slug / org.name — `org` is 200 rows. The measured plan is a plain Seq Scan at 1.44 ms
--     INCLUDING a fold_search() call per row, and org_slug_key (V15) is there if the planner ever
--     wants it. Indexing a 200-row table reflexively is write cost for no read benefit.
--   * customer.name_search / product_listing name_search — V62's GIN trigram indexes are untouched
--     and unused by this slice. The fuzzy customer-name probe is deliberately out of v1: pg_trgm
--     extracts no trigram below three characters, so a 2-char LIKE '%ab%' chooses the GIN index and
--     then rechecks the entire table through it — 1706 ms over 200,000 customers, against 0.045 ms
--     at three. See SearchAdminHandler's Javadoc for the full reasoning and what would bring it
--     back.
--
-- WRITE COST. All three are plain (non-partial) btrees on columns that are set once at insert and
-- never updated, so no existing UPDATE path pays a re-index. Sizes on perfdb:
-- sales_order_order_number_global_idx 6.7 MB, customer_email_global_idx 9.7 MB,
-- payment_transaction_provider_ref_global_idx 85 MB. The last is large only because the seed mints
-- 80-character synthetic references (`SEED-<uuid>-<uuid>`); real InstaPay references are short, so
-- expect a fraction of that in production. Even at 85 MB it buys 104 ms → 0.05 ms on the probe an
-- operator holding a bank reference fires.
--
-- perfdb SAFETY: these three were applied to perfdb BY HAND in psql (Flyway is never pointed at
-- perfdb — it shares one volume with the dev inventorydb). V75 keeps all three, so none was
-- dropped again; perfdb and this migration now agree.

-- Probe: sales_order.order_number, the one index this slice cannot ship without.
-- Serves  SELECT count(*) / SELECT … FROM sales_order WHERE order_number = ?
-- Not unique: UNIQUE is (org_id, order_number), and the same number legitimately exists in every
-- tenant at once — 200 of them on perfdb. That multiplicity is the fact the whole slice exists to
-- surface, so an index here must not assert away.
CREATE INDEX sales_order_order_number_global_idx
    ON sales_order (order_number);

-- Probe: payment_transaction.provider_ref. UNIQUE (provider, provider_ref) leads with the provider,
-- and an operator holding a bank reference does not know which provider row it landed under.
CREATE INDEX payment_transaction_provider_ref_global_idx
    ON payment_transaction (provider_ref);

-- Probe: customer.email. Reachable today only via (org_id, email). The smallest win of the three
-- (12-29 ms warm), kept because the whole point of the customer probe is the cross-tenant answer —
-- "this person shops at these three merchants" — which is a scan of every tenant by construction.
CREATE INDEX customer_email_global_idx
    ON customer (email);
