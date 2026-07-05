-- Optional human display name for an app_user. Nullable with no backfill — existing rows keep NULL
-- and clients fall back to email. Read by GET /api/me and the org members roster (stories/09). No
-- unique constraint (names are not identifiers); length is validated in the service layer.
ALTER TABLE app_user ADD COLUMN display_name TEXT;
