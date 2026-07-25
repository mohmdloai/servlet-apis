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

  public CollectionRequest() {}

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
