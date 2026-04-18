-- 1. Org table (the missing FK target)
CREATE TABLE org (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    slug        VARCHAR(64)  NOT NULL UNIQUE,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 2. Wire user_org_role.org_id to org(id) (was a dangling UUID in V12)
ALTER TABLE user_org_role
    ADD CONSTRAINT user_org_role_org_fk FOREIGN KEY (org_id) REFERENCES org(id) ON DELETE CASCADE;

-- 3. product: add org_id, scope sku uniqueness per org
ALTER TABLE product ADD COLUMN org_id UUID NOT NULL REFERENCES org(id);
ALTER TABLE product DROP CONSTRAINT product_sku_unique;
ALTER TABLE product ADD CONSTRAINT product_org_sku_unique UNIQUE (org_id, sku);
CREATE INDEX product_org_idx ON product (org_id);

-- 4. customer: add org_id, scope email uniqueness per org
ALTER TABLE customer ADD COLUMN org_id UUID NOT NULL REFERENCES org(id);
ALTER TABLE customer DROP CONSTRAINT customer_email_unique;
ALTER TABLE customer ADD CONSTRAINT customer_org_email_unique UNIQUE (org_id, email);
CREATE INDEX customer_org_idx ON customer (org_id);

-- 5. inventory: composite PK (org_id, product_id) so the same product row in
--    different orgs has independent stock. Product itself is org-scoped (#3),
--    so this is consistent.
ALTER TABLE inventory DROP CONSTRAINT inventory_pk;
ALTER TABLE inventory ADD COLUMN org_id UUID NOT NULL REFERENCES org(id);
ALTER TABLE inventory ADD CONSTRAINT inventory_pk PRIMARY KEY (org_id, product_id);

-- 6. inventory_log: add org_id + index for per-org audit queries
ALTER TABLE inventory_log ADD COLUMN org_id UUID NOT NULL REFERENCES org(id);
CREATE INDEX inventory_log_org_product_idx ON inventory_log (org_id, product_id);
