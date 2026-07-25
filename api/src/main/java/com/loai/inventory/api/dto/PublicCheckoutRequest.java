package com.loai.inventory.api.dto;

import java.util.List;

/**
 * Anonymous checkout body ({@code POST /api/public/{orgSlug}/checkout}). The cart is expressed in
 * public listing slugs — the slug→product mapping is resolved server-side. Jackson maps snake_case
 * JSON ({@code listing_slug}) onto these camelCase fields. See {@code stories/public_checkout.md}.
 */
public class PublicCheckoutRequest {

  private CustomerBody customer;
  private List<LineBody> lines;
  private String notes;

  /**
   * Optional checkout language (slice L2b): the locale the order line's title is snapshotted in
   * (unset → org default; unknown → 400). The storefront sends its route locale.
   */
  private String locale;

  public CustomerBody getCustomer() {
    return customer;
  }

  public String getLocale() {
    return locale;
  }

  public void setLocale(String locale) {
    this.locale = locale;
  }

  public void setCustomer(CustomerBody customer) {
    this.customer = customer;
  }

  public List<LineBody> getLines() {
    return lines;
  }

  public void setLines(List<LineBody> lines) {
    this.lines = lines;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public static class CustomerBody {
    private String name;
    private String email;
    private String phone;
    private String address;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getEmail() {
      return email;
    }

    public void setEmail(String email) {
      this.email = email;
    }

    public String getPhone() {
      return phone;
    }

    public void setPhone(String phone) {
      this.phone = phone;
    }

    public String getAddress() {
      return address;
    }

    public void setAddress(String address) {
      this.address = address;
    }
  }

  public static class LineBody {
    private String listingSlug;

    /**
     * The variant's public key ("red-m"), when the listing sells through variants (VG2). Required
     * for such a listing (400 otherwise) and rejected for one that has none — the key is the ONLY
     * public handle a variant has; no product id or SKU is representable here.
     */
    private String variant;

    private int quantity;

    public String getListingSlug() {
      return listingSlug;
    }

    public void setListingSlug(String listingSlug) {
      this.listingSlug = listingSlug;
    }

    public String getVariant() {
      return variant;
    }

    public void setVariant(String variant) {
      this.variant = variant;
    }

    public int getQuantity() {
      return quantity;
    }

    public void setQuantity(int quantity) {
      this.quantity = quantity;
    }
  }
}
