package com.loai.inventory.domain.model;

/**
 * Cross-org tenant census for the platform overview: how many client orgs exist, how many are live,
 * how many an ADMIN suspended, how many are awaiting email verification, and how many were
 * provisioned in the trailing 7 days.
 *
 * <p>{@code active + suspended + pending == total} by construction — the three come from {@link
 * OrgStatus}, which partitions the table, and the platform org list's {@code
 * ?status=active|pending|suspended} filter consumes the very same constants. So a tile and the list
 * it drills into cannot disagree.
 *
 * <p>{@code pending} is a tenant born inactive at self-serve registration whose owner has not
 * clicked the verification link — the ordinary case, not an alarm. It used to be folded into {@code
 * suspended}; see {@code stories/platform_tenant_states.md}.
 */
public record PlatformTenantCounts(
    long total, long active, long suspended, long pending, long provisionedLast7d) {}
