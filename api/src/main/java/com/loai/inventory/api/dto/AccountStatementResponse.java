package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.ledger.AccountStatement;
import com.loai.inventory.domain.model.ledger.AccountStatementLine;
import com.loai.inventory.service.LedgerService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/orgs/{orgId}/ledger/accounts/{code}/lines?from=&to=&page=&size=} — one account's
 * statement: the balance carried in, the page of lines each with its running balance, the balance
 * carried out. Balances are on the account's normal side.
 */
public record AccountStatementResponse(
    Account account,
    OffsetDateTime from,
    OffsetDateTime to,
    BigDecimal opening,
    int page,
    int size,
    long total,
    List<Line> items,
    BigDecimal closing) {

  public record Account(String code, String name, String type, String normalSide) {}

  public record Line(
      UUID entryId,
      long entryNo,
      OffsetDateTime postedAt,
      String sourceType,
      String sourceId,
      String event,
      String memo,
      String side,
      BigDecimal amount,
      BigDecimal balanceAfter) {
    static Line from(AccountStatementLine l) {
      return new Line(
          l.entryId(),
          l.entryNo(),
          l.postedAt(),
          l.sourceType(),
          l.sourceId(),
          l.event(),
          l.memo(),
          l.side().name(),
          l.amount(),
          l.balanceAfter());
    }
  }

  public static AccountStatementResponse from(LedgerService.Statement s) {
    AccountStatement r = s.result();
    return new AccountStatementResponse(
        new Account(
            r.account().code(),
            r.account().name(),
            r.account().type().name(),
            r.account().normalSide().name()),
        s.from(),
        s.to(),
        r.opening(),
        s.page().page(),
        s.page().size(),
        r.total(),
        r.items().stream().map(Line::from).toList(),
        r.closing());
  }
}
