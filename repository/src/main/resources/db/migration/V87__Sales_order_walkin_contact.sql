-- Capture the walk-in buyer's contact ON THE ORDER (stories/capture_walk_in_customer.md).
--
-- The in-store checkout accepts an optional customer block, but it was only ever honoured when an
-- email was present: resolveCustomer upserts the CRM row on (org_id, email) and returns null
-- otherwise, so a cashier who typed a name and a mobile with no email had both silently dropped.
-- These two columns are where that typed contact now lands — a SNAPSHOT for this one sale, not an
-- identity.
--
-- Deliberately NOT the V80 delivery_* columns, even though they are also a per-order contact
-- snapshot: those are documented as "who receives this parcel" / "the number a courier calls",
-- NULL for IN_STORE by contract, and the self-delivery work will dial delivery_phone. A counter
-- sale has no parcel and nobody to call; a walk-in's mobile must never become a rider's target.
-- One column cannot mean both.
--
-- And deliberately NOT a customer row: customer stays email-keyed (and, since V79, the identity a
-- notification channel dials). A walk-in may give no details at all, so nothing here is required.
--
-- Nullable, and null is meaningful: an email sale is owned by its CRM row (customer_id set, these
-- null); an anonymous sale sets nothing; a name/phone-only walk-in sets these and leaves
-- customer_id null. Existing rows are anonymous exactly as they were.

ALTER TABLE sales_order
    ADD COLUMN customer_name  TEXT,
    ADD COLUMN customer_phone TEXT;

COMMENT ON COLUMN sales_order.customer_name IS
    'Walk-in buyer''s name as typed at the counter, frozen at sale. Only ever set when customer_id IS NULL (an email sale is owned by the CRM row). Not a delivery contact — see delivery_recipient (V80).';
COMMENT ON COLUMN sales_order.customer_phone IS
    'Walk-in buyer''s phone as typed at the counter (Text.normalizeNumeric: Arabic-Indic digits folded, not E.164). Nothing dials it; no phone_e164 twin. Only ever set when customer_id IS NULL.';

-- No index and no backfill. Nothing queries orders by walk-in name (reads are order-id-first and
-- customer search has no row to find), and there is nothing to backfill from — the dropped details
-- were never stored anywhere.
