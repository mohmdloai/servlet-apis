package com.loai.inventory.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.service.platform.PlatformOrgTimelineService;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The {@code GET /api/admin/orgs/{orgId}/timeline} envelope: a {@link PageResponse} plus the
 * tenant's own {@code created_at}.
 *
 * <p><strong>The org's birth is an anchor, not an entry.</strong> It sits on the envelope instead
 * of being synthesized into the stream because self-serve registration writes <em>no audit row at
 * all</em> — {@code AccountService} never calls {@code PlatformAuditService} — so most tenants have
 * no {@code ORG_CREATE} and the stream's oldest entry is routinely not the beginning. A synthesized
 * first row would need an actor that does not exist. The client renders it as the list's terminal
 * cap ("Tenant registered · {date}"), which is true for every org, provisioned or self-serve.
 */
public class AdminOrgTimelineResponse extends PageResponse<AdminOrgTimelineEntryResponse> {

  private final OffsetDateTime createdAt;

  private AdminOrgTimelineResponse(
      List<AdminOrgTimelineEntryResponse> data,
      long total,
      int page,
      int size,
      OffsetDateTime createdAt) {
    super(data, total, page, size);
    this.createdAt = createdAt;
  }

  public static AdminOrgTimelineResponse from(
      PlatformOrgTimelineService.TimelinePage page, ObjectMapper mapper) {
    return new AdminOrgTimelineResponse(
        page.entries().stream().map(e -> AdminOrgTimelineEntryResponse.from(e, mapper)).toList(),
        page.total(),
        page.page(),
        page.size(),
        page.orgCreatedAt());
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
