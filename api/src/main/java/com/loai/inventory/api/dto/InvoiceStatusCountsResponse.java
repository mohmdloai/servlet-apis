package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.InvoiceStatus;
import java.util.Map;

/**
 * {@code GET /invoices/status-counts} ({@code stories/invoice_filters.md}) — the worklist tabs'
 * numbers.
 *
 * <p>JSON shape: { "counts": { "DRAFT": 0, "ISSUED": 12, "PAID": 41, "VOID": 3 }, "total": 56 }
 *
 * <p>All four {@link InvoiceStatus} values are always present, {@code 0} included, and {@code
 * total} is the unfiltered ledger count (== Σ counts by construction) so the All tab reads one
 * field. Map keys serialize as enum names — the SNAKE_CASE strategy renames bean properties, never
 * map keys — matching the frontend's {@code InvoiceStatus} strings. The same shape as {@link
 * OrderStatusCountsResponse}.
 */
public record InvoiceStatusCountsResponse(Map<InvoiceStatus, Long> counts, long total) {}
