package com.loai.inventory.domain.model.ledger;

import java.math.BigDecimal;

/**
 * One account on the trial balance over a half-open window {@code [from, to)}.
 *
 * <p>{@code opening} and {@code closing} are signed on the account's normal side (a positive
 * receivable is money owed to us; a positive revenue is revenue): {@code closing = opening + (debit
 * − credit)} for a DR-normal account and {@code opening + (credit − debit)} for a CR-normal one.
 * {@code debit} / {@code credit} are the raw movement sums inside the window, so the statement
 * identity Σ debit = Σ credit over every row is visible to the reader.
 */
public record TrialBalanceRow(
    String code,
    String name,
    AccountType type,
    Side normalSide,
    BigDecimal opening,
    BigDecimal debit,
    BigDecimal credit,
    BigDecimal closing) {}
