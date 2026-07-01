-- Platform-tier audit ledger (see docs/platform-admin-plan.md, slice 0).
-- A shared, minimal accountability trail for every mutating platform action:
-- org suspend/reactivate, user create/disable, role grant/revoke, force-logout, etc.
-- impersonation_event (V41) stays its own dedicated ledger and can be folded in later.

CREATE TABLE platform_audit (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id     UUID        NOT NULL REFERENCES app_user(id),   -- the platform admin
    action       VARCHAR(64) NOT NULL,                           -- 'ORG_SUSPEND','ROLE_GRANT',...
    target_type  VARCHAR(32) NOT NULL,                           -- 'ORG','USER','SESSION'
    target_id    UUID,                                           -- nullable: some actions have no single target row
    detail       JSONB,                                          -- action-specific payload
    source_ip    VARCHAR(64),
    user_agent   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_platform_audit_target ON platform_audit (target_type, target_id, created_at);
CREATE INDEX ix_platform_audit_actor  ON platform_audit (actor_id, created_at);
