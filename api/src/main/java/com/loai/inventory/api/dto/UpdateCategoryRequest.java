package com.loai.inventory.api.dto;

import java.util.List;
import java.util.UUID;

public class UpdateCategoryRequest {
  private String name;
  private String slug;
  private UUID parentCategoryId;

  /**
   * Per-language names (content-localization slice L3). When present, supersedes the single {@code
   * name} and replaces the whole set; the org's default-locale entry is required. When absent,
   * {@code name} becomes the default-locale row (backward-compatible).
   */
  private List<CategoryTranslationDto> translations;

  /**
   * Org-scoped image key from {@code POST /categories/presign} ({@code stories/category_image.md}).
   * Create: absent/blank = no image. Update: absent = unchanged, blank = clear, value = replace.
   */
  private String imageObjectKey;

  public UpdateCategoryRequest() {}

  public String getImageObjectKey() {
    return imageObjectKey;
  }

  public void setImageObjectKey(String imageObjectKey) {
    this.imageObjectKey = imageObjectKey;
  }

  public List<CategoryTranslationDto> getTranslations() {
    return translations;
  }

  public void setTranslations(List<CategoryTranslationDto> translations) {
    this.translations = translations;
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

  public UUID getParentCategoryId() {
    return parentCategoryId;
  }

  public void setParentCategoryId(UUID parentCategoryId) {
    this.parentCategoryId = parentCategoryId;
  }
}
