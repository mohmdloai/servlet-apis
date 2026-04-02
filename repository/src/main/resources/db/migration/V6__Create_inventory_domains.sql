CREATE DOMAIN non_negative_int   AS INT          CHECK (VALUE >= 0);
CREATE DOMAIN non_negative_price AS NUMERIC(12,2) CHECK (VALUE >= 0);
