package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.ledger.JournalEntryView;
import com.loai.inventory.domain.model.ledger.JournalLineView;
import com.loai.inventory.service.LedgerService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** {@code GET /api/orgs/{orgId}/ledger/journal?from=&to=&account=&page=&size=}. */
public record JournalPageResponse(
    OffsetDateTime from,
    OffsetDateTime to,
    String account,
    int page,
    int size,
    long total,
    List<Entry> items) {

  public record Line(
      int seq, String accountCode, String accountName, String side, BigDecimal amount) {
    static Line from(JournalLineView l) {
      return new Line(l.seq(), l.accountCode(), l.accountName(), l.side().name(), l.amount());
    }
  }

  public record Entry(
      UUID id,
      long entryNo,
      OffsetDateTime postedAt,
      String sourceType,
      String sourceId,
      String event,
      String memo,
      List<Line> lines) {
    static Entry from(JournalEntryView e) {
      return new Entry(
          e.id(),
          e.entryNo(),
          e.postedAt(),
          e.sourceType(),
          e.sourceId(),
          e.event(),
          e.memo(),
          e.lines().stream().map(Line::from).toList());
    }
  }

  public static JournalPageResponse from(LedgerService.Journal j) {
    return new JournalPageResponse(
        j.from(),
        j.to(),
        j.account(),
        j.page().page(),
        j.page().size(),
        j.result().total(),
        j.result().items().stream().map(Entry::from).toList());
  }
}
