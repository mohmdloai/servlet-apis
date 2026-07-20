package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.ProductListingTranslation;

/**
 * One language's listing copy on the admin wire (content-localization slice L2). Used both inbound
 * (create/update {@code translations: [{language, title, marketing_copy}]}) and outbound (the admin
 * response embeds every language). {@code language} is BCP-47 ({@code ar}/{@code en}); the org's
 * default-locale entry is required on write.
 */
public class ProductListingTranslationDto {
  private String language;
  private String title;
  private String marketingCopy;

  public ProductListingTranslationDto() {}

  public static ProductListingTranslationDto from(ProductListingTranslation t) {
    ProductListingTranslationDto d = new ProductListingTranslationDto();
    d.language = t.language();
    d.title = t.title();
    d.marketingCopy = t.marketingCopy();
    return d;
  }

  /** Convert to the domain value type (used when applying an inbound write). */
  public ProductListingTranslation toDomain() {
    return new ProductListingTranslation(language, title, marketingCopy);
  }

  public String getLanguage() {
    return language;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getMarketingCopy() {
    return marketingCopy;
  }

  public void setMarketingCopy(String marketingCopy) {
    this.marketingCopy = marketingCopy;
  }
}
