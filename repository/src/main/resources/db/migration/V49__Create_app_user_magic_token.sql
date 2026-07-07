-- Single-use, hashed capability tokens for app_user credential flows (registration/forgot-password,
-- stories/11_st_platform_admin_console.md). The app_user analog of customer_magic_token (V45): a
-- transactional email carries a raw, full-entropy token in its URL; only its SHA-256 hash is stored.
--
-- Two purposes, both genuinely one-shot (unlike the customer VIEW_ORDER link, which is multi-use):
--   PASSWORD_RESET — self-service "forgot password".
--   INVITE         — a provisioned owner (unusable password, see PlatformOrgService.provision) or an
--                    admin-created account sets its first password and activates.
-- consumed_at is stamped on redeem, so a leaked-then-used link cannot be replayed.

CREATE TABLE app_user_magic_token (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,

    token_hash   VARCHAR(64) NOT NULL UNIQUE,                 -- SHA-256 hex; the raw token lives only in the URL
    purpose      TEXT        NOT NULL CHECK (purpose IN ('PASSWORD_RESET', 'INVITE')),

    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,                                 -- stamped on redeem; a used token never resolves again
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Hot lookup path: resolve a presented token by hash, ignoring already-consumed tokens.
CREATE INDEX app_user_magic_token_lookup ON app_user_magic_token (token_hash) WHERE consumed_at IS NULL;

-- Cleanup scans for an eventual purge job (delete rows well past expiry).
CREATE INDEX app_user_magic_token_cleanup ON app_user_magic_token (expires_at);
