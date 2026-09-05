package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.CashShift;
import com.loai.inventory.service.CashShiftService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A cash shift on the wire ({@code stories/cash_shift.md} §Endpoints); money as bare scale-2
 * numbers.
 */
public class ShiftResponse {

  private UUID id;
  private String status;
  private PersonResponse openedBy;
  private OffsetDateTime openedAt;
  private boolean autoOpened;
  private BigDecimal startingCash;
  private PersonResponse closedBy;
  private OffsetDateTime closedAt;
  private BigDecimal countedCash;
  private BigDecimal expectedCash;
  private BigDecimal difference;
  private String note;
  private TotalsResponse totals;

  /** Detail only; {@code null} on the list and on the writes. */
  private List<ShiftMovementResponse> movements;

  public static ShiftResponse from(CashShiftService.ShiftView v) {
    CashShift s = v.shift();
    ShiftResponse r = new ShiftResponse();
    r.id = s.getId();
    r.status = s.isOpen() ? "OPEN" : "CLOSED";
    r.openedBy = PersonResponse.from(v.openedBy());
    r.openedAt = s.getOpenedAt();
    r.autoOpened = s.isAutoOpened();
    r.startingCash = s.getStartingCash();
    r.closedBy = PersonResponse.from(v.closedBy());
    r.closedAt = s.getClosedAt();
    r.countedCash = s.getCountedCash();
    r.expectedCash = v.expectedCash();
    r.difference = v.difference();
    r.note = s.getNote();
    r.totals = TotalsResponse.from(v.totals());
    return r;
  }

  public static ShiftResponse from(CashShiftService.Detail d) {
    ShiftResponse r = from(d.view());
    r.movements = d.movements().stream().map(ShiftMovementResponse::from).toList();
    return r;
  }

  public static class PersonResponse {
    private UUID id;
    private String name;

    static PersonResponse from(CashShiftService.Person p) {
      if (p == null) {
        return null;
      }
      PersonResponse r = new PersonResponse();
      r.id = p.id();
      r.name = p.name();
      return r;
    }

    public UUID getId() {
      return id;
    }

    public String getName() {
      return name;
    }
  }

  public static class TotalsResponse {
    private BigDecimal cashSales;
    private BigDecimal changeGiven;
    private BigDecimal cashRefunds;
    private BigDecimal payIn;
    private BigDecimal payOut;
    private BigDecimal instapayTotal;
    private long receipts;
    private BigDecimal discounts;

    static TotalsResponse from(com.loai.inventory.domain.repository.CashShiftRepository.Totals t) {
      TotalsResponse r = new TotalsResponse();
      r.cashSales = t.cashSales();
      r.changeGiven = t.changeGiven();
      r.cashRefunds = t.cashRefunds();
      r.payIn = t.payIn();
      r.payOut = t.payOut();
      r.instapayTotal = t.instapayTotal();
      r.receipts = t.receipts();
      r.discounts = t.discounts();
      return r;
    }

    public BigDecimal getCashSales() {
      return cashSales;
    }

    public BigDecimal getChangeGiven() {
      return changeGiven;
    }

    public BigDecimal getCashRefunds() {
      return cashRefunds;
    }

    public BigDecimal getPayIn() {
      return payIn;
    }

    public BigDecimal getPayOut() {
      return payOut;
    }

    public BigDecimal getInstapayTotal() {
      return instapayTotal;
    }

    public long getReceipts() {
      return receipts;
    }

    public BigDecimal getDiscounts() {
      return discounts;
    }
  }

  public UUID getId() {
    return id;
  }

  public String getStatus() {
    return status;
  }

  public PersonResponse getOpenedBy() {
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

  public PersonResponse getClosedBy() {
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

  public BigDecimal getDifference() {
    return difference;
  }

  public String getNote() {
    return note;
  }

  public TotalsResponse getTotals() {
    return totals;
  }

  public List<ShiftMovementResponse> getMovements() {
    return movements;
  }
}
