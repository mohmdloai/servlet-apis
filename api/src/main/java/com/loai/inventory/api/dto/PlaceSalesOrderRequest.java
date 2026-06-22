package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.OrderChannel;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/orgs/{orgId}/sales-orders}, both channels.
 *
 * <p>{@code channel} selects the flow: {@code ONLINE}/{@code PHONE} (default) place a
 * PENDING_PAYMENT order with reservations; {@code IN_STORE} runs the whole sale in one txn and
 * reads the {@code payment} block. Validation lives in {@link
 * com.loai.inventory.service.SalesOrderService}; this is just a Jackson data carrier (snake_case
 * JSON ↔ camelCase Java handled by the global {@code ObjectMapper}).
 */
public class PlaceSalesOrderRequest {

  private OrderChannel channel;
  private CustomerPayload customer;
  private List<LinePayload> lines;
  private PaymentPayload payment;
  private String notes;

  public PlaceSalesOrderRequest() {}

  public OrderChannel getChannel() {
    return channel;
  }

  public void setChannel(OrderChannel channel) {
    this.channel = channel;
  }

  public CustomerPayload getCustomer() {
    return customer;
  }

  public void setCustomer(CustomerPayload customer) {
    this.customer = customer;
  }

  public List<LinePayload> getLines() {
    return lines;
  }

  public void setLines(List<LinePayload> lines) {
    this.lines = lines;
  }

  public PaymentPayload getPayment() {
    return payment;
  }

  public void setPayment(PaymentPayload payment) {
    this.payment = payment;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public static class CustomerPayload {
    private String name;
    private String email;
    private String phone;
    private String address;

    public CustomerPayload() {}

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

  public static class LinePayload {
    private UUID productId;
    private Integer quantity;

    public LinePayload() {}

    public UUID getProductId() {
      return productId;
    }

    public void setProductId(UUID productId) {
      this.productId = productId;
    }

    public Integer getQuantity() {
      return quantity;
    }

    public void setQuantity(Integer quantity) {
      this.quantity = quantity;
    }
  }

  /** Cashier tender for an in-store sale. {@code provider} is the enum name or the DB literal. */
  public static class PaymentPayload {
    private String provider;
    private String providerRef;
    private BigDecimal amount;

    public PaymentPayload() {}

    public String getProvider() {
      return provider;
    }

    public void setProvider(String provider) {
      this.provider = provider;
    }

    public String getProviderRef() {
      return providerRef;
    }

    public void setProviderRef(String providerRef) {
      this.providerRef = providerRef;
    }

    public BigDecimal getAmount() {
      return amount;
    }

    public void setAmount(BigDecimal amount) {
      this.amount = amount;
    }
  }
}
