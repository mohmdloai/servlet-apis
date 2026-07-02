-- Notifications: the supertable model from sys-analysis/notifications/notifications.md, refined by
-- docs/notifications-plan.md §1. One NOTIFICATION (the channel-agnostic event) fans out to many
-- NOTIFICATION_DELIVERY rows (one per channel attempt), each with a channel-specific subtype row.
--
-- Amendment vs. the sys-analysis sketch: the recipient is polymorphic. A customer is NOT an app_user
-- (CRM-only, no auth), so a notification targets either a USER or a CUSTOMER, enforced by a CHECK.
--
-- Enum-shaped columns use TEXT + CHECK rather than real PG enums: `type` is intentionally open (new
-- event types must not need a migration), and keeping the sibling status/channel columns TEXT keeps
-- the family consistent. Discriminator/child rows are app-enforced (created in the same txn); a
-- periodic invariant check for orphan deliveries is a later concern.

CREATE TABLE notification (
    id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                 UUID        NOT NULL REFERENCES org(id),

    -- Polymorphic recipient: exactly one of the two id columns is set (see CHECK below).
    recipient_type         TEXT        NOT NULL CHECK (recipient_type IN ('USER', 'CUSTOMER')),
    recipient_user_id      UUID        REFERENCES app_user(id),
    recipient_customer_id  UUID        REFERENCES customer(id),

    type                   TEXT        NOT NULL,          -- 'ORDER_PLACED', 'PAYMENT_VERIFIED', ...
    title                  TEXT        NOT NULL,
    body                   TEXT        NOT NULL,          -- rendered, channel-agnostic

    -- Light polymorphic ref to the source event (same caveat as audit logs — no FK).
    source_type            TEXT,                          -- 'sales_order', 'invoice', ...
    source_id              UUID,

    status                 TEXT        NOT NULL CHECK (status IN ('PENDING', 'DISPATCHED')),
    payload                JSONB,                         -- extra structured data for templates

    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    dispatched_at          TIMESTAMPTZ,

    CONSTRAINT notification_recipient_ck CHECK (
        (recipient_type = 'USER'     AND recipient_user_id     IS NOT NULL AND recipient_customer_id IS NULL) OR
        (recipient_type = 'CUSTOMER' AND recipient_customer_id IS NOT NULL AND recipient_user_id     IS NULL))
);

-- Producer hot path: undispatched events per org, oldest first.
CREATE INDEX idx_notification_pending ON notification (org_id, created_at)
    WHERE status = 'PENDING';

-- Feed hot path: a user's own in-app feed, newest first.
CREATE INDEX idx_notification_recipient_user ON notification (recipient_user_id, created_at DESC)
    WHERE recipient_type = 'USER';

CREATE TABLE notification_delivery (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id  UUID        NOT NULL REFERENCES notification(id) ON DELETE CASCADE,
    channel          TEXT        NOT NULL CHECK (channel IN ('in_app', 'email')),  -- sms/whatsapp: future
    status           TEXT        NOT NULL CHECK (status IN ('PENDING', 'SENT', 'DELIVERED', 'FAILED')),
    attempts         INTEGER     NOT NULL DEFAULT 0,
    last_error       TEXT,
    sent_at          TIMESTAMPTZ,
    delivered_at     TIMESTAMPTZ,
    failed_at        TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT notification_delivery_channel_uq UNIQUE (notification_id, channel)  -- one delivery per channel per event
);

-- Delivery-sweeper hot path: undelivered attempts per channel, oldest first.
CREATE INDEX idx_delivery_pending ON notification_delivery (channel, created_at)
    WHERE status = 'PENDING';

CREATE TABLE notification_delivery_in_app (
    delivery_id  UUID        PRIMARY KEY REFERENCES notification_delivery(id) ON DELETE CASCADE,
    read_at      TIMESTAMPTZ,
    dismissed_at TIMESTAMPTZ,
    link_target  TEXT
);

-- Email subtype created now (full schema) so Phase 2 adds no notification tables; unused until then.
CREATE TABLE notification_delivery_email (
    delivery_id      UUID PRIMARY KEY REFERENCES notification_delivery(id) ON DELETE CASCADE,
    to_address       TEXT NOT NULL,
    subject          TEXT NOT NULL,
    rendered_html    TEXT,
    smtp_message_id  TEXT,
    bounce_status    TEXT,
    bounce_reason    TEXT
);
