package com.loai.inventory.api.dto;

import java.util.UUID;

public class CreateCategoryRequest {
  private String name;
  private String slug;
  private UUID parentCategoryId;

  public CreateCategoryRequest() {}

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

  public UUID getParentCategoryId() {
    return parentCategoryId;
  }

  public void setParentCategoryId(UUID parentCategoryId) {
    this.parentCategoryId = parentCategoryId;
  }
}
