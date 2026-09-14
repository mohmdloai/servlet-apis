-- Slice 3 of the Paymob card epic: card payments that survive a dropped webhook.
-- See stories/paymob_card_reliability.md and docs/paymob-card-epic.md.
--
-- The story planned no migration. The sandbox said otherwise: Paymob's transaction-inquiry
-- endpoint (POST /api/ecommerce/orders/transaction_inquiry) authenticates only with the auth
-- token minted from the account's legacy API key (POST /api/auth/tokens) — the secret key that
-- mints intentions is refused there ("Authentication credentials were not provided", probed
-- 2026-09-14). So the poller needs a fifth per-merchant credential, sealed like the other two.
ALTER TABLE org_paymob_config ADD COLUMN api_key_encrypted TEXT;

COMMENT ON COLUMN org_paymob_config.api_key_encrypted IS
    'AES-GCM ciphertext under PAYMOB_CREDENTIAL_KEY of the account''s legacy API key — exchanged for a short-lived auth token by the inquiry poller only. Never returned by any read path. NULL on rows connected before V100: such an org cannot be inquired about and its stuck intents can only expire (status carries inquiry_enabled:false).';
