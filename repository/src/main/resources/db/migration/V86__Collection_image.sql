-- Story: stories/collection_image.md — a merchant-uploaded image per collection, for the
-- storefront's collections rail tiles. Nullable: absent = a text-only tile (today's chip). The value
-- is an org-scoped object key ({orgId}/collection/…, the V53 banner / V85 category precedent)
-- validated at write time — never a URL; the reads presign it.
ALTER TABLE collection ADD COLUMN image_object_key TEXT;
