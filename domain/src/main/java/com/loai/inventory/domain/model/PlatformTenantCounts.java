package com.loai.inventory.domain.model;

/**
 * Cross-org tenant census for the platform overview: how many client orgs exist, how many are live,
 * how many are suspended, and how many were provisioned in the trailing 7 days.
 *
 * <p>{@code active + suspended == total} by construction — {@code org.active} is a boolean, and the
 * platform org list's {@code ?status=active|suspended} filter reduces to the same flag.
 */
public record PlatformTenantCounts(
    long total, long active, long suspended, long provisionedLast7d) {}
