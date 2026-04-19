CREATE TABLE app_user (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255)    NOT NULL,
    password_hash   VARCHAR(255)    NOT NULL,
    actor_type      actor_type      NOT NULL,
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    token_version   INTEGER         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT app_user_email_unique UNIQUE (email)
);