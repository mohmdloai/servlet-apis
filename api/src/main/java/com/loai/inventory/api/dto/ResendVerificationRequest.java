package com.loai.inventory.api.dto;

/**
 * Body for {@code POST /api/admin/users/{id}/resend-verification}. Entirely optional — an absent
 * body and {@code {}} are the same request.
 *
 * <p>{@code orgId} is the tenant this rescue is for, and it is what puts the action on that
 * tenant's timeline (V76's {@code platform_audit.org_id}). Send it when the operator acted from an
 * org's page; omit it when they acted from the user directory and named no tenant. It is validated
 * against the user's actual OWNER role rather than trusted, because an audit column you can point
 * anywhere is worse than a null one.
 */
public class ResendVerificationRequest {
  private String orgId;

  public String getOrgId() {
    return orgId;
  }

  public void setOrgId(String orgId) {
    this.orgId = orgId;
  }
}
