-- C3: featured listings (customization epic slice C3). Adds the smallest true merchandising
-- primitive — an ordered, org-scoped pinned list of listings the merchant chooses to lead with on
-- the storefront home ("Featured" strip). Rather than a collection table (many named lists —
-- premature, no second list exists yet), the "featured list" is one nullable column on
-- product_listing: featured_sort (NULL = not featured; ascending = display order). The admin edits
-- it as one atomic set-replace (the PUT …/categories precedent), and the public read composes it
-- with the whole B3 predicate/sort machinery for free — one more optional predicate, no new path.
--
-- The public row shape is UNCHANGED: featured_sort never leaves the repository (the whitelisted
-- StorefrontService.ListingView carries no such field, IT-asserted) — the *list* is the feature, not
-- a per-row flag. PUBLISHED-only serving stays hard-coded in the read, so a merchant may stage a
-- DRAFT for launch (storable) yet it never shows publicly until published (epic honesty rule).
-- See stories/storefront_featured_listings.md (C3) and frontst/docs/storefront-customization-epic.md.
ALTER TABLE product_listing
    ADD COLUMN featured_sort INT;

-- The admin ordered read and the public ?featured=true predicate both ride this partial index; it
-- stays small (only the ≤ 12 curated rows per org, cap service-enforced) and skips the vast
-- non-featured majority.
CREATE INDEX product_listing_org_featured_idx
    ON product_listing (org_id, featured_sort)
    WHERE featured_sort IS NOT NULL;
