-- Catalog: hierarchical product categories (e.g. "Notebooks > Spiral"). Org-scoped.
-- Same-org parent and acyclicity are enforced in CategoryService — a self-FK cannot express
-- "parent must be in the same org" nor "no cycles".
CREATE TABLE category (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id             UUID         NOT NULL REFERENCES org(id),
    parent_category_id UUID         REFERENCES category(id) ON DELETE RESTRICT,
    name               VARCHAR(255) NOT NULL,
    slug               VARCHAR(255) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT category_org_slug_unique UNIQUE (org_id, slug)
);

CREATE INDEX category_org_idx    ON category (org_id);
CREATE INDEX category_parent_idx ON category (parent_category_id);
