-- The language to write to this customer in — slice L of the notification-reach epic
-- (stories/localized_notification_templates.md).
--
-- Every notification title/body is rendered server-side and STORED on the notification row at
-- produce time, so the language has to be decided when the event fires, from something durable on
-- the recipient. Until now there was nothing: the storefront knew the shopper's locale at checkout
-- (L2b even snapshots the order-line title in it) and then discarded it, so an Arabic-speaking
-- shopper got an Arabic UI wrapped around an English sentence.
--
-- NULLABLE, and null is a real answer: "we have never learned it", which resolves to
-- org.default_locale (V52, NOT NULL, CHECK IN ('ar','en')). That fallback is why this column does
-- not need a default of its own — 'ar' here would claim knowledge we do not have, and would be
-- wrong for an English-default store.
--
-- Same CHECK as org.default_locale rather than a free-text column: these two are compared and
-- swapped for one another constantly, and a locale set that can drift between them is a bug
-- waiting for its first non-'ar'/'en' language.

ALTER TABLE customer
    ADD COLUMN locale VARCHAR(5) CHECK (locale IN ('ar', 'en'));

COMMENT ON COLUMN customer.locale IS
    'Preferred content language for this customer. NULL = never learned, falls back to org.default_locale. Learned at anonymous checkout, filled once at portal checkout if absent, and settable by the customer at PATCH /api/portal/me.';

-- No index and no backfill. Nothing queries customers BY locale, and there is nothing to backfill
-- from: the checkout locale was never persisted, and inferring a language from a name or an address
-- would be a guess about a person. NULL correctly says "ask the org default".
