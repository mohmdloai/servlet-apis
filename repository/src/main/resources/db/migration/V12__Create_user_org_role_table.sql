CREATE TYPE org_role AS ENUM ('OWNER', 'MANAGER', 'STAFF', 'VIEWER');

CREATE TABLE user_org_role (
    user_id  UUID     NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    org_id   UUID     NOT NULL,
    role     org_role NOT NULL,
    PRIMARY KEY (user_id, org_id, role)
);
