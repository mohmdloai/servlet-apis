package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One selectable option of a listing — "Red / M" — bridging the listing to its <b>child product</b>
 * (slice VG1, {@code docs/catalog-variants-architecture.md} §2).
 *
 * <p>{@code productId} is that child: an ordinary org {@code product} with its own SKU, barcode,
 * and inventory row, which is why per-variant stock, restock, scan-to-stock and scan-to-sell all
 * work with no new inventory code. {@code variantKey} is the <b>public</b> handle ({@code "red-m"})
 * — unique within the listing, and the only name a variant ever crosses the public boundary under
 * (architecture §3).
 *
 * <p>{@code active} is how removal works: dropping a variant from a set-replace flips this to false
 * rather than deleting, because the child product may already be referenced by order lines and
 * inventory and that history has to stand.
 */
public record ProductVariant(
    UUID id,
    UUID orgId,
    UUID productListingId,
    UUID productId,
    String variantKey,
    BigDecimal salesPrice,
    int sortOrder,
    boolean active) {}
