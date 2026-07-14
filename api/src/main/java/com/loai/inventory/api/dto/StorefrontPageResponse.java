package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.StorefrontPage;
import java.time.OffsetDateTime;

/**
 * An admin-plane page row ({@code /api/orgs/{orgId}/storefront/pages}, customization epic slice
 * C4). Serializes the kind, <b>both</b> bodies verbatim (the editor's two textareas), and {@code
 * updated_at} (the "last updated" status line). The global ObjectMapper omits null fields, so an
 * unwritten locale drops out. No {@code id}/{@code org_id} — the kind is the identity.
 */
public class StorefrontPageResponse {

  private String kind;
  private String bodyAr;
  private String bodyEn;
  private OffsetDateTime updatedAt;

  private StorefrontPageResponse() {}

  public static StorefrontPageResponse from(StorefrontPage p) {
    StorefrontPageResponse r = new StorefrontPageResponse();
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
