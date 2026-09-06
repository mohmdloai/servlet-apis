package com.loai.inventory.domain.model;

/**
 * The stock overview tabs' numbers ({@code stories/inventory_filters.md}) — org totals, one query,
 * unfiltered by search or the sheet (the status-counts convention Orders and Invoices use).
 *
 * @param all every product in the org, untracked included
 * @param low tracked and {@code available <= lowLte} (the caller's alert level)
 * @param reorder tracked, has a reorder point, and {@code available <= reorder_point}
 * @param out tracked and {@code available == 0}
 * @param untracked no inventory row
 */
public record InventoryStockCounts(long all, long low, long reorder, long out, long untracked) {}
