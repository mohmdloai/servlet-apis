package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.OrgStatus;
import com.loai.inventory.service.platform.PlatformOrgService;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the platform cross-org list: identity, status, and distinct member count.
 *
 * <p><b>The server names the state.</b> This row carries {@code status} ({@code active} / {@code
 * pending} / {@code suspended}) and deliberately <em>not</em> the {@code active} boolean it
 * replaced: a client deriving a label from that boolean is a second copy of a rule that has already
 * proved it can be wrong, and two values cannot express three states anyway. There is no
 * compatibility field — see {@code stories/platform_tenant_states.md}.
 */
public record AdminOrgSummaryResponse(
    UUID id, String name, String slug, String status, OffsetDateTime createdAt, long memberCount) {

  public static AdminOrgSummaryResponse from(PlatformOrgService.OrgListItem item) {
    return new AdminOrgSummaryResponse(
        item.org().getId(),
        item.org().getName(),
        item.org().getSlug(),
        OrgStatus.of(item.org()).wire(),
        item.org().getCreatedAt(),
        item.memberCount());
  }
}
