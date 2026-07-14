package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.StorefrontPage;
import java.time.OffsetDateTime;

/**
 * A public text-page detail for the anonymous storefront ({@code GET
 * /api/public/{orgSlug}/pages/{kind}}, customization epic slice C4). Both locale bodies cross
 * verbatim (newlines intact — the client resolves the §1 fallback and renders them whitespace-
 * preserved and inert); the global ObjectMapper omits null fields, so an unwritten locale drops
 * out. Carries <b>no</b> id/org id — the kind is the identity.
 */
public class PublicPageResponse {

  private String kind;
  private String bodyAr;
  private String bodyEn;
  private OffsetDateTime updatedAt;

  private PublicPageResponse() {}

  public static PublicPageResponse from(StorefrontPage p) {
    PublicPageResponse r = new PublicPageResponse();
    r.kind = p.getKind().wire();
    r.bodyAr = p.getBodyAr();
    r.bodyEn = p.getBodyEn();
    r.updatedAt = p.getUpdatedAt();
    return r;
  }

  public String getKind() {
    return kind;
  }

  public String getBodyAr() {
    return bodyAr;
  }

  public String getBodyEn() {
    return bodyEn;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
