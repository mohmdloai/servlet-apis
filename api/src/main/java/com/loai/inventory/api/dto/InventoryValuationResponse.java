package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.report.InventoryValuation;
import com.loai.inventory.service.ReportService.InventoryValuationReport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * {@code GET /api/orgs/{orgId}/reports/inventory-valuation} — point-in-time stock units + value
 * over tracked products. {@code retailValue} (JSON {@code retail_value}) is retail-basis on purpose
 * and always present. The three cost fields (stories/product_cost_and_margin.md) are MANAGER-plane:
 * written only with manager authority — {@code costValue} (over the costed products; absent when
 * none is costed) and the two coverage primitives {@code uncostedProducts} / {@code uncostedUnits}
 * (always present when visible). Below MANAGER the envelope is byte-identical to the pre-V92 shape.
 * See {@code stories/reporting_reads.md} §G5.
 */
public record InventoryValuationResponse(
    OffsetDateTime asOf,
    long trackedProducts,
    long totalUnits,
    BigDecimal retailValue,
    long outOfStock,
    BigDecimal costValue,
    Long uncostedProducts,
    Long uncostedUnits) {

  /** The pre-V92 envelope: no cost figure crosses. */
  public static InventoryValuationResponse from(InventoryValuationReport r) {
    return from(r, false);
  }

  public static InventoryValuationResponse from(InventoryValuationReport r, boolean costVisible) {
    InventoryValuation v = r.valuation();
    return new InventoryValuationResponse(
        r.asOf(),
        v.trackedProducts(),
        v.totalUnits(),
        v.retailValue(),
        v.outOfStock(),
        costVisible ? v.costValue() : null,
        costVisible ? v.uncostedProducts() : null,
        costVisible ? v.uncostedUnits() : null);
  }
}
