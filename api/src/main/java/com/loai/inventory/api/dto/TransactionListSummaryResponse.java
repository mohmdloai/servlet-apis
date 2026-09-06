package com.loai.inventory.api.dto;

import java.math.BigDecimal;

/**
 * The money the whole filtered ledger adds up to, riding on the transaction list envelope ({@code
 * stories/transaction_filters.md}): {@code money_in} = Σ {@code amount} over VERIFIED CREDITs (a
 * shopper's claim resting UNVERIFIED is a row, not money), {@code money_out} = Σ {@code amount}
 * over VERIFIED DEBITs (executed refunds). Both always present, {@code 0.00} for an empty set — the
 * worklist's "12 transfers · EGP 14,320.00 in" line is one read, never a second request.
 */
public record TransactionListSummaryResponse(BigDecimal moneyIn, BigDecimal moneyOut) {}
