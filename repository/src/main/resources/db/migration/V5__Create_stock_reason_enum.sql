CREATE TYPE stock_reason AS ENUM (
    'RESERVED',      -- stock held during checkout
    'RELEASED',      -- reservation cancelled
    'SOLD',          -- reservation confirmed after payment
    'RESTOCK',       -- manual restock
    'ADJUSTMENT',    -- inventory correction
    'RETURNED'       -- customer return
);