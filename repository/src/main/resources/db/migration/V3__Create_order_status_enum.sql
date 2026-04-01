CREATE TYPE order_status AS ENUM (
    'PENDING',
    'CONFIRMED',
    'PAID',
    'SHIPPED',
    'CANCELLED',
    'FAILED'
);