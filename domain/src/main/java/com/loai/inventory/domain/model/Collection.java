package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A merchant-defined, named list of listings ("Ramadan picks") — roadmap item 8, {@code
 * stories/storefront_collections.md}. Org-scoped; {@code slug} is unique per org and is the public
 * handle (the {@code /col/{slug}} landing page and the {@code ?collection=} predicate), so no
 * internal id ever crosses the public boundary.
 *
 * <p>{@code sortOrder} is the merchant's rail order (tie-broken by slug). The bilingual {@code
 * name} lives in {@code collection_translation} (the V63 pattern); the single {@code name} carried
 * here is the resolved display value — the org's default locale on the admin plane, the requested
 * locale on the public rail — joined in by the repository, never a stored column.
 */
public class Collection {
  private UUID id;
  private UUID orgId;
  private String slug;
  private String name;
  private int sortOrder;

  /**
   * Object-storage key of the merchant's collection image ({@code {orgId}/collection/…}); null = no
   * image, the rail tile stays text-only. Never a URL — the reads presign it.
   */
  private String imageObjectKey;

  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public Collection() {}

  public Collection(
      UUID id,
      UUID orgId,
      String slug,
      String name,
      int sortOrder,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.orgId = orgId;
    this.slug = slug;
    this.name = name;
    this.sortOrder = sortOrder;
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

  public String getSlug() {
    return slug;
  }

  public void setSlug(String slug) {
    this.slug = slug;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(int sortOrder) {
    this.sortOrder = sortOrder;
  }

  public String getImageObjectKey() {
    return imageObjectKey;
  }

  public void setImageObjectKey(String imageObjectKey) {
    this.imageObjectKey = imageObjectKey;
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
    return "Collection{id=" + id + ", orgId=" + orgId + ", slug='" + slug + "'}";
  }
}
