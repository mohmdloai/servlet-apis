package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.StorefrontPage;
import java.time.OffsetDateTime;

/**
 * A public page-list entry ({@code GET /api/public/{orgSlug}/pages}, customization epic slice C4) —
 * the footer's link source. Carries only {@code kind} + {@code updated_at} (no body — the slower
 * cache window and the list/detail split exist so the footer renders links without fetching
 * bodies).
 */
public class PublicPageSummaryResponse {

  private String kind;
  private OffsetDateTime updatedAt;

  private PublicPageSummaryResponse() {}

  public static PublicPageSummaryResponse from(StorefrontPage p) {
    PublicPageSummaryResponse r = new PublicPageSummaryResponse();
    r.kind = p.getKind().wire();
    r.updatedAt = p.getUpdatedAt();
    return r;
  }

  public String getKind() {
    return kind;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
