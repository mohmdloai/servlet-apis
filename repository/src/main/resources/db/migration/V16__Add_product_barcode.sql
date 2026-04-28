-- Barcode for in-store scanner lookup. Per-org unique; nullable so existing
-- products migrate cleanly and not every SKU has a printed barcode.
ALTER TABLE product ADD COLUMN barcode VARCHAR(64);

CREATE UNIQUE INDEX product_org_barcode_unique
    ON product (org_id, barcode)
    WHERE barcode IS NOT NULL;
