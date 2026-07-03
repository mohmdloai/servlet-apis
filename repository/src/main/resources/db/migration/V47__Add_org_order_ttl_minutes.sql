-- Per-org payment-hold window for reserved online/phone orders: placement stamps
-- expires_at = placed_at + order_ttl_minutes, after which the order-TTL sweeper expires the
-- order and releases its reservations. Implements the "Configurable per-org" half of
-- sys-analysis/outbound/reservation.md §Default TTL (the 24h default shipped hardcoded).
-- Configurable by the org's OWNER via PUT /api/orgs/{orgId}. Minutes (not hours) because the
-- future PSP flow needs sub-hour windows and flash-sale orgs want sub-day granularity.
-- Bounds keep a typo from creating insta-expiring (< 15 min) or effectively immortal
-- (> 30 days = 43200 min) orders.
ALTER TABLE org
    ADD COLUMN order_ttl_minutes INTEGER NOT NULL DEFAULT 1440
        CHECK (order_ttl_minutes BETWEEN 15 AND 43200);
