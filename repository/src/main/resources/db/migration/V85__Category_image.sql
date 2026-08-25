-- Story: stories/category_image.md — a merchant-uploaded image per category, for the storefront's
-- category circles. Nullable: absent = the storefront's neutral fallback (today's letter circle).
-- The value is an org-scoped object key ({orgId}/category/…, the V53 banner precedent) validated at
-- write time — never a URL; the reads presign it.
ALTER TABLE category ADD COLUMN image_object_key TEXT;
