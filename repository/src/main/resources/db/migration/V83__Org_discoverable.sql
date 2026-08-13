-- The merchant opt-out from search discovery (stories/storefront_crawl_feeds.md).
--
-- `active` answers "can this store transact?"; `discoverable` answers the different question
-- "does this store want to be FOUND?". Platforms always have a window where a merchant is live
-- but not ready for organic traffic — still setting up branding, inventory, pricing — and some
-- (B2B/wholesale, franchise agreements) never want organic search at all. Opt-out, not opt-in:
-- an existing store's crawl presence must not vanish on migration day, so the default is TRUE.
--
-- Enforced in three places, all reading this one column: the public store index
-- (GET /api/public/storefronts) omits the org, its crawl feed 404s (so its sitemap ceases to
-- exist), and its public profile carries the flag so the storefront app noindexes every page —
-- the third being what stops an externally-linked hidden store from entering the index anyway.
-- The store itself stays fully reachable by direct link.
ALTER TABLE org
    ADD COLUMN discoverable BOOLEAN NOT NULL DEFAULT TRUE;
