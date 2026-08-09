-- D4 follow-up: a claimed state for email delivery, so the SMTP round-trip no longer runs inside
-- the per-delivery transaction holding that row's lock and a pooled DB connection.
--
-- V44 modelled a delivery as PENDING -> SENT/FAILED, which forces the send to happen inside the
-- transaction that observes PENDING: there is no state meaning "a worker has this and is talking to
-- the provider right now". SENDING is that state, and claimed_at is when the claim was taken —
-- together they are a lease, so a row stranded by a crash mid-send can be identified and returned to
-- the queue instead of sitting SENDING forever.

ALTER TABLE notification_delivery
    DROP CONSTRAINT notification_delivery_status_check;

ALTER TABLE notification_delivery
    ADD CONSTRAINT notification_delivery_status_check
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'DELIVERED', 'FAILED'));

-- Nullable and unset for every existing row: nothing is mid-send at migration time, and a NULL here
-- means "not currently claimed" rather than an unknown. Cleared again on every release back to
-- PENDING, so it always describes the *current* claim and never a historical one.
ALTER TABLE notification_delivery
    ADD COLUMN claimed_at TIMESTAMPTZ;

-- The reaper's only query: claims older than the lease. Partial, because SENDING is a transient
-- state holding a handful of rows at any moment while the table grows without bound.
CREATE INDEX idx_notification_delivery_stranded
    ON notification_delivery (claimed_at)
    WHERE status = 'SENDING';
