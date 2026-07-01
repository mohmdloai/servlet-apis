package com.loai.inventory.api.dto;

import com.loai.inventory.service.platform.PlatformOrgService;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One row of the platform cross-org list: identity, status, and distinct member count. */
public record AdminOrgSummaryResponse(
    UUID id, String name, String slug, boolean active, OffsetDateTime createdAt, long memberCount) {

  public static AdminOrgSummaryResponse from(PlatformOrgService.OrgListItem item) {
    return new AdminOrgSummaryResponse(
        item.org().getId(),
        item.org().getName(),
        item.org().getSlug(),
        item.org().isActive(),
        item.org().getCreatedAt(),
        item.memberCount());
  }
}
