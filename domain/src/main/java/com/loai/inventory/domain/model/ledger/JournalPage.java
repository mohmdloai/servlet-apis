package com.loai.inventory.domain.model.ledger;

import java.util.List;

/** A page of the journal plus the window's total entry count. */
public record JournalPage(List<JournalEntryView> items, long total) {}
