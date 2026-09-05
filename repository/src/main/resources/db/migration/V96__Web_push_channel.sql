-- The Web Push channel (stories/web_push_channel.md): the third delivery channel after email
-- (Phase 2) and WhatsApp (V82), and the first a STAFF recipient has ever had besides the in-app
-- feed. Push accelerates; the feed row and the bell's poll still own delivery
-- (stories/portal_notifications.md §89). The immediate motivation is LOW_STOCK (V94): the person
-- who needs to know a shelf is empty is usually not looking at the dashboard.

-- The channel. Both CHECKs are independent and were last widened by V82 — same shape here.
ALTER TABLE notification_delivery
    DROP CONSTRAINT notification_delivery_channel_check;

ALTER TABLE notification_delivery
    ADD CONSTRAINT notification_delivery_channel_check
        CHECK (channel IN ('in_app', 'email', 'whatsapp', 'push'));

ALTER TABLE notification_preference
    DROP CONSTRAINT notification_preference_channel_check;

ALTER TABLE notification_preference
    ADD CONSTRAINT notification_preference_channel_check
        CHECK (channel IN ('in_app', 'email', 'whatsapp', 'push'));

-- One push delivery PER DEVICE, not per channel.
--
-- V44's UNIQUE (notification_id, channel) says "one delivery per channel per event". A staff member
-- with a phone and a laptop has two push targets that fail independently (the phone's subscription
-- is 410 Gone, the laptop's is fine). Modelling that as one delivery whose subtype holds N endpoints
-- would mean re-inventing per-target status, attempts and a "SENT if at least one" rule on top of a
-- pipeline that already has all of that PER DELIVERY. So the constraint is relaxed for this channel
-- only: a push delivery is one row per (notification, subscription), and every existing mechanism
-- — claim, attempt budget, stranded reaper, finalize-when-all-terminal, the failed queue — applies
-- unchanged. The other three channels keep their one-row guarantee.
ALTER TABLE notification_delivery
    DROP CONSTRAINT notification_delivery_channel_uq;

CREATE UNIQUE INDEX notification_delivery_channel_uq
    ON notification_delivery (notification_id, channel)
    WHERE channel <> 'push';

-- The device.
--
-- Keyed on the browser's push endpoint URL (unique per subscription by construction; upsert on
-- re-subscribe), NOT on the Redis session family: a family is TTL-bounded and garbage-collected on
-- read, so a row bound to it would dangle silently, and this codebase has never held a per-device
-- row in Postgres.
--
-- `token_version_at_subscribe` is the whole revocation story. A subscription is live only while
-- app_user.token_version still equals it, so every path that already bumps the version —
-- logout-all, a self password change, a MANAGER revoking a role, a platform ADMIN disabling the
-- account — silences push with no new call site. The same mechanism that already invalidates the
-- access tokens those actions must kill. A single-device logout is the client's job (unsubscribe +
-- DELETE before clearing cookies); a 404/410 from the push service prunes the row on send.
CREATE TABLE push_subscription (
    id                          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                     UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    endpoint                    TEXT        NOT NULL UNIQUE,
    p256dh                      TEXT        NOT NULL,   -- base64url, 65-byte uncompressed P-256 point
    auth                        TEXT        NOT NULL,   -- base64url, 16 bytes
    user_agent                  VARCHAR(255),
    token_version_at_subscribe  INTEGER     NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at                TIMESTAMPTZ
);

CREATE INDEX push_subscription_user_idx ON push_subscription (user_id);

COMMENT ON TABLE push_subscription IS
    'One browser push subscription of a staff user. Live only while token_version_at_subscribe = app_user.token_version; pruned on 404/410 from the push service.';
COMMENT ON COLUMN push_subscription.endpoint IS
    'The push service URL. A bearer capability: never returned by any read path, never logged in full.';

-- What was sent, frozen — the email precedent (notification_delivery_email) one level down: the
-- subscription's endpoint and keys plus the JSON payload, captured at produce time, so what went
-- out is reconstructable from the row rather than re-derived from a device that may since have
-- re-subscribed. `subscription_id` goes NULL when the device is pruned; the sweeper fails such a
-- delivery at the claim without a send.
CREATE TABLE notification_delivery_push (
    delivery_id      UUID    PRIMARY KEY REFERENCES notification_delivery(id) ON DELETE CASCADE,
    subscription_id  UUID    REFERENCES push_subscription(id) ON DELETE SET NULL,
    endpoint         TEXT    NOT NULL,
    p256dh           TEXT    NOT NULL,
    auth             TEXT    NOT NULL,
    payload_json     JSONB   NOT NULL,
    provider_status  INTEGER                       -- the push service's HTTP status on the last send
);

-- No other index: V44's idx_delivery_pending (channel, created_at) WHERE status = 'PENDING' already
-- serves the sweeper for every channel (V82's verdict), and push_subscription is read by user_id
-- and by endpoint, both covered above.
