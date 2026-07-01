-- Impersonation slice (see docs/impersonation.md).
-- Layer-1 audit ledger for impersonation START/STOP, plus Layer-2 stamping on inventory_log.

CREATE TABLE impersonation_event (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    impersonator_id UUID        NOT NULL REFERENCES app_user(id),
    target_id       UUID        NOT NULL REFERENCES app_user(id),
    tier            VARCHAR(16) NOT NULL,             -- 'PLATFORM' | 'ORG'
    scope_org_id    UUID        REFERENCES org(id),   -- NULL for PLATFORM tier
    event           VARCHAR(16) NOT NULL,             -- 'START' | 'STOP'
    reason          TEXT,
    source_ip       VARCHAR(64),
    user_agent      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_imp_tier  CHECK (tier  IN ('PLATFORM','ORG')),
    CONSTRAINT ck_imp_event CHECK (event IN ('START','STOP')),
    -- ORG events must carry a scope org; PLATFORM events must not.
    CONSTRAINT ck_imp_scope CHECK ((tier = 'ORG') = (scope_org_id IS NOT NULL))
);

CREATE INDEX ix_impersonation_event_target ON impersonation_event (target_id, created_at);
CREATE INDEX ix_impersonation_event_actor  ON impersonation_event (impersonator_id, created_at);

-- Layer-2: the driver behind an overlay is stamped onto every inventory_log row it produces.
-- Null on normal sessions; the row's actor_id/actor_type remain the principal (the target).
ALTER TABLE inventory_log ADD COLUMN impersonator_id UUID REFERENCES app_user(id);
