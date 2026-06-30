package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A hierarchical product category ("Notebooks &gt; Spiral"). Org-scoped; slug unique per org. */
public class Category {
  private UUID id;
  private UUID orgId;
  private UUID parentCategoryId;
  private String name;
  private String slug;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public Category() {}

  public Category(
      UUID id,
      UUID orgId,
      UUID parentCategoryId,
      String name,
      String slug,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.orgId = orgId;
    this.parentCategoryId = parentCategoryId;
    this.name = name;
    this.slug = slug;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

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

  public UUID getParentCategoryId() {
    return parentCategoryId;
  }

  public void setParentCategoryId(UUID parentCategoryId) {
    this.parentCategoryId = parentCategoryId;
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
    return "Category{id=" + id + ", orgId=" + orgId + ", slug='" + slug + "'}";
  }
}
