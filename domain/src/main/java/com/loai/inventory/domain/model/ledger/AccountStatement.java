package com.loai.inventory.domain.model.ledger;

import java.math.BigDecimal;
import java.util.List;

/**
 * An account's statement over {@code [from, to)}: the balance carried in, the page of lines with
 * their running balance, the window's line count, and the balance carried out.
 */
public record AccountStatement(
    LedgerChart.Account account,
    BigDecimal opening,
    List<AccountStatementLine> items,
    long total,
    BigDecimal closing) {}
