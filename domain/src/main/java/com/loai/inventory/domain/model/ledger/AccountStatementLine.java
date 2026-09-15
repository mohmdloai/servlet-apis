package com.loai.inventory.domain.model.ledger;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One line of an account's statement. {@code balanceAfter} is the running balance on the account's
 * normal side after this line, computed over the whole window (not the page) so paging never
 * restarts it.
 */
public record AccountStatementLine(
    UUID entryId,
    long entryNo,
    OffsetDateTime postedAt,
    String sourceType,
    String sourceId,
    String event,
    String memo,
    Side side,
    BigDecimal amount,
    BigDecimal balanceAfter) {}
