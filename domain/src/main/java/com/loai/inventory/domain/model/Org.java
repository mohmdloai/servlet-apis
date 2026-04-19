package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public class Org {
  private UUID id;
  private String name;
  private String slug;
  private boolean active;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public Org() {}

  public Org(
      UUID id,
      String name,
      String slug,
      boolean active,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.name = name;
    this.slug = slug;
    this.active = active;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getSlug() {
    return slug;
  }

  public void setSlug(String slug) {
    this.slug = slug;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  @Override
  public String toString() {
    return "Org{id=" + id + ", slug='" + slug + "'}";
  }
}
