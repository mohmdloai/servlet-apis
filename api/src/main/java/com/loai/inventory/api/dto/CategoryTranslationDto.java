package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.CategoryTranslation;

/**
 * One language's category name on the admin wire (content-localization slice L3). Used both inbound
 * (create/update {@code translations: [{language, name}]}) and outbound (the admin response embeds
 * every language). {@code language} is BCP-47 ({@code ar}/{@code en}); the org's default-locale
 * entry is required on write.
 */
public class CategoryTranslationDto {
  private String language;
  private String name;

  public CategoryTranslationDto() {}

  public static CategoryTranslationDto from(CategoryTranslation t) {
    CategoryTranslationDto d = new CategoryTranslationDto();
    d.language = t.language();
    d.name = t.name();
    return d;
  }

  /** Convert to the domain value type (used when applying an inbound write). */
  public CategoryTranslation toDomain() {
    return new CategoryTranslation(language, name);
  }

  public String getLanguage() {
    return language;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
