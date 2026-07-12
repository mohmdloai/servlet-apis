package com.loai.inventory.domain.model.report;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One time-bucket of the revenue report ({@code GET /reports/revenue}). The three figures answer
 * different questions and are deliberately returned side by side: {@code invoiced} (accrual — money
 * billed), {@code collected} (cash — money that arrived), {@code refunded} (cash out — executed
 * refunds). Refund <em>rate</em> is a client-side division of {@code refunded / collected}, not a
 * server column. See {@code stories/reporting_reads.md} §G1.
 *
 * @param period bucket start, {@code date_trunc(bucket, ts, 'UTC')} — the sparse series omits empty
 *     buckets, so a gap here means no rows in that bucket for any column
 */
public record RevenuePoint(
    OffsetDateTime period, BigDecimal invoiced, BigDecimal collected, BigDecimal refunded) {}
