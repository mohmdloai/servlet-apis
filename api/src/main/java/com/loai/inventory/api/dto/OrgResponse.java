package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Org;
import java.time.OffsetDateTime;
import java.util.UUID;

public class OrgResponse {
  private UUID id;
  private String name;
  private String slug;
  private boolean active;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  private OrgResponse() {}

  public static OrgResponse from(Org o) {
    OrgResponse r = new OrgResponse();
    r.id = o.getId();
    r.name = o.getName();
    r.slug = o.getSlug();
    r.active = o.isActive();
    r.createdAt = o.getCreatedAt();
    r.updatedAt = o.getUpdatedAt();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getSlug() {
    return slug;
  }

  public boolean isActive() {
    return active;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
