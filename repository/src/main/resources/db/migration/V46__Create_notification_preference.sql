-- Notification preferences (docs/notifications-plan.md §4) — the opt-out layer over the delivery
-- fan-out. A row SUPPRESSES a (subject, type, channel): absence means enabled, so every wired event
-- still fires by default. The subject is polymorphic (a USER or a CUSTOMER), mirroring the
-- notification recipient. `type` is a NotificationType name OR the sentinel 'ALL' (every type on that
-- channel); resolution prefers an exact-type row over an 'ALL' row. Enum-shaped columns are
-- TEXT+CHECK, matching the notification family.

CREATE TABLE notification_preference (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       UUID        NOT NULL REFERENCES org(id),

    -- Polymorphic subject: exactly one of the two id columns is set (see CHECK below).
    subject_type TEXT        NOT NULL CHECK (subject_type IN ('USER', 'CUSTOMER')),
    user_id      UUID        REFERENCES app_user(id),
    customer_id  UUID        REFERENCES customer(id) ON DELETE CASCADE,

    type         TEXT        NOT NULL,                            -- a NotificationType name, or 'ALL'
    channel      TEXT        NOT NULL CHECK (channel IN ('in_app', 'email')),
    enabled      BOOLEAN     NOT NULL,

    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT notification_preference_subject_ck CHECK (
        (subject_type = 'USER'     AND user_id     IS NOT NULL AND customer_id IS NULL) OR
        (subject_type = 'CUSTOMER' AND customer_id IS NOT NULL AND user_id     IS NULL))
);

-- One preference per (subject, type, channel); the upsert conflict target for each subject kind.
CREATE UNIQUE INDEX notification_preference_user_uq
    ON notification_preference (org_id, user_id, type, channel)     WHERE subject_type = 'USER';
CREATE UNIQUE INDEX notification_preference_customer_uq
    ON notification_preference (org_id, customer_id, type, channel) WHERE subject_type = 'CUSTOMER';
