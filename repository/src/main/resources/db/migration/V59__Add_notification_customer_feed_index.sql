-- Slice P5 (customer portal notifications): the customer feed read path.
-- Mirrors idx_notification_recipient_user — the portal feed filters on
-- recipient_customer_id and orders newest-first.
CREATE INDEX idx_notification_recipient_customer
    ON notification (recipient_customer_id, created_at DESC)
    WHERE recipient_type = 'CUSTOMER';
