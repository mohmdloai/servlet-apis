package com.loai.inventory.api.dto;

import com.loai.inventory.service.StorefrontService.ListingView;
import java.math.BigDecimal;
import java.util.List;

/**
 * Public, whitelisted storefront view of a listing. Intentionally omits internal id, {@code
 * product_id}, status, timestamps and object keys. {@code categories} is populated only on the
 * detail endpoint (null/omitted on list items). {@code rating_avg} (one decimal, string) + {@code
 * rating_count} are the APPROVED-review aggregate (slice R1) — both omitted when the listing has no
 * approved review (absent, never zero-fabricated; epic §8/§10).
 */
public class PublicListingResponse {
  private String slug;
  private String title;
  private String marketingCopy;
  private BigDecimal salesPrice;
  private boolean inStock;
  private List<PublicListingImageResponse> images;
  private List<PublicCategoryRefResponse> categories;
  private String ratingAvg;
  private Long ratingCount;

  private PublicListingResponse() {}

  public static PublicListingResponse from(ListingView v) {
    PublicListingResponse r = new PublicListingResponse();
    r.slug = v.slug();
    r.title = v.title();
    r.marketingCopy = v.marketingCopy();
    r.salesPrice = v.salesPrice();
    r.inStock = v.inStock();
    r.images = v.images().stream().map(PublicListingImageResponse::from).toList();
    r.categories =
        v.categories() == null || v.categories().isEmpty()
            ? null
            : v.categories().stream().map(PublicCategoryRefResponse::from).toList();
    r.ratingAvg = v.ratingAvg();
    r.ratingCount = v.ratingCount();
    return r;
  }

  public String getSlug() {
    return slug;
  }

  public String getTitle() {
    return title;
  }

  public String getMarketingCopy() {
    return marketingCopy;
  }

  public BigDecimal getSalesPrice() {
    return salesPrice;
  }

  public boolean isInStock() {
    return inStock;
  }

  public List<PublicListingImageResponse> getImages() {
    return images;
  }

  public List<PublicCategoryRefResponse> getCategories() {
    return categories;
  }

  public String getRatingAvg() {
    return ratingAvg;
  }

  public Long getRatingCount() {
    return ratingCount;
  }
}
