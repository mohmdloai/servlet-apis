package com.loai.inventory.domain.model;

/**
 * The tenant lifecycle funnel's acquisition-path filter ({@code ?path=} on {@code GET
 * /api/admin/funnel}, slice 7 of the platform console). <strong>Required, not defaulted</strong> by
 * the client that matters: a provisioned tenant is born {@link PlatformFunnelStage#ACTIVATED} (no
 * verification step), so pooling both paths under {@link #ALL} reports a stage-2 conversion
 * inflated by tenants that stage never applied to. The two paths are distinguished post hoc by
 * whether the org has an {@code ORG_CREATE} {@code platform_audit} row (provisioning writes one;
 * self-serve registration writes none — slice 4's finding, direct since V76 added {@code
 * platform_audit.org_id}).
 */
public enum PlatformFunnelPath {
  ALL,
  SELF_SERVE,
  PROVISIONED
}
