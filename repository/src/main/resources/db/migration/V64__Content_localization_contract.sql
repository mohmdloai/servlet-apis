-- Content-localization CONTRACT (epic slice L6). L2-L4 cut every read and write over to the
-- per-language *_translation tables and dual-wrote the legacy/paired columns for one release; those
-- columns are now dead. Drop them so *_translation is the single source of truth for all four
-- customer-facing entities. This is the destructive, irreversible-cheap step (its own migration, its
-- own release): a revert means restoring the columns and re-backfilling from *_translation (the
-- inverse of V63's backfill), so it ships only after L2-L5 have been stable in production.
--
-- Untouched by design: the four *_translation tables, their generated *_search columns + trigram GIN
-- indexes, and product.name_search + its ICU/collation (product stays single-column per the epic's
-- scope decision, so its V62 search is still live and correct).

-- product_listing: title/marketing_copy → product_listing_translation.(title, marketing_copy).
-- product_listing never carried a generated *_search or ICU treatment (title_search lives on the
-- translation table), so the plain columns drop cleanly.
ALTER TABLE product_listing DROP COLUMN title;
ALTER TABLE product_listing DROP COLUMN marketing_copy;

-- category.name → category_translation.name. The column carried V62's `text COLLATE "und-x-icu"`
-- treatment; dropping the column drops that collated column with it (the per-language ICU columns
-- now live on category_translation.name). Category never got a generated name_search to drop.
ALTER TABLE category DROP COLUMN name;

-- storefront_banner paired headline/subheading → storefront_banner_translation.(headline, subheading),
-- one row per language. All four are plain TEXT with no constraint referencing them.
ALTER TABLE storefront_banner
    DROP COLUMN headline_ar,
    DROP COLUMN headline_en,
    DROP COLUMN subheading_ar,
    DROP COLUMN subheading_en;

-- storefront_page paired body → storefront_page_translation.body, one row per language.
ALTER TABLE storefront_page
    DROP COLUMN body_ar,
    DROP COLUMN body_en;
