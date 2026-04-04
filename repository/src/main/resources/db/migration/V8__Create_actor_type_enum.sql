CREATE TYPE actor_type AS ENUM (
    'USER',        -- logged-in human via UI
    'SERVICE',     -- internal service-to-service call
    'SYSTEM',      -- scheduled job, background process
    'MIGRATION'    -- one-off data correction script
);
