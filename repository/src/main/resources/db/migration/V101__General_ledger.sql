-- The general ledger (stories/general_ledger.md) — sys-analysis/system/accounting-future.md's
-- trigger #1 ("an org wants in-app financial statements") fired; this is the "rough shape" that
-- document sketched, built as a DERIVED-THEN-POSTED ledger:
--
--   * Nothing here is typed by a person. Every journal entry is derived from a row the domain
--     already writes (an issued invoice, a verified receipt, an executed refund, an inventory_log
--     row, a cash movement, a closed shift) by one idempotent poster
--     (LedgerRepositoryImpl.post*), keyed UNIQUE on (org, source_type, source_id, event) so a
--     replay, a catch-up on read and a full rebuild are the same statement.
--   * No money write site changes. The invariants the domain already enforces
--     (paid_amount <= grand_total, refunded <= paid, reservations <= on hand) stay the truth;
--     the ledger is a second, independent reading of the same rows — and LedgerRepositoryImpl
--     .health() compares the two, which is how a posting defect shows up as a number, not a lie.
--   * A deferred constraint trigger refuses to COMMIT an entry whose debits != credits. The poster
--     builds entries from balanced templates; the trigger is the backstop no code path can skip.
--
-- accounting-future.md said "don't add a direction field meant to be a debit/credit flag" —
-- payment_transaction.direction stays money-in/money-out at the provider; the accounting side is
-- journal_line.side, a different column on a different table, as that note intended.

-- Chart of accounts: one row per (org, code), materialised lazily from the standard chart in
-- service code (LedgerChart) the first time an org's ledger is touched — no org-creation change.
CREATE TABLE ledger_account (
    id          UUID        PRIMARY KEY,
    org_id      UUID        NOT NULL REFERENCES org(id),
    code        TEXT        NOT NULL CHECK (code ~ '^[0-9]{4}$'),
    name        TEXT        NOT NULL,
    type        TEXT        NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    -- The side a positive balance sits on: DR for assets/expenses, CR for liabilities/equity/revenue.
    -- Contra accounts (sales discounts, sales returns) are REVENUE with normal_side DR.
    normal_side CHAR(2)     NOT NULL CHECK (normal_side IN ('DR', 'CR')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (org_id, code)
);

COMMENT ON TABLE ledger_account IS
    'Per-org chart of accounts (stories/general_ledger.md). Codes are the standard chart in LedgerChart; rows appear when the poster first needs them.';

CREATE TABLE journal_entry (
    id          UUID        PRIMARY KEY,
    org_id      UUID        NOT NULL REFERENCES org(id),
    -- Per-org running number for the journal view (JE-000123). Assigned by the poster under the
    -- org's advisory lock; the UNIQUE below is the guard, not the allocator.
    entry_no    BIGINT      NOT NULL,
    -- The BUSINESS instant of the event (issued_at, occurred_at, executed_at, ...), never the
    -- moment the poster ran — a backfill dates history correctly.
    posted_at   TIMESTAMPTZ NOT NULL,
    source_type TEXT        NOT NULL CHECK (source_type IN (
                    'INVOICE', 'CREDIT_NOTE', 'RECEIPT', 'REFUND', 'ALLOCATION',
                    'STOCK', 'CASH_MOVEMENT', 'CASH_SHIFT')),
    -- The source row's key as text (UUIDs for documents, the BIGSERIAL id for inventory_log).
    source_id   TEXT        NOT NULL,
    event       TEXT        NOT NULL,
    memo        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The idempotency key: one entry per business event, however many times the poster runs.
    UNIQUE (org_id, source_type, source_id, event),
    UNIQUE (org_id, entry_no)
);

COMMENT ON TABLE journal_entry IS
    'One balanced double-entry posting derived from one domain event. (org_id, source_type, source_id, event) is the idempotency key that makes catch-up, replay and rebuild the same statement.';
COMMENT ON COLUMN journal_entry.posted_at IS
    'The business instant of the source event (issued_at / occurred_at / executed_at / created_at / recorded_at / closed_at), not when the poster ran.';

CREATE INDEX journal_entry_org_posted_idx ON journal_entry (org_id, posted_at, entry_no);

CREATE TABLE journal_line (
    id          BIGSERIAL     PRIMARY KEY,
    entry_id    UUID          NOT NULL REFERENCES journal_entry(id) ON DELETE CASCADE,
    org_id      UUID          NOT NULL REFERENCES org(id),
    account_id  UUID          NOT NULL REFERENCES ledger_account(id),
    -- The entry's posted_at, copied onto the leg so the trial balance and a statement's window
    -- are one index range per account and never a join back to the entry (perfdb: 840 ms cold /
    -- 36 ms warm through the join over 27k legs, vs a single range aggregate — see
    -- tools/seed/results/general_ledger_196.txt).
    posted_at   TIMESTAMPTZ   NOT NULL,
    seq         SMALLINT      NOT NULL,
    side        CHAR(2)       NOT NULL CHECK (side IN ('DR', 'CR')),
    amount      NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    UNIQUE (entry_id, seq)
);

COMMENT ON TABLE journal_line IS
    'A debit or credit of one account inside one journal_entry. Zero-amount legs are never written; a template leg whose amount is 0 (no tax, no discount) is simply absent. posted_at duplicates the entry''s for the per-account reads.';

-- The trial balance (every account, opening + window movement) and the account statement are
-- range scans of this index; the journal read goes through journal_entry_org_posted_idx.
CREATE INDEX journal_line_org_account_posted_idx ON journal_line (org_id, account_id, posted_at);

-- Rows the poster looked at and could not post honestly — a stock move with no cost to post at,
-- an invoice whose totals do not add up, a receipt on a rail the chart does not know. Without
-- this table such a row sits in the poster's delta forever and is re-examined (re-costed, on the
-- STOCK template) on every read: perfdb's largest org has 24 064 uncosted stock rows and paid
-- 342 ms per catch-up for them. Recorded once, under the entry's own key shape, and excluded from
-- the delta like a posted entry; the health read counts them per reason as coverage. A reset
-- rebuild clears them, which is how a newly known rail or a repaired row gets re-judged.
CREATE TABLE ledger_skip (
    org_id      UUID        NOT NULL REFERENCES org(id),
    source_type TEXT        NOT NULL,
    source_id   TEXT        NOT NULL,
    event       TEXT        NOT NULL,
    reason      TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, source_type, source_id, event)
);

