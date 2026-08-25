package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.CollectionTranslation;
import com.loai.inventory.service.CollectionService.CollectionView;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The admin shape of a collection (roadmap item 8). Carries the paired {@code name_ar}/{@code
 * name_en} the editor renders — the bilingual pair rather than a translations array, because a
 * collection has exactly one field to localize and the admin form is two inputs. {@code name} is
 * the resolved default-locale label (the list column).
 */
public class CollectionResponse {
  private UUID id;
  private UUID orgId;
  private String slug;
  private String name;
  private String nameAr;
  private String nameEn;
  private int sortOrder;
  private long listingCount;

  /** The raw org-scoped key (admin only); null = no image. */
  private String imageObjectKey;

  /** A fresh presigned preview URL for the image; null = no image. */
  private String imageUrl;

  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  private CollectionResponse() {}

  public static CollectionResponse from(CollectionView v) {
    CollectionResponse r = new CollectionResponse();
    r.id = v.collection().getId();
    r.orgId = v.collection().getOrgId();
    r.slug = v.collection().getSlug();
    r.name = v.collection().getName();
    r.sortOrder = v.collection().getSortOrder();
    r.listingCount = v.listingCount();
    r.imageObjectKey = v.collection().getImageObjectKey();
    r.imageUrl = v.imageUrl();
    r.createdAt = v.collection().getCreatedAt();
    r.updatedAt = v.collection().getUpdatedAt();
    for (CollectionTranslation t : v.translations()) {
      if ("ar".equals(t.language())) {
        r.nameAr = t.name();
      } else if ("en".equals(t.language())) {
        r.nameEn = t.name();
      }
    }
    return r;
  }

  /** The write echo: a fresh create/update, before the membership read. */
  public static CollectionResponse from(
      com.loai.inventory.domain.model.Collection c, List<CollectionTranslation> translations) {
    return from(new CollectionView(c, translations, 0L, null));
  }

  public String getImageObjectKey() {
    return imageObjectKey;
  }

  public String getImageUrl() {
    return imageUrl;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public String getSlug() {
    return slug;
  }

  public String getName() {
    return name;
  }

  public String getNameAr() {
    return nameAr;
  }

  public String getNameEn() {
    return nameEn;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public long getListingCount() {
    return listingCount;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
