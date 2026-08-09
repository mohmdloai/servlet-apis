package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.ArAgingReportResponse;
import com.loai.inventory.api.dto.InventoryValuationResponse;
import com.loai.inventory.api.dto.RevenueReportResponse;
import com.loai.inventory.api.dto.SalesReportResponse;
import com.loai.inventory.api.dto.TopProductsReportResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.service.ReportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code GET /api/orgs/{orgId}/reports/*} — the read-only dashboard aggregates ({@code
 * stories/reporting_reads.md}): {@code revenue}, {@code sales}, {@code top-products}, {@code
 * ar-aging}, {@code inventory-valuation}.
 *
 * <p><b>VIEWER+</b>: every figure a VIEWER could already compute by paging the money/inventory
 * lists, so gating higher would be theater. {@code requireOrgAccess} enforces membership +
 * suspension (platform-ADMIN bypass preserved). GET only — any mutating verb on any {@code
 * /reports/*} path is 405 (append-only, like {@code /api/admin/audit}). An unknown report name is a
 * 404. All 400s (bad window/bucket/channel/by/limit/buckets) come from {@link ReportService}.
 */
public class ReportsHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(ReportsHandler.class);

  private final ReportService service;
  private final ObjectMapper mapper;

  public ReportsHandler(ReportService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method,
      HttpServletRequest req,
      HttpServletResponse resp,
      UUID orgId,
      String remainingPath)
      throws IOException {
    try {
      String report = reportName(remainingPath);
      if (report.isEmpty()) {
        writeError(resp, 404, "Unknown report route: " + remainingPath);
        return;
      }
      // Reject unknown report names as 404 regardless of verb; a known report with a bad verb →
      // 405.
      if (!isKnownReport(report)) {
        writeError(resp, 404, "Unknown report: " + report);
        return;
      }
      if (!"GET".equals(method)) {
        writeError(resp, 405, "Method not allowed");
        return;
      }
      AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);

      switch (report) {
        case "revenue" ->
            writeJson(
                resp,
                200,
                RevenueReportResponse.from(
                    service.revenue(
                        orgId,
                        req.getParameter("from"),
                        req.getParameter("to"),
                        req.getParameter("bucket"))));
        case "sales" ->
            writeJson(
                resp,
                200,
                SalesReportResponse.from(
                    service.sales(
                        orgId,
                        req.getParameter("from"),
                        req.getParameter("to"),
                        req.getParameter("bucket"),
                        req.getParameter("channel"))));
        case "top-products" ->
            writeJson(
                resp,
                200,
                TopProductsReportResponse.from(
                    service.topProducts(
                        orgId,
                        req.getParameter("from"),
                        req.getParameter("to"),
                        req.getParameter("by"),
                        req.getParameter("limit"))));
        case "ar-aging" ->
            writeJson(
                resp,
                200,
                ArAgingReportResponse.from(service.arAging(orgId, req.getParameter("buckets"))));
        case "inventory-valuation" ->
            writeJson(
                resp, 200, InventoryValuationResponse.from(service.inventoryValuation(orgId)));
        default -> writeError(resp, 404, "Unknown report: " + report);
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/reports{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private static boolean isKnownReport(String report) {
    return switch (report) {
      case "revenue", "sales", "top-products", "ar-aging", "inventory-valuation" -> true;
      default -> false;
    };
  }

  /**
   * The segment(s) after {@code /reports/}; empty for a bare {@code /reports} or trailing slash. A
   * nested path (e.g. {@code /reports/revenue/x}) is returned intact so it fails the known-report
   * check and 404s, rather than matching {@code revenue}.
   */
  private static String reportName(String remainingPath) {
    if (remainingPath == null || remainingPath.isEmpty() || "/".equals(remainingPath)) {
      return "";
    }
    String raw = remainingPath.startsWith("/") ? remainingPath.substring(1) : remainingPath;
    // Tolerate a single trailing slash (/reports/revenue/) as the bare report name.
    return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse resp, AppException e) throws IOException {
    ApiErrors.applyHeaders(resp, e);
    writeJson(resp, e.getStatusCode(), ApiErrors.body(e));
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }
}
