package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.FulfillmentLine;
import java.util.UUID;

public class FulfillmentLineResponse {
  private UUID id;
  private UUID salesOrderLineId;
  private int quantity;
  private UUID inventoryReservationId;

  private FulfillmentLineResponse() {}

  public static FulfillmentLineResponse from(FulfillmentLine line) {
    FulfillmentLineResponse r = new FulfillmentLineResponse();
    r.id = line.getId();
    r.salesOrderLineId = line.getSalesOrderLineId();
    r.quantity = line.getQuantity();
    r.inventoryReservationId = line.getInventoryReservationId();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public UUID getSalesOrderLineId() {
    return salesOrderLineId;
  }

  public int getQuantity() {
    return quantity;
  }

  public UUID getInventoryReservationId() {
    return inventoryReservationId;
  }
}
