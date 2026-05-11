CREATE TYPE fulfillment_status AS ENUM (
    'PENDING',
    'SHIPPED',
    'DELIVERED',
    'CANCELLED',
    'FAILED'
);

CREATE TABLE fulfillment (
  id              UUID               PRIMARY KEY,
  org_id          UUID               NOT NULL REFERENCES org(id),
  sales_order_id  UUID               NOT NULL REFERENCES sales_order(id),

  status          fulfillment_status NOT NULL,

  carrier         TEXT,   -- e.g. 'Bosta', 'Aramex', 'self-delivery'
  tracking_number TEXT,
  shipped_at      TIMESTAMPTZ,
  delivered_at    TIMESTAMPTZ,
  cancelled_at    TIMESTAMPTZ,
  failed_at       TIMESTAMPTZ,
  failed_reason   TEXT,
  notes           TEXT,

  created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_fulfillment_pending ON fulfillment (org_id, created_at)
  WHERE status = 'PENDING';

-- LINE:
CREATE TABLE fulfillment_line (
  id                  UUID           PRIMARY KEY,
  fulfillment_id      UUID           NOT NULL REFERENCES fulfillment(id) ON DELETE CASCADE,
  sales_order_line_id UUID           NOT NULL REFERENCES sales_order_line(id),
  quantity            integer        NOT NULL CHECK (quantity > 0),

  -- Linked back to the reservation row that this fulfillment line consumed.
  -- Allows reservation → fulfillment traceability without recomputation.
  inventory_reservation_id UUID      REFERENCES inventory_reservation(id)
);

-- Invariant (enforced in app code):
-- For each sales_order_line:
--   SUM(fulfillment_line.quantity for non-CANCELLED fulfillments) <= sales_order_line.quantity