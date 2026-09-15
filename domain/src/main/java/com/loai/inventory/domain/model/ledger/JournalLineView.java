package com.loai.inventory.domain.model.ledger;

import java.math.BigDecimal;

/** One leg of a journal entry as the journal read shows it. */
public record JournalLineView(
    int seq, String accountCode, String accountName, Side side, BigDecimal amount) {}
