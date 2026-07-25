package com.loai.inventory.api.dto;

import com.loai.inventory.service.ProductVariantService.AttributeInput;
import com.loai.inventory.service.ProductVariantService.ValueInput;
import com.loai.inventory.service.ProductVariantService.VariantInput;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Body for {@code PUT /product-listings/{id}/variants} — the whole variant set, replaced atomically
 * (slice VG1). The payload IS the intended state: a key it omits is deactivated, not deleted.
 *
 * <p>Axis and value names come as the paired {@code name_ar}/{@code name_en} the admin editor
 * authors side by side; the service maps them onto the {@code _translation} rows, so widening to a
 * third language is a service + migration change, not a wire break.
 */
public class VariantSetRequest {

  private List<AttributeDto> attributes;
  private List<VariantDto> variants;

  public VariantSetRequest() {}

  public List<AttributeInput> toAttributeInputs() {
    if (attributes == null) {
      return List.of();
    }
    List<AttributeInput> out = new ArrayList<>(attributes.size());
    for (AttributeDto a : attributes) {
      out.add(a == null ? null : a.toInput());
    }
    return out;
  }

  public List<VariantInput> toVariantInputs() {
    if (variants == null) {
      return List.of();
    }
    List<VariantInput> out = new ArrayList<>(variants.size());
    for (VariantDto v : variants) {
      out.add(v == null ? null : v.toInput());
    }
    return out;
  }

  public List<AttributeDto> getAttributes() {
    return attributes;
  }

  public void setAttributes(List<AttributeDto> attributes) {
    this.attributes = attributes;
  }

  public List<VariantDto> getVariants() {
    return variants;
  }

  public void setVariants(List<VariantDto> variants) {
    this.variants = variants;
  }

  /** One declared option axis: {@code {slug, name_ar, name_en, values:[…]}}. */
  public static class AttributeDto {
    private String slug;
    private String nameAr;
    private String nameEn;
    private List<ValueDto> values;

    public AttributeDto() {}

    AttributeInput toInput() {
      List<ValueInput> valueInputs = new ArrayList<>();
      if (values != null) {
        for (ValueDto v : values) {
          valueInputs.add(v == null ? null : v.toInput());
        }
      }
      return new AttributeInput(slug, names(nameAr, nameEn), valueInputs);
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

    public List<ValueDto> getValues() {
      return values;
    }

    public void setValues(List<ValueDto> values) {
      this.values = values;
    }
  }

  /** One selectable value on an axis: {@code {slug, name_ar, name_en}}. */
  public static class ValueDto {
    private String slug;
    private String nameAr;
    private String nameEn;

    public ValueDto() {}

    ValueInput toInput() {
      return new ValueInput(slug, names(nameAr, nameEn));
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
  }

  /** One variant row: {@code {key?, options, sales_price, sku, barcode?, active?}}. */
  public static class VariantDto {
    private String key;
    private Map<String, String> options;
    private BigDecimal salesPrice;
    private String sku;
    private String barcode;
    private Boolean active;

    public VariantDto() {}

    VariantInput toInput() {
      return new VariantInput(key, options, salesPrice, sku, barcode, active);
    }

    public String getKey() {
      return key;
    }

    public void setKey(String key) {
      this.key = key;
    }

    public Map<String, String> getOptions() {
      return options;
    }

    public void setOptions(Map<String, String> options) {
      this.options = options;
    }

    public BigDecimal getSalesPrice() {
      return salesPrice;
    }

    public void setSalesPrice(BigDecimal salesPrice) {
      this.salesPrice = salesPrice;
    }

    public String getSku() {
      return sku;
    }

    public void setSku(String sku) {
      this.sku = sku;
    }

    public String getBarcode() {
      return barcode;
    }

    public void setBarcode(String barcode) {
      this.barcode = barcode;
    }

    public Boolean getActive() {
      return active;
    }

    public void setActive(Boolean active) {
      this.active = active;
    }
  }

  private static Map<String, String> names(String nameAr, String nameEn) {
    Map<String, String> names = new LinkedHashMap<>();
    if (nameAr != null) {
      names.put("ar", nameAr);
    }
    if (nameEn != null) {
      names.put("en", nameEn);
    }
    return names;
  }
}
