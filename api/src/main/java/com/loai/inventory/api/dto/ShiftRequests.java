package com.loai.inventory.api.dto;

import java.math.BigDecimal;

/** The four small write bodies of {@code /shifts} ({@code stories/cash_shift.md}). */
public final class ShiftRequests {

  private ShiftRequests() {}

  public static class Open {
    private BigDecimal startingCash;
    private String note;

    public BigDecimal getStartingCash() {
      return startingCash;
    }

    public void setStartingCash(BigDecimal startingCash) {
      this.startingCash = startingCash;
    }

    public String getNote() {
      return note;
    }

    public void setNote(String note) {
      this.note = note;
    }
  }

  public static class Float {
    private BigDecimal startingCash;

    public BigDecimal getStartingCash() {
      return startingCash;
    }

    public void setStartingCash(BigDecimal startingCash) {
      this.startingCash = startingCash;
    }
  }

  public static class Movement {
    private String kind;
    private BigDecimal amount;
    private String reason;

    public String getKind() {
      return kind;
    }

    public void setKind(String kind) {
      this.kind = kind;
    }

    public BigDecimal getAmount() {
      return amount;
    }

    public void setAmount(BigDecimal amount) {
      this.amount = amount;
    }

    public String getReason() {
      return reason;
    }

    public void setReason(String reason) {
      this.reason = reason;
    }
  }

  public static class Close {
    private BigDecimal countedCash;
    private String note;

    public BigDecimal getCountedCash() {
      return countedCash;
    }

    public void setCountedCash(BigDecimal countedCash) {
      this.countedCash = countedCash;
    }

    public String getNote() {
      return note;
    }

    public void setNote(String note) {
      this.note = note;
    }
  }
}
