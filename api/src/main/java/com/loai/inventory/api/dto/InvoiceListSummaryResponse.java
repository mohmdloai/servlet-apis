package com.loai.inventory.api.dto;

import java.math.BigDecimal;

/**
 * The money the whole filtered set adds up to, riding on the invoice list envelope ({@code
 * stories/invoice_filters.md}): {@code outstanding} = Σ {@code grand_total − paid_amount} over the
 * ISSUED rows; {@code issued} = Σ {@code grand_total} over ISSUED + PAID (VOID never counts). Both
 * always present, {@code 0.00} for an empty set — the worklist's "2 invoices match · EGP 1,920.00
 * outstanding" line is one read, never a second request.
 */
public record InvoiceListSummaryResponse(BigDecimal outstanding, BigDecimal issued) {}
