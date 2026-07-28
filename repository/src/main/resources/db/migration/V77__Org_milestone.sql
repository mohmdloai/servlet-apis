-- Slice 7 of the platform console (stories/platform_tenant_funnel.md): store each tenant's path
-- through the product, not just its current state. `GET /api/admin/overview` already answers "how
-- many tenants are there"; nothing answers "how far did they get" — because nothing durable was
-- ever stamped at the moment a tenant reached a stage.
--
-- WHY A TABLE AND NOT A READ-TIME AGGREGATE OVER THE SOURCE TABLES
--   1. It cannot answer PUBLISHED honestly at all. ProductListingService.publish sets
--      published_at = now(); unpublish (ProductListingService:415) sets it back to NULL, and so
--      does the DRAFT reset on create. MIN(published_at) over an org is "the earliest of the
--      listings currently published", not "when this tenant first went live" — a tenant that
--      unpublished everything would retroactively lose a conversion the funnel already recorded.
--      No query shape recovers a column the application erased.
--   2. It would turn one indexed read into five cross-org aggregates over sales_order and payment —
--      the 183 MB-each pair slice 3 measured against a 128 MB shared_buffers. Every cross-org read
--      in this epic has had to justify its plan; this one would arrive already unjustifiable.
--   3. It would write each stage's definition into an aggregate expression, five of which then
--      drift from the funnel's meaning independently — the opposite of one definition, N callers.
--
-- APPEND-ONLY, STAMPED ONCE, NEVER UPDATED. Every write is
-- INSERT ... ON CONFLICT (org_id, milestone) DO NOTHING, so the first stamp wins and every later
-- one is a no-op by the PRIMARY KEY rather than by a check a future caller can forget to make. That
-- property is the whole reason this table exists — it is precisely what published_at failed to be.
--
-- MILESTONE IS OPEN TEXT; THE ORDERING IS CODE. Same split as platform_audit.action (V44's
-- precedent, restated by V76): no CHECK constraint enumerating the six values, so a future stage
-- needs no migration to be recorded — PlatformFunnelStage (Java enum) is the only place the order
-- lives, and a milestone the enum does not know is stored and simply not rendered.
--
-- ON DELETE CASCADE (unlike platform_audit's NO ACTION FKs). A deleted org's milestones are not a
-- historical record of anything — the tenant is gone. Contrast platform_audit, which is a ledger
-- and keeps its rows even past org deletion.
--
-- NO EXISTS-ON-ORG GUARD IN THE BACKFILL BELOW, UNLIKE V76's. V76 needed one because it backfilled
-- platform_audit.org_id from a JSONB payload (detail->>'org_id') that had never been FK-enforced,
-- so historical rows could legitimately name an org already gone. Every source column read below —
-- org.id itself, product_listing.org_id, sales_order.org_id, payment.org_id, and
-- user_org_role.org_id — is already a live NOT NULL FK REFERENCES org(id); Postgres has enforced
-- referential integrity on all four continuously, so a row from any of them naming a dead org
-- cannot exist. Re-adding the guard here would be ceremony, not safety — noted so it is a deliberate
-- omission rather than one the next reader has to re-derive.
CREATE TABLE org_milestone (
    org_id     UUID        NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    milestone  VARCHAR(32) NOT NULL,
    reached_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (org_id, milestone)
);

-- NO ix_org_milestone_stage_time — THE STORY SPECS ONE, MEASUREMENT DECLINED IT. See the header
-- below ("only a plan can refuse an index", V73's rule): this table is capped at 6 rows per org by
-- construction, so even at perfdb's 200-org / 1,000,000-order scale it holds ~1000 rows and every
-- funnel-read shape (cohort=all, cohort=30d, before and after building the candidate index) chose a
-- Seq Scan over org_milestone every time — cost 16-19 vs. the index path's own higher startup cost.
-- Building it would be dead weight that the planner would never touch at any org count this product
-- plausibly reaches. The PRIMARY KEY (org_id, milestone) already covers the one join this table
-- ever serves (org_milestone.org_id = org.id).

-- ================================ BACKFILL ================================
-- Six stages, five backfillable exactly and one (PUBLISHED) lossy by construction — finding 1
-- above. Going forward every write site closes the gap; this only recovers what history left
-- durable evidence of.

-- REGISTERED — every org, from its own birth. Both acquisition paths write an org row.
INSERT INTO org_milestone (org_id, milestone, reached_at)
SELECT id, 'REGISTERED', created_at FROM org
ON CONFLICT (org_id, milestone) DO NOTHING;

-- ACTIVATED — split by acquisition path, using slice 4's finding: provisioning writes an
-- ORG_CREATE audit row (PlatformOrgService.provision, in the same txn as the org insert);
-- self-serve registration writes none (AccountService never calls PlatformAuditService). Since
-- V76 that audit row carries org_id directly, so the join needs no JSONB probe.
--
-- Provisioned — org.setActive(true) at provisioning (PlatformOrgService:203), so activation and
-- registration are the same instant: reached_at = org.created_at.
INSERT INTO org_milestone (org_id, milestone, reached_at)
SELECT o.id, 'ACTIVATED', o.created_at
  FROM org o
 WHERE EXISTS (
         SELECT 1 FROM platform_audit pa
          WHERE pa.org_id = o.id AND pa.action = 'ORG_CREATE'
       )
ON CONFLICT (org_id, milestone) DO NOTHING;

-- Self-serve — activation is the owner's verification click (AccountService.verifyEmail calls
-- activateRegistrationPendingOrgs on this event), recoverable from app_user.email_verified_at.
-- A self-serve org has exactly one OWNER at registration time (AccountService.register grants it
-- to the single registrant); MIN() over its OWNER row(s) is a defensive aggregate, not evidence of
-- ambiguity, and still resolves to that one value in the common case. Still-PENDING orgs (no
-- OWNER ever verified) correctly get no ACTIVATED row here — the write site stamps it when they do.
INSERT INTO org_milestone (org_id, milestone, reached_at)
SELECT o.id, 'ACTIVATED', v.reached_at
  FROM org o
  JOIN LATERAL (
         SELECT MIN(u.email_verified_at) AS reached_at
           FROM user_org_role r
           JOIN app_user u ON u.id = r.user_id
          WHERE r.org_id = o.id AND r.role = 'OWNER'
       ) v ON v.reached_at IS NOT NULL
 WHERE NOT EXISTS (
         SELECT 1 FROM platform_audit pa
          WHERE pa.org_id = o.id AND pa.action = 'ORG_CREATE'
       )
ON CONFLICT (org_id, milestone) DO NOTHING;

-- CATALOGUED — first product_listing ever created for the org, whatever its current status.
INSERT INTO org_milestone (org_id, milestone, reached_at)
SELECT org_id, 'CATALOGUED', MIN(created_at)
  FROM product_listing
 GROUP BY org_id
ON CONFLICT (org_id, milestone) DO NOTHING;

-- PUBLISHED — lossy by construction (finding 1). Only a listing PUBLISHED right now still carries
-- a published_at; a tenant that unpublished every listing has no durable trace of its first launch
-- and is left unstamped rather than guessed. The counted residual is in this header, measured
-- after this statement ran on the target database — see below.
INSERT INTO org_milestone (org_id, milestone, reached_at)
SELECT org_id, 'PUBLISHED', MIN(published_at)
  FROM product_listing
 WHERE published_at IS NOT NULL
 GROUP BY org_id
ON CONFLICT (org_id, milestone) DO NOTHING;

-- FIRST_ORDER — every channel counts, including IN_STORE (placed_at is set at placement for
-- online/storefront orders; IN_STORE orders go DRAFT→PAID in one call and never pass through
-- markPendingPayment, so placed_at can be NULL there — created_at is the placement instant either
-- way).
INSERT INTO org_milestone (org_id, milestone, reached_at)
SELECT org_id, 'FIRST_ORDER', MIN(COALESCE(placed_at, created_at))
  FROM sales_order
 GROUP BY org_id
ON CONFLICT (org_id, milestone) DO NOTHING;

-- FIRST_PAYMENT — first payment row ever recorded, at any reconciliation outcome (MATCHED,
-- OVERPAID, or UNDERPAID — a partial prepayment is still money that arrived).
INSERT INTO org_milestone (org_id, milestone, reached_at)
SELECT org_id, 'FIRST_PAYMENT', MIN(received_at)
  FROM payment
 GROUP BY org_id
ON CONFLICT (org_id, milestone) DO NOTHING;

-- ============================ MEASURED, NOT ESTIMATED ============================
-- Full capture: tools/seed/results/org_milestone_backfill_135.txt
--
-- BACKFILL COST — measured on perfdb (200 orgs / 145,615 product_listing / 1,000,000 sales_order /
-- 840,117 payment, the 183 MB-class tables slice 3 measured), inside a transaction that was rolled
-- back at the end (perfdb is never migrated — CLAUDE.md — so this INSERTs, times, then undoes):
--   REGISTERED       11.6 ms  (200 rows)
--   CATALOGUED      898.6 ms  (200 rows, scan of 145,615)
--   PUBLISHED        22.3 ms  (200 rows, indexed by product_listing_published_idx)
--   FIRST_ORDER    3 014.0 ms (200 rows, scan of 1,000,000 — the dominant cost, no covering index
--                              for MIN(COALESCE(placed_at, created_at)) GROUP BY org_id)
--   FIRST_PAYMENT  1 007.9 ms (200 rows, scan of 840,117)
--   Total (excl. ACTIVATED): ~4.95 s cold, single pass, on a table 0 rows at the start.
--
-- ACTIVATED — perfdb is at V72 and structurally CANNOT host this measurement: platform_audit.org_id
-- is a V76 column that does not exist there yet (a stronger version of the "platform_audit is
-- empty" finding V76 already made — here the column itself is absent). Built a scratch database
-- instead (dropped after; never perfdb/inventorydb), sized to match V76's own scratch (200 orgs):
-- 100 provisioned (ORG_CREATE audit row) + 100 self-serve (owner email_verified_at set), plus
-- 50,000 unrelated platform_audit rows so the table is not a single-page toy.
--   ACTIVATED (provisioned leg)   4.2 ms (100 rows)
--   ACTIVATED (self-serve leg)    6.8 ms (100 rows)
--
-- COUNTED RESIDUAL (the slice-4 discipline): orgs with a product_listing or a sales_order but no
-- recoverable PUBLISHED stamp, measured on perfdb after the backfill ran —
--   0 of 200 orgs.
-- perfdb's seed data never unpublishes a listing once created, so every org with a catalogue still
-- has at least one currently-published row. This is a property of the seed corpus, not a proof the
-- gap is empty in production — a merchant that unpublishes everything is exactly finding 1's case,
-- and the real dev/prod count will only be known once this runs there. Zero residual on this dataset
-- does not change the design: PUBLISHED stays lossy by construction and no timestamp is synthesised
-- for the rows a future real database does find unstamped.
--
-- FUNNEL READ — see the note beside the (deliberately absent) index above: every shape tested
-- (cohort=all, cohort=30d, path=all; before and after building the candidate index) executed in
-- 0.7-1.9 ms on perfdb's real 200-org org_milestone population (~1000 rows once all six-ish stages
-- are stamped), always via Seq Scan + Hash Join, cost 26-31. The candidate index added cost (80-91)
-- and the planner never chose it — dropped per V73's "only a plan can refuse an index" rule.
-- path=self_serve / path=provisioned cohort reads (scratch DB, 50,100 platform_audit rows): 2.3-2.7
-- ms warm, Seq Scan on platform_audit (an all-orgs existence check has no equality predicate on
-- org_id, so V76's ix_platform_audit_org — built for a single tenant's timeline — does not apply
-- here; a different access pattern, not a regression).
-- ================================================================================
