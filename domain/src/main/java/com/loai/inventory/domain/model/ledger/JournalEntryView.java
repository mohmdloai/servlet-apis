package com.loai.inventory.domain.model.ledger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** A posted entry with its legs, in the journal read's order ({@code posted_at, entry_no}). */
public record JournalEntryView(
    UUID id,
    long entryNo,
    OffsetDateTime postedAt,
    String sourceType,
    String sourceId,
    String event,
    String memo,
    List<JournalLineView> lines) {}