COMMENT ON TABLE ledger_skip IS
    'Source rows the ledger poster judged unpostable (UNCOSTED, TOTALS_MISMATCH, UNKNOWN_RAIL), so they leave the delta; counted on /ledger/health, cleared by a reset rebuild.';

-- The balance guard. Row-level and DEFERRED so a multi-leg entry is judged once its legs are all
-- in, at COMMIT; an entry left unbalanced by any path (poster defect, a hand edit) rolls the
-- transaction back instead of landing. A lone leg (count < 2) is unbalanced by definition. An
-- entry whose row is gone (a reset cascading its lines away) has nothing to balance.
CREATE OR REPLACE FUNCTION journal_entry_must_balance() RETURNS trigger AS $fn$
DECLARE
    v_entry UUID := COALESCE(NEW.entry_id, OLD.entry_id);
    v_net   NUMERIC(14,2);
    v_legs  INT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM journal_entry WHERE id = v_entry) THEN
        RETURN NULL;
    END IF;
    SELECT COALESCE(SUM(CASE side WHEN 'DR' THEN amount ELSE -amount END), 0), COUNT(*)
      INTO v_net, v_legs
      FROM journal_line
     WHERE entry_id = v_entry;
    IF v_net <> 0 OR v_legs < 2 THEN
        RAISE EXCEPTION 'journal_entry % is unbalanced (net % over % legs)', v_entry, v_net, v_legs
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$fn$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER journal_line_balanced
    AFTER INSERT OR UPDATE OR DELETE ON journal_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION journal_entry_must_balance();

-- The one source table with no per-org index. Every ledger read anti-joins each source table by
-- org; payment_allocation (V24) is indexed by invoice and by payment only, so the allocation
-- check was a parallel seq scan over the whole table — 61 ms warm on perfdb's 696 569 rows for
-- ANY org, however small. With the index: 11.7 ms (bitmap on the org's 3 562 rows). Measured
-- before/after in tools/seed/results/general_ledger_196.txt.
CREATE INDEX payment_allocation_org_idx ON payment_allocation (org_id);

-- What a unit cost the moment stock moved, for the movements that have no order line to cost
-- from (a restock, an adjustment, a stocktake variance). Stamped by InventoryLogRepositoryImpl
-- from product.cost_price at insert — the sales_order_line.unit_cost rule (V92) applied one table
-- over: a cost edit tomorrow does not re-value last month's shrinkage. Order-linked moves (SOLD,
-- RETURNED, RESTOCKED_FAILED_FULFILLMENT) are costed by the poster from the order line instead,
-- so COGS agrees with /reports/profit to the piastre. EXISTING ROWS STAY NULL (V92's rule): the
-- poster skips an uncosted move and health() counts it, never prices it at zero.
ALTER TABLE inventory_log
    ADD COLUMN unit_cost NUMERIC(14,2)
        CONSTRAINT ck_inventory_log_unit_cost_non_negative CHECK (unit_cost IS NULL OR unit_cost >= 0);

COMMENT ON COLUMN inventory_log.unit_cost IS
    'product.cost_price at the moment of the move (stories/general_ledger.md); NULL when the product was uncosted and for every row before V101. Order-linked moves are costed from sales_order_line.unit_cost by the ledger poster instead.';
