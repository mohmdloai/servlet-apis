package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Category;
import com.loai.inventory.service.CategoryService.CategoryView;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public class CategoryResponse {
  private UUID id;
  private UUID orgId;
  private UUID parentCategoryId;
  private String name;
  private String slug;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  /**
   * Every authored language (content-localization slice L3); null on the legacy base-only shape.
   */
  private List<CategoryTranslationDto> translations;

  private CategoryResponse() {}

  public static CategoryResponse from(Category c) {
    CategoryResponse r = new CategoryResponse();
    r.id = c.getId();
    r.orgId = c.getOrgId();
    r.parentCategoryId = c.getParentCategoryId();
    r.name = c.getName();
    r.slug = c.getSlug();
    r.createdAt = c.getCreatedAt();
    r.updatedAt = c.getUpdatedAt();
    return r;
  }

  /** The admin shape carrying every language's name (slice L3). */
  public static CategoryResponse from(CategoryView v) {
    CategoryResponse r = from(v.category());
    r.translations = v.translations().stream().map(CategoryTranslationDto::from).toList();
    return r;
  }

  public List<CategoryTranslationDto> getTranslations() {
    return translations;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public UUID getParentCategoryId() {
    return parentCategoryId;
  }

  public String getName() {
    return name;
  }

  public String getSlug() {
    return slug;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
