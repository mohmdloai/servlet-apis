package com.loai.inventory.api.dto;

import com.loai.inventory.service.StorefrontPageService.PublicPageView;
import java.time.OffsetDateTime;

/**
 * A public text-page detail for the anonymous storefront ({@code GET
 * /api/public/{orgSlug}/pages/{kind}}, customization epic slice C4). {@code body} is resolved
 * server-side to a <b>single</b> value by {@code ?locale=} (slice L4 — reverses the shipped
 * both-bodies-client-resolve; newlines intact, rendered whitespace-preserved and inert). Carries
 * <b>no</b> id/org id — the kind is the identity.
 */
public class PublicPageResponse {

  private String kind;
  private String body;
  private OffsetDateTime updatedAt;

  private PublicPageResponse() {}

  public static PublicPageResponse from(PublicPageView p) {
    PublicPageResponse r = new PublicPageResponse();
    r.kind = p.kind();
    r.body = p.body();
    r.updatedAt = p.updatedAt();
    return r;
  }

  public String getKind() {
    return kind;
  }

  public String getBody() {
    return body;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
