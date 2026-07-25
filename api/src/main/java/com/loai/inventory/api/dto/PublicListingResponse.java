package com.loai.inventory.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loai.inventory.service.StorefrontService.ListingView;
import com.loai.inventory.service.StorefrontService.VariantView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Public, whitelisted storefront view of a listing. Intentionally omits internal id, {@code
 * product_id}, status, timestamps and object keys. {@code categories} is populated only on the
 * detail endpoint (null/omitted on list items). {@code rating_avg} (one decimal, string) + {@code
 * rating_count} are the APPROVED-review aggregate (slice R1) — both omitted when the listing has no
 * approved review (absent, never zero-fabricated; epic §8/§10).
 *
 * <p>Variants (VG2, architecture §3): {@code has_variants} says the listing sells through options —
 * on a list row it also means {@code sales_price} is the "from" price (the cheapest active variant)
 * — and the detail carries the {@code variants} block. A variant is named by its {@code key} and
 * nothing else: no {@code product_id}, no SKU, no barcode is representable in this shape, which is
 * what the pinned whitelist tests hold in place.
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
  private boolean hasVariants;
  private List<Variant> variants;

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
    r.hasVariants = v.hasVariants();
    // Omitted entirely on list rows (the grid never pays for it) and on variant-less listings —
    // an empty array would imply "has options, none available", which is a different claim.
    r.variants =
        v.variants() == null || v.variants().isEmpty()
            ? null
            : v.variants().stream().map(Variant::from).toList();
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

  public boolean isHasVariants() {
    return hasVariants;
  }

  public List<Variant> getVariants() {
    return variants;
  }

  /**
   * One selectable option on the detail: its public {@code key}, the composed {@code label} ("Red /
   * M"), the per-axis localized {@code options}, its own {@code price}, and its own {@code
   * in_stock}. Quantity, ids, SKU and barcode all stay behind the boundary.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Variant(
      String key, String label, Map<String, String> options, BigDecimal price, boolean inStock) {
    static Variant from(VariantView v) {
      return new Variant(v.key(), v.label(), v.options(), v.price(), v.inStock());
    }
  }
}
