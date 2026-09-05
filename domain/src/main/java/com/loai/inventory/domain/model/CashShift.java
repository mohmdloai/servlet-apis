package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One drawer-day ({@code stories/cash_shift.md}). Three human inputs — the starting float, the
 * counted cash, a note — and one derived figure, {@code expectedCash}, frozen at close. A shift
 * closes once; there is no reopen, and a wrong count is corrected by a movement on the next shift.
 */
public final class CashShift {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

  private final UUID id;
  private final UUID orgId;
  private final UUID openedBy;
  private final OffsetDateTime openedAt;
  private final boolean autoOpened;
  private BigDecimal startingCash;
  private UUID closedBy;
  private OffsetDateTime closedAt;
  private BigDecimal countedCash;
  private BigDecimal expectedCash;
  private String note;
  private final OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  private CashShift(
      UUID id,
      UUID orgId,
      UUID openedBy,
      OffsetDateTime openedAt,
      boolean autoOpened,
      BigDecimal startingCash,
      UUID closedBy,
      OffsetDateTime closedAt,
      BigDecimal countedCash,
      BigDecimal expectedCash,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.orgId = orgId;
    this.openedBy = openedBy;
    this.openedAt = openedAt;
    this.autoOpened = autoOpened;
    this.startingCash = startingCash;
    this.closedBy = closedBy;
    this.closedAt = closedAt;
    this.countedCash = countedCash;
    this.expectedCash = expectedCash;
    this.note = note;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  /** Open a shift with a counted float; {@code autoOpened} when the first counter sale did it. */
  public static CashShift open(
      UUID id,
      UUID orgId,
      UUID openedBy,
      BigDecimal startingCash,
      boolean autoOpened,
      String note,
      OffsetDateTime now) {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(orgId, "orgId required");
    Objects.requireNonNull(openedBy, "openedBy required");
    Objects.requireNonNull(now, "now required");
    return new CashShift(
        id,
        orgId,
        openedBy,
        now,
        autoOpened,
        money(startingCash, "starting cash"),
        null,
        null,
        null,
        null,
        blankToNull(note),
        now,
        now);
  }

  public static CashShift rehydrate(
      UUID id,
      UUID orgId,
      UUID openedBy,
      OffsetDateTime openedAt,
      boolean autoOpened,
      BigDecimal startingCash,
      UUID closedBy,
      OffsetDateTime closedAt,
      BigDecimal countedCash,
      BigDecimal expectedCash,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    return new CashShift(
        id,
        orgId,
        openedBy,
        openedAt,
        autoOpened,
        startingCash,
        closedBy,
        closedAt,
        countedCash,
        expectedCash,
        note,
        createdAt,
        updatedAt);
  }

  public boolean isOpen() {
    return closedAt == null;
  }

  /** Fix the float while open — the one edit an auto-opened shift invites. */
  public void setStartingCash(BigDecimal startingCash, OffsetDateTime now) {
    requireOpen("change the float of");
    this.startingCash = money(startingCash, "starting cash");
    this.updatedAt = now;
  }

  /**
   * Freeze the count against the derived expectation. {@code expectedCash} is what the service
   * computed from the stamped ledger and the movements — never a typed figure.
   */
  public void close(
      UUID closedBy,
      BigDecimal countedCash,
      BigDecimal expectedCash,
      String note,
      OffsetDateTime now) {
    requireOpen("close");
    Objects.requireNonNull(closedBy, "closedBy required");
    Objects.requireNonNull(expectedCash, "expectedCash required");
    this.closedBy = closedBy;
    this.closedAt = now;
    this.countedCash = money(countedCash, "counted cash");
    this.expectedCash = expectedCash.setScale(MONEY_SCALE, MONEY_ROUNDING);
    if (note != null && !note.isBlank()) {
      this.note = note.trim();
    }
    this.updatedAt = now;
  }

  /** {@code counted − expected}: positive = over, negative = short; {@code null} while open. */
  public BigDecimal getDifference() {
    return isOpen() ? null : countedCash.subtract(expectedCash);
  }

  private void requireOpen(String verb) {
    if (!isOpen()) {
      throw new IllegalStateException("cannot " + verb + " a closed shift");
    }
  }

  private static BigDecimal money(BigDecimal amount, String what) {
    if (amount == null || amount.signum() < 0) {
      throw new IllegalArgumentException(what + " must be >= 0");
    }
    return amount.setScale(MONEY_SCALE, MONEY_ROUNDING);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public UUID getOpenedBy() {
    return openedBy;
  }

  public OffsetDateTime getOpenedAt() {
    return openedAt;
  }

  public boolean isAutoOpened() {
    return autoOpened;
  }

  public BigDecimal getStartingCash() {
    return startingCash;
  }

  public UUID getClosedBy() {
    return closedBy;
  }

  public OffsetDateTime getClosedAt() {
    return closedAt;
  }

  public BigDecimal getCountedCash() {
    return countedCash;
  }

  public BigDecimal getExpectedCash() {
    return expectedCash;
  }

  public String getNote() {
    return note;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
