package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.ledger.TrialBalanceRow;
import com.loai.inventory.service.LedgerService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /api/orgs/{orgId}/ledger/trial-balance?from=&to=} (stories/general_ledger.md). Every
 * chart account, zero-filled, with {@code opening} / {@code closing} signed on its normal side and
 * the window's raw {@code debit} / {@code credit}; {@code total_debit == total_credit} is the
 * balance proof the client shows. The client derives the income statement (REVENUE / EXPENSE
 * movements) and the balance sheet (ASSET / LIABILITY / EQUITY closings + the period's net) from
 * this one read.
 */
public record TrialBalanceResponse(
    OffsetDateTime from,
    OffsetDateTime to,
    List<Row> accounts,
    BigDecimal totalDebit,
    BigDecimal totalCredit) {

  public record Row(
      String code,
      String name,
      String type,
      String normalSide,
      BigDecimal opening,
      BigDecimal debit,
      BigDecimal credit,
      BigDecimal closing) {
    static Row from(TrialBalanceRow r) {
      return new Row(
          r.code(),
          r.name(),
          r.type().name(),
          r.normalSide().name(),
          r.opening(),
          r.debit(),
          r.credit(),
          r.closing());
    }
  }

  public static TrialBalanceResponse from(LedgerService.TrialBalance tb) {
    BigDecimal dr = BigDecimal.ZERO.setScale(2);
    BigDecimal cr = BigDecimal.ZERO.setScale(2);
    for (TrialBalanceRow r : tb.rows()) {
      dr = dr.add(r.debit());
      cr = cr.add(r.credit());
    }
    return new TrialBalanceResponse(
        tb.from(), tb.to(), tb.rows().stream().map(Row::from).toList(), dr, cr);
  }
}
