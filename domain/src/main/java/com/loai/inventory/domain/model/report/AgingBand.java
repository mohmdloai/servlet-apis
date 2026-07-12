package com.loai.inventory.domain.model.report;

import java.math.BigDecimal;

/**
 * One age band of the AR-aging report ({@code GET /reports/ar-aging}) — the count and outstanding
 * money of ISSUED, not-fully-paid invoices whose age (now − {@code issued_at}) falls in this band.
 * Unlike the sparse time series, the band set is fixed and always fully present (zero-filled). See
 * {@code stories/reporting_reads.md} §G4.
 *
 * @param label human band, e.g. {@code "0-30"}, {@code "31-60"}, {@code "90+"}
 * @param outstanding Σ({@code grand_total − paid_amount}) for invoices in this band
 */
public record AgingBand(String label, long count, BigDecimal outstanding) {}
