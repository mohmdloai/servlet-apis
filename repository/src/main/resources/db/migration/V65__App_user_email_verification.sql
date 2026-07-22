-- Verify-to-activate for self-serve merchant registration (story 88).
--
-- app_user.email_verified_at is the staff-plane twin of customer.email_verified_at: NULL means the
-- inbox was never proven and login is blocked with a 403 ("Email not verified"). It is stamped by
-- redeeming an EMAIL_VERIFY token (the emailed link), and self-healed by any other inbox proof
-- (PASSWORD_RESET / INVITE redemption). Admin-plane creations (platform console, org provisioning)
-- are born verified — the admin vouches, and the invite flow re-proves the inbox anyway.

ALTER TABLE app_user ADD COLUMN email_verified_at TIMESTAMPTZ;

-- Grandfather every existing account: nobody alive gets locked out by this deploy. created_at is
-- the honest stamp we have (they all predate verification existing at all).
UPDATE app_user SET email_verified_at = created_at;

-- Third one-shot token purpose on the V49 machinery: the registration verification link (48h TTL).
ALTER TABLE app_user_magic_token DROP CONSTRAINT app_user_magic_token_purpose_check;
ALTER TABLE app_user_magic_token
    ADD CONSTRAINT app_user_magic_token_purpose_check
    CHECK (purpose IN ('PASSWORD_RESET', 'INVITE', 'EMAIL_VERIFY'));

-- The purge job's scan: unverified accounts past the grace window. Partial — verified rows (the
-- overwhelming majority) never enter the index.
CREATE INDEX app_user_unverified_purge ON app_user (created_at) WHERE email_verified_at IS NULL;
