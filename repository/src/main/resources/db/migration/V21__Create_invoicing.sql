CREATE TYPE invoice_status AS ENUM (
    'DRAFT',
    'ISSUED',
    'PAID',
    'VOID'
);

CREATE TABLE sales_invoice (
  id                  UUID           PRIMARY KEY,
  org_id              UUID           NOT NULL REFERENCES org(id),
  customer_id         UUID           REFERENCES customer(id),
  sales_order_id      UUID           REFERENCES sales_order(id),  -- nullable for pre-billing without an order; rare
  fulfillment_id      UUID           NOT NULL UNIQUE REFERENCES fulfillment(id),
  invoice_number      TEXT           NOT NULL,    -- gapless per-org per-year, e.g. INV-2026-0001
  status              invoice_status NOT NULL,

  -- Frozen totals (snapshot at issuance)
  subtotal            NUMERIC(14,2)  NOT NULL,
  tax_total           NUMERIC(14,2)  NOT NULL DEFAULT 0,
  discount_total      NUMERIC(14,2)  NOT NULL DEFAULT 0,
  grand_total         NUMERIC(14,2)  NOT NULL,

  currency            char(3)        NOT NULL DEFAULT 'EGP',

  -- Frozen customer data (in case the customer record changes later)
  customer_name       TEXT           NOT NULL,
  customer_email      TEXT,
  customer_phone      TEXT,
  customer_address    TEXT,


  -- Cached payment status
  paid_amount         NUMERIC(14,2)  NOT NULL DEFAULT 0,  -- = SUM(payment_allocation.amount)

  issued_at           timestamptz,                        -- NULL while DRAFT
  voided_at           timestamptz,
  void_reason         TEXT,

  created_at          timestamptz    NOT NULL DEFAULT now(),
  updated_at          timestamptz    NOT NULL DEFAULT now(),

  UNIQUE (org_id, invoice_number)
);

CREATE INDEX idx_invoice_unpaid ON sales_invoice (org_id, issued_at)
  WHERE status = 'ISSUED' AND paid_amount < grand_total;

CREATE TABLE sales_invoice_line (
  id              UUID           PRIMARY KEY,
  sales_invoice_id UUID          NOT NULL REFERENCES sales_invoice(id) ON DELETE CASCADE,
  product_id      UUID           REFERENCES product(id),          -- nullable for non-product lines (shipping, fees)
  description     TEXT           NOT NULL,                        -- snapshotted; survives product rename/delete
  quantity        integer        NOT NULL CHECK (quantity > 0),
  unit_price      NUMERIC(14,2)  NOT NULL,
  tax_rate        NUMERIC(6,4)   NOT NULL DEFAULT 0,
  line_subtotal   NUMERIC(14,2)  NOT NULL,
  line_tax        NUMERIC(14,2)  NOT NULL DEFAULT 0,
  line_total      NUMERIC(14,2)  NOT NULL
);