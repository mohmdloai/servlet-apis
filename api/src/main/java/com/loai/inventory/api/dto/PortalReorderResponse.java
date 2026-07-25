package com.loai.inventory.api.dto;

import com.loai.inventory.service.CustomerPortalService.ReorderItem;
import com.loai.inventory.service.CustomerPortalService.ReorderResult;
import com.loai.inventory.service.CustomerPortalService.UnavailableItem;
import java.math.BigDecimal;
import java.util.List;

/**
 * Result of {@code POST /api/portal/orders/{orderNumber}/reorder} (slice P4, {@code
 * stories/portal_addresses_reorder.md}): the past order resolved into a currently-buyable cart.
 * Each {@link Item} is keyed by the public {@code listing_slug} (never a {@code product_id}) so the
 * storefront can add it to the same cart the catalog uses; {@code unavailable} lists the lines
 * whose product is no longer published, so the UI can report an honest "N of M added".
 */
public class PortalReorderResponse {
  private List<Item> items;
  private List<Unavailable> unavailable;

  private PortalReorderResponse() {}

  public static PortalReorderResponse from(ReorderResult result) {
    PortalReorderResponse r = new PortalReorderResponse();
    r.items = result.items().stream().map(Item::of).toList();
    r.unavailable = result.unavailable().stream().map(Unavailable::of).toList();
    return r;
  }

  public List<Item> getItems() {
    return items;
  }

  public List<Unavailable> getUnavailable() {
    return unavailable;
  }

  /**
   * One buyable cart item — the storefront adds it by {@code listing_slug} (plus {@code variant},
   * VG2) at {@code qty}. A line bought as an option comes back naming that option, so "buy it
   * again" restores the same size/colour rather than a different one at the parent's price.
   */
  public static class Item {
    private String listingSlug;
    private String variant;
    private String title;
    private BigDecimal unitPrice;
    private boolean inStock;
    private int qty;

    private static Item of(ReorderItem src) {
      Item i = new Item();
      i.listingSlug = src.slug();
      i.variant = src.variant();
      i.title = src.title();
      i.unitPrice = src.unitPrice();
      i.inStock = src.inStock();
      i.qty = src.qty();
      return i;
    }

    public String getListingSlug() {
      return listingSlug;
    }

    public String getVariant() {
      return variant;
    }

    public String getTitle() {
      return title;
    }

    public BigDecimal getUnitPrice() {
      return unitPrice;
    }

    public boolean getInStock() {
      return inStock;
    }

    public int getQty() {
      return qty;
    }
  }

  /** A line that could not be re-added — its product is no longer published. */
  public static class Unavailable {
    private String title;
    private int qty;

    private static Unavailable of(UnavailableItem src) {
      Unavailable u = new Unavailable();
      u.title = src.description();
      u.qty = src.qty();
      return u;
    }

    public String getTitle() {
      return title;
    }

    public int getQty() {
      return qty;
    }
  }
}
