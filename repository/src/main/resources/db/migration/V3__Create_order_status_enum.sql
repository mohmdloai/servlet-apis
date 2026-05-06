CREATE TYPE order_status AS ENUM (
    'DRAFT',
    'PENDING_PAYMENT',
    'PAID',
    'FULFILLING',
    'FULFILLED',
    'CLOSED',
    'CANCELLED',
    'EXPIRED'
);
