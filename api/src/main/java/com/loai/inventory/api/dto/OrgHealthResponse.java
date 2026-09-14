package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.OrgHealth;

/**
 * The org's own operational rollup, returned by {@code GET /api/orgs/{orgId}/health}. Deliberately
 * the <em>same</em> shape as {@code AdminOrgDetailResponse.Health} (both reduce to {@link
 * OrgHealth}) so the tenant dashboard and the platform console read identical numbers and share one
 * frontend entity. Counts only — the disputes/unallocated rows live in {@code GET /payments}. See
 * {@code stories/org_health_rollup.md}.
 */
public record OrgHealthResponse(
    long memberCount,
    long pendingPaymentOrders,
    long openDisputes,
    long unallocatedPayments,
    long claimsToVerify,
    long cardIntentsStuck) {

  public static OrgHealthResponse from(OrgHealth h) {
    return new OrgHealthResponse(
        h.memberCount(),
        h.pendingPaymentOrders(),
        h.openDisputes(),
        h.unallocatedPayments(),
        h.claimsToVerify(),
        h.cardIntentsStuck());
  }
}
