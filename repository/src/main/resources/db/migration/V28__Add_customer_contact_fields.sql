-- Extend customer with contact fields used at order placement.
-- All nullable so existing rows (created via /api/orgs/{orgId}/customers with
-- email only) stay valid; the sales-order endpoint upserts these via
-- INSERT … ON CONFLICT … DO UPDATE keyed on (org_id, email).
ALTER TABLE customer ADD COLUMN name    TEXT;
ALTER TABLE customer ADD COLUMN phone   TEXT;
ALTER TABLE customer ADD COLUMN address TEXT;
