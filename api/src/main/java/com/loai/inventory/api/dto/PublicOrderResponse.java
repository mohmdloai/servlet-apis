package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.service.CustomerPortalService;
import com.loai.inventory.service.StorefrontService.CheckoutResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Customer-safe checkout confirmation ({@code POST /api/public/{orgSlug}/checkout} → 201/200).
 * Whitelisted: it carries <b>no</b> {@code product_id}, {@code org_id}, internal order {@code id},
 * {@code customer_id}, or status timestamps other than {@code placed_at}/{@code expires_at}. Each
 * line is labelled with the listing title the shopper saw, not the internal product description.
 * See {@code stories/public_checkout.md}.
 */
public class PublicOrderResponse {

  private String orderNumber;
  private String status;
  private String currency;
  private BigDecimal subtotal;
  private BigDecimal taxTotal;
  private BigDecimal discountTotal;
  private BigDecimal grandTotal;
  private OffsetDateTime placedAt;
  private OffsetDateTime expiresAt;
  private List<Line> lines;
  private String paymentInstructions;
  private String trackUrl;

  private PublicOrderResponse() {}

  public static PublicOrderResponse from(CheckoutResult r) {
    PublicOrderResponse out = new PublicOrderResponse();
    out.orderNumber = r.order().getOrderNumber();
    out.status = r.order().getStatus().name();
    out.currency = r.order().getCurrency();
    out.subtotal = r.order().getSubtotal();
    out.taxTotal = r.order().getTaxTotal();
    out.discountTotal = r.order().getDiscountTotal();
    out.grandTotal = r.order().getGrandTotal();
    out.placedAt = r.order().getPlacedAt();
    out.expiresAt = r.order().getExpiresAt();
    out.lines =
        r.lines().stream()
            .map(l -> Line.from(l, r.titleByProductId().get(l.getProductId())))
            .toList();
    out.paymentInstructions = r.paymentInstructions();
    out.trackUrl = r.trackUrl();
    return out;
  }

  /**
   * The customer-safe body for the anonymous order-view magic link ({@code GET
   * /api/public/orders/{token}} — the {@code track_url}). Same whitelist as {@link
   * #from(CheckoutResult)}: <b>no</b> internal order {@code id}, {@code org_id}, {@code
   * customer_id}, per-line {@code product_id}, {@code prepaid_amount}, {@code channel}, or {@code
   * created_at}/{@code updated_at} — nothing beyond what the shopper needs to see their order.
   * Lines are labelled with their placement-time {@code description} (the label the customer saw at
   * checkout). {@code payment_instructions}/{@code track_url} stay null — the storefront page reads
   * payment details from the org profile it already loads.
   */
  public static PublicOrderResponse forOrderView(SalesOrder order, List<SalesOrderLine> lines) {
    PublicOrderResponse out = new PublicOrderResponse();
    out.orderNumber = order.getOrderNumber();
    out.status = order.getStatus().name();
    out.currency = order.getCurrency();
    out.subtotal = order.getSubtotal();
    out.taxTotal = order.getTaxTotal();
    out.discountTotal = order.getDiscountTotal();
    out.grandTotal = order.getGrandTotal();
    out.placedAt = order.getPlacedAt();
    out.expiresAt = order.getExpiresAt();
    out.lines = lines.stream().map(l -> Line.from(l, l.getDescription())).toList();
    return out;
  }

  /**
   * The portal order <b>detail</b> (slice R1 rider, frontend story 40): the same customer-safe
   * whitelist as {@link #forOrderView}, with two additive per-line fields — {@code listing_slug}
   * (the line's public listing identity, so "rate this item" can pre-scope the review form) and
   * {@code delivered} (goods in hand — the same fact the review-eligibility gate checks). Both stay
   * omitted on the anonymous track view and the portal list rows.
   */
  public static PublicOrderResponse forPortalOrderDetail(CustomerPortalService.OrderDetail detail) {
    PublicOrderResponse out = forOrderView(detail.order(), detail.lines());
    for (int i = 0; i < detail.lines().size(); i++) {
      SalesOrderLine line = detail.lines().get(i);
      Line dto = out.lines.get(i);
      dto.listingSlug = detail.listingSlugByProduct().get(line.getProductId());
      dto.delivered = detail.deliveredLineIds().contains(line.getId());
    }
    return out;
  }

  public String getOrderNumber() {
    return orderNumber;
  }

  public String getStatus() {
    return status;
  }

  public String getCurrency() {
    return currency;
  }

  public BigDecimal getSubtotal() {
    return subtotal;
  }

  public BigDecimal getTaxTotal() {
    return taxTotal;
  }

  public BigDecimal getDiscountTotal() {
    return discountTotal;
  }

  public BigDecimal getGrandTotal() {
    return grandTotal;
  }

  public OffsetDateTime getPlacedAt() {
    return placedAt;
  }

  public OffsetDateTime getExpiresAt() {
    return expiresAt;
  }

  public List<Line> getLines() {
    return lines;
  }

  public String getPaymentInstructions() {
    return paymentInstructions;
  }

  public String getTrackUrl() {
    return trackUrl;
  }

  /**
   * A confirmation line: the listing title, quantity, and snapshotted unit + line totals. {@code
   * listingSlug} + {@code delivered} ride only the portal order detail ({@link
   * #forPortalOrderDetail}) — omitted (null) everywhere else.
   */
  public static class Line {
    private String title;
    private int quantity;
    private BigDecimal unitPrice;
    private BigDecimal lineTotal;
    private String listingSlug;
    private Boolean delivered;

    private Line() {}

    static Line from(SalesOrderLine l, String title) {
      Line out = new Line();
      out.title = title;
      out.quantity = l.getQuantity();
      out.unitPrice = l.getUnitPrice();
      out.lineTotal = l.getLineTotal();
      return out;
    }

    public String getTitle() {
      return title;
    }

    public int getQuantity() {
      return quantity;
    }

    public BigDecimal getUnitPrice() {
      return unitPrice;
    }

    public BigDecimal getLineTotal() {
      return lineTotal;
    }

    public String getListingSlug() {
      return listingSlug;
    }

    public Boolean getDelivered() {
      return delivered;
    }
  }
}
