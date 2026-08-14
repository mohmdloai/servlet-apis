package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.OrderStatus;
import java.util.Map;

/**
 * {@code GET /sales-orders/status-counts} ({@code stories/order_status_counts.md}) — the worklist
 * tabs' numbers.
 *
 * <p>JSON shape: { "counts": { "DRAFT": 0, "PENDING_PAYMENT": 2, … }, "total": 12 }
 *
 * <p>All eight {@link OrderStatus} values are always present, {@code 0} included — a client must
 * never have to treat absence as zero ("nobody here" is data, the funnel's {@code reached: 0}
 * rule). {@code total} is the unfiltered ledger count (== Σ counts by construction) so the All chip
 * reads one field. Map keys serialize as enum names — the SNAKE_CASE strategy renames bean
 * properties, never map keys — matching the frontend's {@code SalesOrderStatus} strings.
 */
public record OrderStatusCountsResponse(Map<OrderStatus, Long> counts, long total) {}
