package com.loai.inventory.domain.model.report;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One {@code (bucket, channel)} cell of the sales report ({@code GET /reports/sales}). "Sales" here
 * means <em>stood-up</em> sales — orders currently in a money-committed status
 * (PAID/FULFILLING/FULFILLED/CLOSED); an order that later cancels drops out of a subsequent read.
 * See {@code stories/reporting_reads.md} §G2.
 *
 * @param channel the {@code order_channel} name ({@code ONLINE|IN_STORE|PHONE})
 */
public record SalesPoint(OffsetDateTime period, String channel, long orders, BigDecimal gross) {}
