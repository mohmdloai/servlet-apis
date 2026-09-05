package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * A pay-in or pay-out on an open {@link CashShift} ({@code stories/cash_shift.md}): who moved how
 * much, when, and why. Immutable once written — a wrong movement is answered by another one.
 */
public final class CashMovement {

  private final UUID id;
  private final UUID orgId;
  private final UUID shiftId;
  private final CashMovementKind kind;
  private final BigDecimal amount;
  private final String reason;
  private final UUID recordedBy;
  private final OffsetDateTime recordedAt;

  private CashMovement(
      UUID id,
      UUID orgId,
      UUID shiftId,
      CashMovementKind kind,
      BigDecimal amount,
      String reason,
      UUID recordedBy,
      OffsetDateTime recordedAt) {
    this.id = id;
    this.orgId = orgId;
    this.shiftId = shiftId;
    this.kind = kind;
    this.amount = amount;
    this.reason = reason;
    this.recordedBy = recordedBy;
    this.recordedAt = recordedAt;
  }

  public static CashMovement create(
      UUID id,
      UUID orgId,
      UUID shiftId,
      CashMovementKind kind,
      BigDecimal amount,
      String reason,
      UUID recordedBy,
      OffsetDateTime now) {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(orgId, "orgId required");
    Objects.requireNonNull(shiftId, "shiftId required");
    Objects.requireNonNull(kind, "kind required");
    Objects.requireNonNull(recordedBy, "recordedBy required");
    Objects.requireNonNull(now, "now required");
    if (amount == null || amount.signum() <= 0) {
      throw new IllegalArgumentException("movement amount must be > 0");
    }
    String r = reason == null ? "" : reason.trim();
    if (r.isEmpty() || r.length() > 200) {
      throw new IllegalArgumentException("movement reason must be 1–200 characters");
    }
    return new CashMovement(
        id, orgId, shiftId, kind, amount.setScale(2, RoundingMode.HALF_EVEN), r, recordedBy, now);
  }

  public static CashMovement rehydrate(
      UUID id,
      UUID orgId,
      UUID shiftId,
      CashMovementKind kind,
      BigDecimal amount,
      String reason,
      UUID recordedBy,
      OffsetDateTime recordedAt) {
    return new CashMovement(id, orgId, shiftId, kind, amount, reason, recordedBy, recordedAt);
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public UUID getShiftId() {
    return shiftId;
  }

  public CashMovementKind getKind() {
    return kind;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getReason() {
    return reason;
  }

  public UUID getRecordedBy() {
    return recordedBy;
  }

  public OffsetDateTime getRecordedAt() {
    return recordedAt;
  }
}
