-- stories/support_tickets.md — a merchant opens a ticket to the platform, the desk works it.
-- One ticket, one owner at every moment (OPEN = the desk, AWAITING_MERCHANT / RESOLVED = the
-- merchant, CLOSED = nobody); a message is the transition; every transition is a STATUS row in
-- the thread, so the history IS the thread. Vocabularies are open TEXT + CHECK with the Java
-- enums owning order and validation (the V42/V61/V77 verdict), so a fifth status costs no
-- migration. The ticket number is a GLOBAL sequence: an operator quotes "#1042" across tenants.
--
-- Indexes: the org ledger (org_id, last_activity_at DESC) and the desk queue
-- (status, blocking DESC, status_since) — the queue key is "how long has the desk held it".
-- perfdb carries no support rows (like every platform-plane table), so the plans were measured on
-- a scratch DB seeded 200 orgs × 50 tickets (600 OPEN / 1000 AWAITING / 800 RESOLVED / 7600
-- CLOSED, 67 blocking) — tools/seed/results/support_desk_187.txt. Warm, LIMIT 20:
--   OPEN queue  Index Scan ix_support_ticket_desk → Incremental Sort   0.23 ms
--   CLOSED tab  Seq Scan + top-N heapsort (the planner declines the index on 76 % of rows) 3.7 ms
--   counts      OPEN / blocking-open via the index 0.13 / 0.04 ms; CLOSED a Seq Scan 1.1 ms
--   org ledger  Bitmap on ix_support_ticket_org_activity, 50 rows/org               < 0.1 ms
-- The queue is the read an operator waits on and it is served; the closed ledger is a Seq Scan
-- over a table that fits in cache, exactly the shape the story predicted, so no third index.

CREATE SEQUENCE support_ticket_number_seq;

CREATE TABLE support_ticket (
    id                 UUID PRIMARY KEY,
    org_id             UUID NOT NULL REFERENCES org(id),
    number             BIGINT NOT NULL UNIQUE DEFAULT nextval('support_ticket_number_seq'),
    status             TEXT NOT NULL CHECK (status IN ('OPEN', 'AWAITING_MERCHANT', 'RESOLVED', 'CLOSED')),
    category           TEXT NOT NULL CHECK (category IN ('ACCOUNT', 'ORDERS', 'PAYMENTS', 'CATALOG', 'STOREFRONT', 'DEVICES', 'OTHER')),
    -- The merchant's fact ("I can't sell or take orders right now"), not a priority: sorts first
    -- on the desk and wears a danger chip. Priority is the desk's judgement and is slice 3.
    blocking           BOOLEAN NOT NULL DEFAULT false,
    subject            TEXT NOT NULL CHECK (length(subject) BETWEEN 1 AND 120),
    -- A frozen pointer at a record ("about SO-2026-00042"), never a foreign key: the ticket must
    -- survive the order being cancelled, and the desk resolves it through console search.
    ref_type           TEXT CHECK (ref_type IN ('ORDER', 'INVOICE', 'TRANSACTION', 'PRODUCT')),
    ref_id             UUID,
    ref_label          TEXT,
    opened_by          UUID NOT NULL REFERENCES app_user(id),
    opened_at          TIMESTAMPTZ NOT NULL,
    -- Stamped on every transition; the desk queue's sort key ("waiting since").
    status_since       TIMESTAMPTZ NOT NULL,
    -- Bumped on every message and transition; the ledgers' sort key.
    last_activity_at   TIMESTAMPTZ NOT NULL,
    -- The first SUPPORT message; a slice-3 metric, cheap to keep from day one.
    first_response_at  TIMESTAMPTZ,
    resolved_at        TIMESTAMPTZ,
    resolved_by        UUID REFERENCES app_user(id),
    closed_at          TIMESTAMPTZ,
    closed_by          UUID REFERENCES app_user(id),
    closed_reason      TEXT CHECK (closed_reason IN ('MERCHANT', 'SUPPORT', 'AUTO')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_support_ticket_ref      CHECK ((ref_type IS NULL) = (ref_id IS NULL)),
    CONSTRAINT ck_support_ticket_closed   CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CONSTRAINT ck_support_ticket_resolved CHECK (status <> 'RESOLVED' OR resolved_at IS NOT NULL)
);

COMMENT ON TABLE support_ticket IS
    'A merchant''s ticket to the platform desk (stories/support_tickets.md). One owner per status; a message is the transition; status_since is the desk queue key.';

CREATE INDEX ix_support_ticket_org_activity ON support_ticket (org_id, last_activity_at DESC);
CREATE INDEX ix_support_ticket_desk ON support_ticket (status, blocking DESC, status_since);

CREATE TABLE support_ticket_message (
    id          UUID PRIMARY KEY,
    org_id      UUID NOT NULL REFERENCES org(id),
    ticket_id   UUID NOT NULL REFERENCES support_ticket(id) ON DELETE CASCADE,
    -- NOTE exists from day one so the merchant read excludes it from the first commit; the desk
    -- writes the first one in slice 3. STATUS rows are the transitions, drawn as hairlines.
    kind        TEXT NOT NULL DEFAULT 'MESSAGE' CHECK (kind IN ('MESSAGE', 'NOTE', 'STATUS')),
    side        TEXT NOT NULL CHECK (side IN ('MERCHANT', 'SUPPORT')),
    -- NULL only for an automatic STATUS row (the slice-2 auto-close).
    author_id   UUID REFERENCES app_user(id),
    body        TEXT CHECK (length(body) BETWEEN 1 AND 4000),
    -- kind = STATUS: the status entered.
    status_to   TEXT,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_support_ticket_message_shape CHECK (
        (kind = 'STATUS' AND status_to IS NOT NULL)
        OR (kind <> 'STATUS' AND body IS NOT NULL AND author_id IS NOT NULL))
);

COMMENT ON TABLE support_ticket_message IS
    'The thread of a support_ticket: MESSAGE rows from either side, NOTE rows the merchant never sees, STATUS rows for every transition (stories/support_tickets.md).';

CREATE INDEX ix_support_ticket_message_thread ON support_ticket_message (ticket_id, created_at, id);

CREATE TABLE support_ticket_attachment (
    id           UUID PRIMARY KEY,
    org_id       UUID NOT NULL REFERENCES org(id),
    ticket_id    UUID NOT NULL REFERENCES support_ticket(id) ON DELETE CASCADE,
    message_id   UUID NOT NULL REFERENCES support_ticket_message(id) ON DELETE CASCADE,
    -- {orgId}/support/{uuid}-{name}: guarded on write (attach) and re-checked on read (presign).
    object_key   TEXT NOT NULL,
    content_type TEXT NOT NULL,
    file_name    TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE support_ticket_attachment IS
    'A screenshot on a support_ticket_message: an object key under the org''s support prefix, never bytes (stories/support_tickets.md).';

CREATE INDEX ix_support_ticket_attachment_message ON support_ticket_attachment (message_id);
