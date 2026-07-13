package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.SalesOrderLine;
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

  /** A confirmation line: the listing title, quantity, and snapshotted unit + line totals. */
  public static class Line {
    private String title;
    private int quantity;
    private BigDecimal unitPrice;
    private BigDecimal lineTotal;

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
  }
}
