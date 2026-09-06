package com.loai.inventory.api.dto;

/**
 * {@code GET /inventory/stock-counts?low_lte=} ({@code stories/inventory_filters.md}) — the stock
 * overview tabs' numbers.
 *
 * <p>JSON shape: { "all": 128, "low": 9, "reorder": 4, "out": 3, "untracked": 6 }
 *
 * <p>Flat, keyed by the frontend's segment names (the {@code ?stock=} values plus {@code all}), all
 * five always present, {@code 0} included. Org totals, unfiltered by search or the sheet — the
 * status-counts convention Orders and Invoices use. {@code low} is at the caller's {@code low_lte}
 * (default 5, the LOW tab's own default).
 */
public record InventoryStockCountsResponse(
    long all, long low, long reorder, long out, long untracked) {}
