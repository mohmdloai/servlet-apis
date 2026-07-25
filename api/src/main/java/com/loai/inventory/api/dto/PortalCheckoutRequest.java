package com.loai.inventory.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Authenticated checkout body ({@code POST /api/portal/checkout} — slice P6, {@code
 * stories/portal_checkout.md}). The customer is the session — there are deliberately no identity
 * fields (no email/name); the delivery contact is either a saved {@code address_id} (P4,
 * ownership-checked) or a typed {@code address} ({@code save_address:true} adds it to the book).
 * The cart is public listing slugs, resolved server-side exactly like the anonymous checkout.
 * Jackson maps snake_case JSON ({@code listing_slug}, {@code address_id}) onto these camelCase
 * fields.
 */
public class PortalCheckoutRequest {

  private List<LineBody> lines;
  private String notes;
  private UUID addressId;
  private PortalAddressRequest address;
  private Boolean saveAddress;

  /**
   * Optional checkout language (slice L2b): the locale the order line's title is snapshotted in
   * (unset → org default; unknown → 400). The storefront sends its route locale.
   */
  private String locale;

  public List<LineBody> getLines() {
    return lines;
  }

  /**
   * The optional coupon code the shopper applied (roadmap item 9, portal checkout). Absent/blank =
   * no coupon — every pre-V72 client, and the overwhelming majority of orders. Re-validated inside
   * the placement transaction; the pre-checkout preview is advisory only.
   */
  private String coupon;

  public String getCoupon() {
    return coupon;
  }

  public void setCoupon(String coupon) {
    this.coupon = coupon;
  }

  public String getLocale() {
    return locale;
  }

  public void setLocale(String locale) {
    this.locale = locale;
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

  public UUID getAddressId() {
    return addressId;
  }

  public void setAddressId(UUID addressId) {
    this.addressId = addressId;
  }

  public PortalAddressRequest getAddress() {
    return address;
  }

  public void setAddress(PortalAddressRequest address) {
    this.address = address;
  }

  public Boolean getSaveAddress() {
    return saveAddress;
  }

  public void setSaveAddress(Boolean saveAddress) {
    this.saveAddress = saveAddress;
  }

  public static class LineBody {
    private String listingSlug;

    /**
     * The variant's public key ("red-m") when the listing sells through variants (VG2) — the same
     * wire as the anonymous checkout, and the only public handle a variant has.
     */
    private String variant;

    private int quantity;

    public String getVariant() {
      return variant;
    }

    public void setVariant(String variant) {
      this.variant = variant;
    }

    public String getListingSlug() {
      return listingSlug;
    }

    public void setListingSlug(String listingSlug) {
      this.listingSlug = listingSlug;
    }

    public int getQuantity() {
      return quantity;
    }

    public void setQuantity(int quantity) {
      this.quantity = quantity;
    }
  }
}
