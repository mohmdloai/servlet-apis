-- Customer-portal P1 (portal_auth_core.md): a trust signal stamped on the first successful OTP
-- verify — distinguishes "typed an email at checkout" from "proved ownership of it". Nullable; it
-- never gates login (every OTP re-proves ownership) and is for display / future policy only. The OTP
-- challenge and portal sessions live in Redis (self-expiring), so this column is the slice's only
-- schema change.
ALTER TABLE customer ADD COLUMN email_verified_at TIMESTAMPTZ;
