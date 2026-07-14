package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A merchant-managed storefront text page (customization epic slice C4). A fixed {@link PageKind}
 * ({@code about}/{@code policies}) with bilingual paired-column plain-text bodies ({@code
 * body_ar}/{@code body_en}, epic §1 — the default-locale one required, service-enforced) and its
 * own {@code updated_at} (the storefront's "last updated" line). Carries <b>no</b> title — the
 * title is a fixed, frontend-localized key per kind (always bilingual even when one body is
 * written); and <b>no</b> markup — bodies are stored verbatim and rendered inert (epic §Out). See
 * {@code stories/storefront_pages.md}.
 */
public class StorefrontPage {
  private UUID id;
  private UUID orgId;
  private PageKind kind;
  private String bodyAr;
  private String bodyEn;
  private OffsetDateTime updatedAt;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public void setOrgId(UUID orgId) {
    this.orgId = orgId;
  }

  public PageKind getKind() {
    return kind;
  }

  public void setKind(PageKind kind) {
    this.kind = kind;
  }

  public String getBodyAr() {
    return bodyAr;
  }

  public void setBodyAr(String bodyAr) {
    this.bodyAr = bodyAr;
  }

  public String getBodyEn() {
    return bodyEn;
  }

  public void setBodyEn(String bodyEn) {
    this.bodyEn = bodyEn;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
