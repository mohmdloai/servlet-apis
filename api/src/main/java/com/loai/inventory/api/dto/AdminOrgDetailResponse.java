package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.OrgHealth;
import com.loai.inventory.service.platform.PlatformOrgService;

/** An org plus its operational rollup, for the platform drill-down view. */
public record AdminOrgDetailResponse(OrgResponse org, Health health) {

  /** The org-scoped aggregates a platform operator triages against. */
  public record Health(
      long memberCount, long pendingPaymentOrders, long openDisputes, long unallocatedPayments) {

    public static Health from(OrgHealth h) {
      return new Health(
          h.memberCount(), h.pendingPaymentOrders(), h.openDisputes(), h.unallocatedPayments());
    }
  }

  public static AdminOrgDetailResponse from(PlatformOrgService.OrgWithHealth v) {
    return new AdminOrgDetailResponse(OrgResponse.from(v.org()), Health.from(v.health()));
  }
}
