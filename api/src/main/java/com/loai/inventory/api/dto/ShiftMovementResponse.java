package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.CashMovement;
import com.loai.inventory.service.CashShiftService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A pay-in / pay-out on the wire. */
public class ShiftMovementResponse {

  private UUID id;
  private String kind;
  private BigDecimal amount;
  private String reason;
  private ShiftResponse.PersonResponse recordedBy;
  private OffsetDateTime recordedAt;

  public static ShiftMovementResponse from(CashShiftService.MovementView v) {
    CashMovement m = v.movement();
    ShiftMovementResponse r = new ShiftMovementResponse();
    r.id = m.getId();
    r.kind = m.getKind().name();
    r.amount = m.getAmount();
    r.reason = m.getReason();
    r.recordedBy = ShiftResponse.PersonResponse.from(v.recordedBy());
    r.recordedAt = m.getRecordedAt();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public String getKind() {
    return kind;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getReason() {
    return reason;
  }

  public ShiftResponse.PersonResponse getRecordedBy() {
    return recordedBy;
  }

  public OffsetDateTime getRecordedAt() {
    return recordedAt;
  }
}
