package com.loai.inventory.api.dto;

import com.loai.inventory.service.ReportService.InventoryValuationReport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * {@code GET /api/orgs/{orgId}/reports/inventory-valuation} — point-in-time stock units + retail
 * value over tracked products. {@code retailValue} (JSON {@code retail_value}) is retail-basis on
 * purpose — {@code product} has no cost column — so the field name states the basis. See {@code
 * stories/reporting_reads.md} §G5.
 */
public record InventoryValuationResponse(
    OffsetDateTime asOf,
    long trackedProducts,
    long totalUnits,
    BigDecimal retailValue,
    long outOfStock) {

  public static InventoryValuationResponse from(InventoryValuationReport r) {
    return new InventoryValuationResponse(
        r.asOf(),
        r.valuation().trackedProducts(),
        r.valuation().totalUnits(),
        r.valuation().retailValue(),
        r.valuation().outOfStock());
  }
}
