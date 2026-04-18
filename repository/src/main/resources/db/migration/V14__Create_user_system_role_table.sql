CREATE TYPE system_role AS ENUM ('ADMIN', 'SUPPORT');

CREATE TABLE user_system_role (
    user_id  UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role     system_role NOT NULL,
    PRIMARY KEY (user_id, role)
);
