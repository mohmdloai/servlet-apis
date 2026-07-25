package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.VariantAttribute;
import com.loai.inventory.domain.model.VariantAttributeValue;
import com.loai.inventory.service.ProductVariantService.VariantRow;
import com.loai.inventory.service.ProductVariantService.VariantSetView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The admin variant read (slice VG1) — the full editor state for one listing.
 *
 * <p><b>Admin plane</b>: internal ids (the child {@code product_id}, so the rail can deep-link to
 * that product's inventory screen) and the SKU/barcode are deliberately present here. None of them
 * appear on the public listing read, which names a variant only by its {@code key} (architecture
 * §3).
 *
 * <p>Inactive variants are included: removal is a deactivation, and the merchant must be able to
 * see — and reactivate — what was removed.
 */
public class VariantSetResponse {

  private final List<AttributeDto> attributes;
  private final List<VariantDto> variants;

  private VariantSetResponse(List<AttributeDto> attributes, List<VariantDto> variants) {
    this.attributes = attributes;
    this.variants = variants;
  }

  public static VariantSetResponse from(VariantSetView view) {
    return new VariantSetResponse(
        view.attributes().stream().map(AttributeDto::from).toList(),
        view.variants().stream().map(VariantDto::from).toList());
  }

  public List<AttributeDto> getAttributes() {
    return attributes;
  }

  public List<VariantDto> getVariants() {
    return variants;
  }

  /** An axis with its paired names and every value defined on it (the editor's vocabulary). */
  public static class AttributeDto {
    private final String slug;
    private final String nameAr;
    private final String nameEn;
    private final List<ValueDto> values;

    private AttributeDto(String slug, String nameAr, String nameEn, List<ValueDto> values) {
      this.slug = slug;
      this.nameAr = nameAr;
      this.nameEn = nameEn;
      this.values = values;
    }

    static AttributeDto from(VariantAttribute a) {
      return new AttributeDto(
          a.slug(),
          a.names().get("ar"),
          a.names().get("en"),
          a.values().stream().map(ValueDto::from).toList());
    }

    public String getSlug() {
      return slug;
    }

    public String getNameAr() {
      return nameAr;
    }

    public String getNameEn() {
      return nameEn;
    }

    public List<ValueDto> getValues() {
      return values;
    }
  }

  /** One selectable value with its paired names. */
  public static class ValueDto {
    private final String slug;
    private final String nameAr;
    private final String nameEn;

    private ValueDto(String slug, String nameAr, String nameEn) {
      this.slug = slug;
      this.nameAr = nameAr;
      this.nameEn = nameEn;
    }

    static ValueDto from(VariantAttributeValue v) {
      return new ValueDto(v.slug(), v.names().get("ar"), v.names().get("en"));
    }

    public String getSlug() {
      return slug;
    }

    public String getNameAr() {
      return nameAr;
    }

    public String getNameEn() {
      return nameEn;
    }
  }

  /**
   * One variant row, keyed publicly by {@code key} and privately by its child {@code product_id}.
   */
  public static class VariantDto {
    private final String key;
    private final Map<String, String> options;
    private final BigDecimal salesPrice;
    private final int sortOrder;
    private final boolean active;
    private final UUID productId;
    private final String sku;
    private final String barcode;

    private VariantDto(
        String key,
        Map<String, String> options,
        BigDecimal salesPrice,
        int sortOrder,
        boolean active,
        UUID productId,
        String sku,
        String barcode) {
      this.key = key;
      this.options = options;
      this.salesPrice = salesPrice;
      this.sortOrder = sortOrder;
      this.active = active;
      this.productId = productId;
      this.sku = sku;
      this.barcode = barcode;
    }

    static VariantDto from(VariantRow row) {
      return new VariantDto(
          row.key(),
          row.options(),
          row.salesPrice(),
          row.sortOrder(),
          row.active(),
          row.productId(),
          row.sku(),
          row.barcode());
    }

    public String getKey() {
      return key;
    }

    public Map<String, String> getOptions() {
      return options;
    }

    public BigDecimal getSalesPrice() {
      return salesPrice;
    }

    public int getSortOrder() {
      return sortOrder;
    }

    public boolean isActive() {
      return active;
    }

    public UUID getProductId() {
      return productId;
    }

    public String getSku() {
      return sku;
    }

    public String getBarcode() {
      return barcode;
    }
  }
}
