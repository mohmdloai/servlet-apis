-- Slice 4 of the platform console (stories/platform_org_timeline.md): give platform_audit an
-- org_id, so "what has been done to this tenant" is one indexed predicate instead of a rule
-- re-inferred at every read site.
--
-- WHY A COLUMN AND NOT A JSONB PROBE
-- A third of the audit vocabulary targets the USER, not the tenant: ORG_ROLE_GRANT/REVOKE carry
-- target_type='USER' and hide the org in detail->>'org_id', and the provisioned owner's
-- USER_CREATE names no org at all. So `WHERE target_type='ORG' AND target_id=?` returns a
-- tenant's lifecycle and silently drops every membership change to it — which is the whole of an
-- access-review question. V42's ix_platform_audit_target (target_type, target_id, created_at)
-- cannot serve a JSONB probe, and an expression index on detail->>'org_id' would still not cover
-- the provisioned owner. One column, one definition, one index.
--
-- NULLABLE ON PURPOSE. SYSTEM_ROLE_*, SESSION_REVOKE, FORCE_LOGOUT_ALL, USER_ENABLE/DISABLE,
-- PASSWORD_RESET and a plain admin-plane USER_CREATE concern no tenant. NOT NULL here would force
-- a lie; "this operator was force-logged-out" is not an event that happened to a merchant.
--
-- FK SHAPE. Plain REFERENCES org(id) (NO ACTION), matching impersonation_event.scope_org_id from
-- V41 exactly — same family, same nullability, same rule. Org deletion is already restricted by
-- ~30 business tables carrying org_id NO ACTION, so this adds no new class of block.
--
-- ============================ MEASURED, NOT ESTIMATED ============================
-- perfdb could NOT host this measurement: perfdb.platform_audit is EMPTY (0 rows) and its
-- impersonation_event holds only 6 PLATFORM-tier rows, so every plan there is a trivial scan and
-- the planner would never consider an index. Measured instead on a scratch DB reproducing the
-- cardinality that decides the question — 500 400 audit rows / 20 000 ORG-tier overlays / 200
-- orgs (~2 500 audit + 100 overlay rows per tenant), Postgres 17, shared_buffers 128 MB.
-- Caveat: unlike perfdb there is no 183 MB neighbour competing for cache here, so the warm
-- numbers below are the optimistic end.
--
-- Migration cost at 500 400 rows (total ~15.2 s):
--   ALTER TABLE ADD COLUMN            11 ms
--   backfill 1 (ORG-targeted)       3 035 ms  (100 200 rows)
--   backfill 2 (role grant/revoke)  1 880 ms  (100 000 rows)
--   backfill 3 (provisioned owner)  9 923 ms  (    200 rows)
--   CREATE INDEX                      315 ms
-- Backfill 3 dominates despite touching 200 rows: it is a self-join, and UPDATE ... FROM runs it
-- serially. The identical SELECT is 124 ms under a Parallel Hash Join, so the cost is the
-- statement's serial plan plus the heap already carrying 200 k dead tuples from backfills 1-2 —
-- write amplification, not join cost. It scales with table size, not with rows updated.
--
-- Merged read (platform_audit UNION ALL impersonation_event, ordered once, page size 20).
-- Reported as first-touch / fully-warm:
--   probe                     before                after ix_platform_audit_org  + ix_imp_scope_org
--   page 0                    72.4 / 30.9 ms        23.7 / 6.7-6.9 ms            20.3 / 5.6 ms
--   deep page (offset 2000)   57.5 / 27.8 ms         5.4 / 4.6-4.7 ms             3.7 / 3.4 ms
--   count(*) for the envelope 38.5 / 27.5 ms         1.8 / 1.4-1.5 ms             0.55 / 0.47 ms
-- Plan change is the part that reproduces: Parallel Seq Scan on platform_audit -> Bitmap Index
-- Scan for the reads and Index Only Scan for the count. The planner declined neither index, so
-- both are kept (V73's rule: only a plan can refuse an index).
-- Index sizes: ix_platform_audit_org 17 MB on a 99 MB heap; ix_imp_scope_org 808 kB on 20 k rows.
--
-- COUNTED RESIDUAL after the backfill (500 400 rows -> 200 400 attributed, 300 000 still NULL):
--   FORCE_LOGOUT_ALL   50 000     SYSTEM_ROLE_GRANT  50 000
--   PASSWORD_RESET     50 000     USER_DISABLE       50 000
--   SESSION_REVOKE     50 000     USER_ENABLE        50 000
-- All six are CORRECT to be NULL — every one is platform-wide by nature and concerns no tenant.
-- USER_CREATE ... detail->>'via' = 'org_provision' still NULL after the join: 0. Counted, not
-- assumed — the join is a functional dependency the ledger already stores (see below), and it
-- held. On the dev inventorydb (5 audit rows) the same statements run in single-digit ms.
-- ================================================================================

ALTER TABLE platform_audit ADD COLUMN org_id UUID REFERENCES org(id);

-- Backfill 1 — anything targeting the tenant directly. Keyed on target_type rather than an
-- 'ORG\_%' action prefix on purpose: ORG_ROLE_GRANT/ORG_ROLE_REVOKE share that prefix but target
-- the USER, and target_type='ORG' is the invariant that actually means "target_id is an org id".
-- The EXISTS guard is not ceremony: an org created and deleted before it accrued any business
-- data leaves audit rows naming an id that is gone, and without the guard the new FK would abort
-- the whole migration.
UPDATE platform_audit pa
   SET org_id = pa.target_id
 WHERE pa.org_id IS NULL
   AND pa.target_type = 'ORG'
   AND pa.target_id IS NOT NULL
   AND EXISTS (SELECT 1 FROM org o WHERE o.id = pa.target_id);

-- Backfill 2 — membership changes. These target the USER and record the tenant in the payload;
-- that is precisely the rule this column exists to stop re-inferring.
UPDATE platform_audit pa
   SET org_id = (pa.detail->>'org_id')::uuid
 WHERE pa.org_id IS NULL
   AND pa.action IN ('ORG_ROLE_GRANT','ORG_ROLE_REVOKE')
   AND pa.detail->>'org_id' IS NOT NULL
   AND EXISTS (SELECT 1 FROM org o WHERE o.id = (pa.detail->>'org_id')::uuid);

-- Backfill 3 — the owner minted by org provisioning. Its detail is {email, via:'org_provision'}
-- and names no org, but the PAIRING is recorded, not inferred: PlatformOrgService writes both
-- rows in one transaction and the ORG_CREATE carries {slug, owner_id, owner_minted}.
--
-- The join is unique WITHOUT a timestamp window. owner_minted=true means the user did not exist a
-- moment earlier, and a user is created once — so at most one ORG_CREATE can ever carry
-- (owner_id = U, owner_minted = true). Provisioning that same user as owner of a second org finds
-- them already present, writes no USER_CREATE, and records owner_minted=false. This is a
-- functional dependency the ledger already stores, not a heuristic that happens to fit this data;
-- PlatformOrgTimelineIT.provisionedOwnerCreation_backfillJoinIsUnique asserts it against a decoy
-- owner_minted=false row.
--
-- DO NOT add a created_at proximity clause "for safety". It cannot tighten an already-unique join,
-- and it would silently drop rows if a future provisioning path ever writes the two entries a
-- second apart.
UPDATE platform_audit u
   SET org_id = o.target_id
  FROM platform_audit o
 WHERE u.org_id IS NULL
   AND u.action = 'USER_CREATE'
   AND u.detail->>'via' = 'org_provision'
   AND o.action = 'ORG_CREATE'
   AND o.detail->>'owner_minted' = 'true'
   AND (o.detail->>'owner_id')::uuid = u.target_id
   AND EXISTS (SELECT 1 FROM org g WHERE g.id = o.target_id);

-- Everything else stays NULL, and that is the correct answer rather than a gap. Attributing a
-- SYSTEM_ROLE_GRANT or a FORCE_LOGOUT_ALL to an org the user happens to belong to would be the
-- console-that-lies failure committed in a migration.

CREATE INDEX ix_platform_audit_org ON platform_audit (org_id, created_at DESC);

-- The timeline's second leg. V41 indexed impersonation_event by target and by actor, but never by
-- the tenant an ORG-tier overlay was scoped to — which is the predicate a per-tenant timeline
-- runs. Not named in the story; added because it was measured and the planner took it (seq scan
-- -> Bitmap Index Scan, and the envelope's count 1.4 ms -> 0.47 ms).
CREATE INDEX ix_impersonation_event_scope_org ON impersonation_event (scope_org_id, created_at DESC);
