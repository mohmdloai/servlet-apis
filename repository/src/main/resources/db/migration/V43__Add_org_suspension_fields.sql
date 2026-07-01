-- Org lifecycle (see docs/platform-admin-plan.md, slice 5).
-- org.active (V15) is the enforcement flag; these columns record WHY and WHEN a suspension happened.
-- No enforcement lives in SQL - AuthzHelper.requireOrgAccess rejects members of an inactive org
-- (platform admin bypass preserved), reading a Redis mirror of org.active on the hot path.

ALTER TABLE org ADD COLUMN suspended_at     TIMESTAMPTZ;
ALTER TABLE org ADD COLUMN suspended_reason TEXT;
