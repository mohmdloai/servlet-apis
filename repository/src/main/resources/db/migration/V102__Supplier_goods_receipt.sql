-- Inbound slice 1 (stories/supplier_goods_receipt.md): a supplier, a receipt that knows what the
-- goods cost, and the column that links a stock movement to its delivery note.
--
-- The receipt posts NO ledger entry of its own: each line already writes an inventory_log row and
-- V101's STOCK/MOVED template posts those (DR 1200 / CR 2000). All that changes is the amount —
-- unit_cost is now what somebody paid. Hence: no new chart account, no new template, no new enum.

-- supplier ≅ customer (V2 → V81): org-scoped CRM record, no password, no portal, no auth ever.
CREATE TABLE supplier (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID        NOT NULL REFERENCES org(id),
    name        TEXT        NOT NULL COLLATE "und-x-icu",
    name_search TEXT        GENERATED ALWAYS AS (fold_search(name)) STORED,
    phone       TEXT,
    phone_e164  TEXT,
    email       TEXT,
    address     TEXT,
    notes       TEXT,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE supplier IS
    'Who the shop buys from (stories/supplier_goods_receipt.md). A CRM record, the customer table''s mirror: no authentication, no portal, nothing supplier-facing.';
COMMENT ON COLUMN supplier.name_search IS
    'fold_search(name) (V62) — unique per org, so two spellings of one supplier cannot become two AP balances.';

-- One supplier per FOLDED name: "أحمد" and "احمد" are one supplier, not two half-balances.
CREATE UNIQUE INDEX supplier_org_name_idx ON supplier (org_id, name_search);
CREATE INDEX supplier_org_active_name_idx ON supplier (org_id, active, name);

CREATE TABLE goods_receipt (
    id                 UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id             UUID          NOT NULL REFERENCES org(id),
    supplier_id        UUID          NOT NULL REFERENCES supplier(id),
    receipt_number     TEXT          NOT NULL,
    status             TEXT          NOT NULL DEFAULT 'POSTED'
                                     CHECK (status IN ('POSTED', 'VOIDED')),
    received_at        TIMESTAMPTZ   NOT NULL,
    supplier_reference TEXT,
    notes              TEXT,
    total_cost         NUMERIC(14,2) NOT NULL CHECK (total_cost >= 0),
    idempotency_key    TEXT,
    voided_at          TIMESTAMPTZ,
    void_reason        TEXT,
    voided_by          UUID          REFERENCES app_user(id),
    created_by         UUID          REFERENCES app_user(id),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (org_id, receipt_number)
);

COMMENT ON TABLE goods_receipt IS
    'A delivery, keyed off the supplier''s note. A document OVER inventory_log movements, never a second source of truth: total_cost = SUM(line.line_total) by construction, frozen for slice 2''s bill to match.';
COMMENT ON COLUMN goods_receipt.supplier_reference IS
    'THEIR delivery-note number, not ours. Deliberately not unique — what a duplicate supplier document means is slice 2''s question.';

CREATE UNIQUE INDEX goods_receipt_idem_idx ON goods_receipt (org_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX goods_receipt_org_received_idx ON goods_receipt (org_id, received_at DESC, id DESC);
CREATE INDEX goods_receipt_supplier_idx ON goods_receipt (org_id, supplier_id, received_at DESC);

CREATE TABLE goods_receipt_line (
    id               BIGSERIAL     PRIMARY KEY,
    org_id           UUID          NOT NULL REFERENCES org(id),
    goods_receipt_id UUID          NOT NULL REFERENCES goods_receipt(id) ON DELETE CASCADE,
    product_id       UUID          NOT NULL REFERENCES product(id),
    quantity         INT           NOT NULL CHECK (quantity > 0),
    unit_cost        NUMERIC(14,2) NOT NULL CHECK (unit_cost >= 0),
    line_total       NUMERIC(14,2) NOT NULL CHECK (line_total >= 0),
    UNIQUE (goods_receipt_id, product_id)
);

COMMENT ON COLUMN goods_receipt_line.unit_cost IS
    '0.00 is legal and means free-of-charge goods: stock moves, journal_line (amount > 0) posts nothing, ledger_skip records UNCOSTED honestly.';

CREATE INDEX goods_receipt_line_receipt_idx ON goods_receipt_line (goods_receipt_id);

-- GRN-YYYY-NNNNN, per org per year, advanced FOR UPDATE inside the issuing txn (V30's rule):
-- a rollback un-burns the number.
CREATE TABLE goods_receipt_number_counter (
    org_id   UUID   NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    year     INT    NOT NULL,
    next_val BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (org_id, year)
);

-- The movement's document — the mirror of inventory_log.order_id. Nullable and NOT backfilled:
-- every RESTOCK before V102 was keyed by hand and had no document.
ALTER TABLE inventory_log
    ADD COLUMN goods_receipt_id UUID REFERENCES goods_receipt(id);

COMMENT ON COLUMN inventory_log.goods_receipt_id IS
    'The delivery this RESTOCK came from (V102), or NULL for a hand-keyed one. No new stock_reason: a receipt line is a RESTOCK that knows its document.';

CREATE INDEX inventory_log_receipt_idx ON inventory_log (goods_receipt_id)
    WHERE goods_receipt_id IS NOT NULL;
