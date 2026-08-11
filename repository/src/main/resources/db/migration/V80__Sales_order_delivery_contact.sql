-- Snapshot the per-order delivery contact ON THE ORDER, where it belongs.
--
-- This is the column set docs/notifications-plan.md §0 always said would land here: "Shipping/
-- billing address is not modelled anywhere yet; when it lands it snapshots here." Until now it did
-- not exist, so the delivery contact was laundered through the CUSTOMER row: placement upserted the
-- typed recipient/phone/address onto `customer`, and InvoiceService read it back off that row when
-- freezing the invoice's contact block.
--
-- That was survivable while `customer.phone` was an inert CRM field. V79 made it the identity a
-- notification channel dials, and the two roles are incompatible: a logged-in shopper sending a gift
-- to their mother had their OWN name and phone overwritten with hers, so every subsequent order
-- update went to a third party and the buyer heard nothing. One field cannot be both "who you are"
-- and "where this parcel goes".
--
-- Nullable, and null is meaningful: an IN_STORE sale has no delivery contact at all, and orders
-- placed before this migration have none recorded (InvoiceService falls back to the customer row for
-- exactly those, so no existing invoice changes).
--
-- Deliberately three plain TEXT columns rather than a foreign key to `customer_address`: this is a
-- SNAPSHOT, frozen at placement, and it must not change when the shopper later edits or deletes the
-- address book entry it was copied from — the same reason sales_invoice freezes its own contact
-- block (V21) and sales_order_line freezes unit_price (V18).

ALTER TABLE sales_order
    ADD COLUMN delivery_recipient TEXT,
    ADD COLUMN delivery_phone     TEXT,
    ADD COLUMN delivery_address   TEXT;

COMMENT ON COLUMN sales_order.delivery_recipient IS
    'Who receives this parcel, frozen at placement. May differ from the buying customer (a gift). NULL for IN_STORE and pre-V80 orders.';
COMMENT ON COLUMN sales_order.delivery_phone IS
    'The number a courier calls for THIS order, frozen at placement. Deliberately not customer.phone, which is the buyer''s own messaging identity (V79).';
COMMENT ON COLUMN sales_order.delivery_address IS
    'Where this parcel goes, frozen at placement — never a reference to customer_address, which the shopper may later edit or delete.';

-- No index and no backfill. Nothing queries orders BY delivery contact (the reads are always
-- order-id-first), and there is nothing to backfill from: the pre-V80 delivery contact was merged
-- destructively into the customer row, so any value copied back now would be a guess about which of
-- that customer's orders it belonged to. The fallback in InvoiceService is the honest answer for
-- those rows.
