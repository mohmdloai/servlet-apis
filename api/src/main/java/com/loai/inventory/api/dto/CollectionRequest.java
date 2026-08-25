package com.loai.inventory.api.dto;

/**
 * Body for {@code POST /collections} and {@code PUT /collections/{id}} (roadmap item 8). One shape
 * for both verbs — a PUT is a full replace of the collection's identity, so there is no
 * partial-merge asymmetry to model. {@code sortOrder} absent means 0 (the rail's front, tie-broken
 * by slug). Validation (slug shape/uniqueness, the default-locale name, the per-org cap) is the
 * service's job.
 */
public class CollectionRequest {
  private String slug;
  private String nameAr;
  private String nameEn;
  private Integer sortOrder;

  /**
   * Org-scoped image key from {@code POST /collections/presign} ({@code
   * stories/collection_image.md}). Create: absent/blank = no image. Update: absent = unchanged,
   * blank = clear, value = replace — the one merged field on an otherwise full-replace PUT.
   */
  private String imageObjectKey;

  public CollectionRequest() {}

  public String getImageObjectKey() {
    return imageObjectKey;
  }

  public void setImageObjectKey(String imageObjectKey) {
    this.imageObjectKey = imageObjectKey;
  }

  public String getSlug() {
    return slug;
  }

  public void setSlug(String slug) {
    this.slug = slug;
  }

  public String getNameAr() {
    return nameAr;
  }

  public void setNameAr(String nameAr) {
    this.nameAr = nameAr;
  }

  public String getNameEn() {
    return nameEn;
  }

  public void setNameEn(String nameEn) {
    this.nameEn = nameEn;
  }

  public Integer getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(Integer sortOrder) {
    this.sortOrder = sortOrder;
  }
}
