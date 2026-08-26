package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.CouponType;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public class SalesOrderResponse {
  private UUID id;
  private UUID orgId;
  private UUID customerId;
  private String orderNumber;
  private OrderChannel channel;
  private OrderStatus status;
  private BigDecimal subtotal;
  private BigDecimal taxTotal;
  private BigDecimal shippingTotal;
  private BigDecimal discountTotal;

  /** The frozen coupon code this order redeemed (roadmap item 9), or null. */
  private String couponCode;

  private BigDecimal grandTotal;
  private String currency;
  private BigDecimal prepaidAmount;
  private OffsetDateTime placedAt;
  private OffsetDateTime expiresAt;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private String notes;

  /**
   * Where this parcel goes (V80) — the order's own frozen delivery contact. All three omitted when
   * absent (an IN_STORE sale, or an order placed before V80).
   *
   * <p><b>This is the answer to "where do I ship it?" and it has to be here.</b> Until V80 the
   * delivery contact was merged onto the {@code customer} row, so a merchant packing an order read
   * it off the CRM record — which only worked because that merge overwrote the buyer's own details,
   * the very defect V80 removes. With the merge gone, the CRM row correctly shows the buyer, and
   * this is the only place the ship-to exists before an invoice is issued at delivery.
   *
   * <p>Staff plane only — {@code forCustomerView} withholds all three, exactly like {@code notes}:
   * an anonymous magic link may be forwarded, and a home address is not something to hand whoever
   * ends up holding that URL. The shopper typed it and sees it on their own portal order page.
   */
  private String deliveryRecipient;

  private String deliveryPhone;
  private String deliveryAddress;

  /**
   * The walk-in buyer's typed contact (V87) — an IN_STORE sale with no email, so no {@code
   * customerId}. Both omitted when absent (anonymous sale, or an email sale whose CRM row owns the
   * details). Staff plane only, the same rule as {@code notes} and the delivery contact: every
   * internal contact field is withheld from the anonymous magic-link view.
   */
  private String customerName;

  private String customerPhone;

  /**
   * The counter discount a manager granted on this IN_STORE sale (V88) — why {@code discountTotal}
   * is non-zero without a {@code couponCode}. Omitted when none. Staff plane only: it names a staff
   * user id, so {@code forCustomerView} withholds it like every other internal field; {@code
   * discountTotal} itself was already public and stays so.
   */
  private CounterDiscount counterDiscount;

  private List<SalesOrderLineResponse> lines;

  private SalesOrderResponse() {}

  public static SalesOrderResponse from(SalesOrder order, List<SalesOrderLine> lines) {
    return build(order, lines, /* includeInternal= */ true);
  }

  /**
   * The order as shown to the customer over the anonymous magic-link route ({@code
   * /api/public/orders/{token}}). Excludes staff-facing fields: {@code notes} is entered by staff
   * on a STAFF-gated placement endpoint and may hold internal/operational text, so it is withheld
   * from an unauthenticated audience. (A proper customer_note / internal_note split is the durable
   * fix — tracked as follow-up.)
   */
  public static SalesOrderResponse forCustomerView(SalesOrder order, List<SalesOrderLine> lines) {
    return build(order, lines, /* includeInternal= */ false);
  }

  private static SalesOrderResponse build(
      SalesOrder order, List<SalesOrderLine> lines, boolean includeInternal) {
    SalesOrderResponse r = new SalesOrderResponse();
    r.id = order.getId();
    r.orgId = order.getOrgId();
    r.customerId = order.getCustomerId();
    r.orderNumber = order.getOrderNumber();
    r.channel = order.getChannel();
    r.status = order.getStatus();
    r.subtotal = order.getSubtotal();
    r.taxTotal = order.getTaxTotal();
    r.shippingTotal = order.getShippingTotal();
    r.discountTotal = order.getDiscountTotal();
    r.couponCode = order.getCouponCode();
    r.grandTotal = order.getGrandTotal();
    r.currency = order.getCurrency();
    r.prepaidAmount = order.getPrepaidAmount();
    r.placedAt = order.getPlacedAt();
    r.expiresAt = order.getExpiresAt();
    r.createdAt = order.getCreatedAt();
    r.updatedAt = order.getUpdatedAt();
    // notes is staff-facing — omit it from the customer view (Jackson drops nulls).
    r.notes = includeInternal ? order.getNotes() : null;
    // Same rule for the delivery contact: the packing desk needs it, a forwarded magic link does
    // not get a home address.
    r.deliveryRecipient = includeInternal ? order.getDeliveryRecipient() : null;
    r.deliveryPhone = includeInternal ? order.getDeliveryPhone() : null;
    r.deliveryAddress = includeInternal ? order.getDeliveryAddress() : null;
    r.customerName = includeInternal ? order.getCustomerName() : null;
    r.customerPhone = includeInternal ? order.getCustomerPhone() : null;
    r.counterDiscount = includeInternal ? CounterDiscount.from(order) : null;
    r.lines = lines.stream().map(SalesOrderLineResponse::from).toList();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public String getDeliveryRecipient() {
    return deliveryRecipient;
  }

  public String getDeliveryPhone() {
    return deliveryPhone;
  }

  public String getDeliveryAddress() {
    return deliveryAddress;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public String getCustomerName() {
    return customerName;
  }

  public String getCustomerPhone() {
    return customerPhone;
  }

  public String getOrderNumber() {
    return orderNumber;
  }

  public OrderChannel getChannel() {
    return channel;
  }

  public OrderStatus getStatus() {
    return status;
  }

  public BigDecimal getSubtotal() {
    return subtotal;
  }

  public BigDecimal getTaxTotal() {
    return taxTotal;
  }

  public BigDecimal getShippingTotal() {
    return shippingTotal;
  }

  public BigDecimal getDiscountTotal() {
    return discountTotal;
  }

  public String getCouponCode() {
    return couponCode;
  }

  public BigDecimal getGrandTotal() {
    return grandTotal;
  }

  public String getCurrency() {
    return currency;
  }

  public BigDecimal getPrepaidAmount() {
    return prepaidAmount;
  }

  public OffsetDateTime getPlacedAt() {
    return placedAt;
  }

  public OffsetDateTime getExpiresAt() {
    return expiresAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public String getNotes() {
    return notes;
  }

  public CounterDiscount getCounterDiscount() {
    return counterDiscount;
  }

  public List<SalesOrderLineResponse> getLines() {
    return lines;
  }

  /** What was keyed and who signed it; {@code reason} omitted when the manager typed none. */
  public record CounterDiscount(CouponType type, BigDecimal value, String reason, UUID by) {
    static CounterDiscount from(SalesOrder order) {
      if (order.getCounterDiscountType() == null) {
        return null;
      }
      return new CounterDiscount(
          order.getCounterDiscountType(),
          order.getCounterDiscountValue(),
          order.getCounterDiscountReason(),
          order.getCounterDiscountBy());
    }
  }
}
