package com.loai.inventory.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/orgs/{orgId}/sales-orders}.
 *
 * <p>Validation lives in {@link com.loai.inventory.service.SalesOrderService}; this is just a
 * Jackson data carrier (snake_case JSON ↔ camelCase Java handled by the global {@code
 * ObjectMapper}).
 */
public class PlaceOnlineOrderRequest {

  private CustomerPayload customer;
  private List<LinePayload> lines;
  private String notes;

  public PlaceOnlineOrderRequest() {}

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
}
