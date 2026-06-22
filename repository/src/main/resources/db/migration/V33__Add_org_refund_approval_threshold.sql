-- Per-org boundary above which returning money requires an OWNER (not just MANAGER): CreditNote
-- issuance and direct-from-Payment refund creation above this amount escalate to OWNER. Configurable
-- by the org's OWNER via PUT /api/orgs/{orgId}. Default 500 EGP per sys-analysis/outbound/refund.md.
ALTER TABLE org
    ADD COLUMN refund_approval_threshold NUMERIC(14,2) NOT NULL DEFAULT 500
        CHECK (refund_approval_threshold >= 0);
