package com.loai.inventory.api.dto;

import java.math.BigDecimal;

/**
 * The money the whole filtered set adds up to, riding on the order list envelope ({@code
 * stories/order_filters.md}): {@code outstanding} = Σ {@code grand_total − prepaid_amount} over the
 * live rows still owing; {@code value} = Σ {@code grand_total} over the live statuses
 * (PENDING_PAYMENT, PAID, FULFILLING, FULFILLED, CLOSED — a DRAFT, CANCELLED or EXPIRED order's
 * amount is not in force). Both always present, {@code 0.00} for an empty set — the worklist's "12
 * orders · EGP 1,240.00 outstanding" line is one read, never a second request.
 */
public record OrderListSummaryResponse(BigDecimal outstanding, BigDecimal value) {}
