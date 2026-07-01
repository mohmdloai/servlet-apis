package com.loai.inventory.domain.model;

/**
 * Cross-org operational rollup for one org, surfaced by the platform read console (see {@code
 * docs/platform-admin-plan.md}, slice 2). Every field is an org-scoped aggregate a platform
 * operator wants at a glance when triaging a tenant.
 *
 * @param memberCount distinct users holding any role in the org
 * @param pendingPaymentOrders sales orders awaiting payment (status {@code PENDING_PAYMENT})
 * @param openDisputes payments currently in dispute (status {@code DISPUTED})
 * @param unallocatedPayments payments still carrying an unallocated balance
 */
public record OrgHealth(
    long memberCount, long pendingPaymentOrders, long openDisputes, long unallocatedPayments) {}
