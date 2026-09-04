package com.loai.inventory.api.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.loai.inventory.api.servlet.handler.ReportsHandler;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.report.InventoryValuation;
import com.loai.inventory.domain.model.report.ProfitTotals;
import com.loai.inventory.domain.model.report.TopProduct;
import com.loai.inventory.service.ReportService;
import com.loai.inventory.service.ReportService.ArAgingReport;
import com.loai.inventory.service.ReportService.InventoryValuationReport;
import com.loai.inventory.service.ReportService.ProfitReport;
import com.loai.inventory.service.ReportService.RevenueReport;
import com.loai.inventory.service.ReportService.SalesReport;
import com.loai.inventory.service.ReportService.TopProductsReport;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Runtime auth + routing for {@code GET /api/orgs/{orgId}/reports/*} ({@code
 * stories/reporting_reads.md}): the five original reports are VIEWER reads; anon → 401; a
 * non-member → 403; any mutating verb on a known report → 405; an unknown report name → 404; a
 * {@link ValidationException} from the service surfaces as 400. Since V92
 * (stories/product_cost_and_margin.md) cost is MANAGER-plane: {@code /profit} and {@code
 * ?by=profit} are 403 below MANAGER, and the cost keys on the other two reads ride only for
 * managers — asserted on the JSON tree.
 */
class ReportsHandlerAuthTest {

  static {
    // ReportService is final; the Mockito inline mock-maker needs ByteBuddy experimental on Java
    // 25.
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID OTHER_ORG = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";
  private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

  // /profit is deliberately not here: it is the one MANAGER-plane report (tests below).
  private static final String[] ALL_REPORTS = {
    "revenue", "sales", "top-products", "ar-aging", "inventory-valuation"
  };

  private SecurityContext ctxWith(UUID orgId, OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(orgId, Set.of(role)), Set.of(), 0);
  }

  private ReportsHandler handler(ReportService service) {
    return new ReportsHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  private static ReportService stubbedService() {
    ReportService service = Mockito.mock(ReportService.class);
    when(service.revenue(any(), any(), any(), any()))
        .thenReturn(
            new RevenueReport(
                "day", NOW, NOW, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    when(service.sales(any(), any(), any(), any(), any()))
        .thenReturn(new SalesReport("day", NOW, NOW, null, List.of(), 0, BigDecimal.ZERO));
    when(service.topProducts(any(), any(), any(), any(), any()))
        .thenReturn(new TopProductsReport(NOW, NOW, "revenue", 10, List.of()));
    when(service.arAging(any(), any()))
        .thenReturn(new ArAgingReport(NOW, List.of(), 0, BigDecimal.ZERO));
    when(service.inventoryValuation(any()))
        .thenReturn(
            new InventoryValuationReport(
                NOW,
                new InventoryValuation(
                    3, 15, new BigDecimal("1100.00"), 1, new BigDecimal("640.00"), 1, 5)));
    when(service.profit(any(), any(), any()))
        .thenReturn(
            new ProfitReport(
                NOW,
                NOW,
                new ProfitTotals(
                    8,
                    3,
                    new BigDecimal("150.00"),
                    new BigDecimal("90.00"),
                    new BigDecimal("60.00"))));
    return service;
  }

  private static TopProductsReport costedTopProducts() {
    return new TopProductsReport(
        NOW,
        NOW,
        "revenue",
        10,
        List.of(
            new TopProduct(
                UUID.randomUUID(),
                "Notebook",
                "NB-1",
                8,
                new BigDecimal("456.00"),
                3,
                new BigDecimal("150.00"),
                new BigDecimal("90.00"),
                new BigDecimal("60.00")),
            new TopProduct(UUID.randomUUID(), "Pen", "PEN-1", 2, new BigDecimal("10.00"))));
  }

  private static JsonNode json(Resp resp) throws IOException {
    return com.loai.inventory.api.config.ObjectMapperProvider.build()
        .readTree(resp.body.toByteArray());
  }

  // Cost is MANAGER-plane (stories/product_cost_and_margin.md)

  @Test
  void profit_viewerAndStaffAre403_serviceNeverCalled() throws IOException {
    ReportService service = stubbedService();
    for (OrgRole role : List.of(OrgRole.VIEWER, OrgRole.STAFF)) {
      Resp resp = new Resp();
      handler(service)
          .handle("GET", reqWith(ctxWith(ORG, role), Map.of()), resp.mock, ORG, "/profit");
      assertEquals(403, resp.status, role + " must not read /profit");
    }
    verify(service, never()).profit(any(), any(), any());
  }

  @Test
  void profit_managerAndOwnerRead200_withTheTotals() throws IOException {
    ReportService service = stubbedService();
    for (OrgRole role : List.of(OrgRole.MANAGER, OrgRole.OWNER)) {
      Resp resp = new Resp();
      handler(service)
          .handle("GET", reqWith(ctxWith(ORG, role), Map.of()), resp.mock, ORG, "/profit");
      assertEquals(200, resp.status, role + " reads /profit");
      JsonNode j = json(resp);
      assertEquals(8, j.get("quantity").asLong());
      assertEquals(3, j.get("costed_quantity").asLong());
      assertEquals(0, new BigDecimal("60.00").compareTo(j.get("gross_profit").decimalValue()));
    }
  }

  @Test
  void profit_mutatingVerbIs405_subpathIs404() throws IOException {
    ReportService service = stubbedService();
    Resp post = new Resp();
    handler(service)
        .handle("POST", reqWith(ctxWith(ORG, OrgRole.OWNER), Map.of()), post.mock, ORG, "/profit");
    assertEquals(405, post.status);
    Resp sub = new Resp();
    handler(service)
        .handle("GET", reqWith(ctxWith(ORG, OrgRole.OWNER), Map.of()), sub.mock, ORG, "/profit/x");
    assertEquals(404, sub.status);
  }

  @Test
  void topProducts_byProfit_is403BelowManager_beforeTheService() throws IOException {
    ReportService service = stubbedService();
    Resp resp = new Resp();
    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.STAFF), Map.of("by", "profit")),
            resp.mock,
            ORG,
            "/top-products");
    assertEquals(403, resp.status);
    verify(service, never()).topProducts(any(), any(), any(), any(), any());
    // The ordinary sorts stay a STAFF read.
    Resp rev = new Resp();
    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.STAFF), Map.of("by", "revenue")),
            rev.mock,
            ORG,
            "/top-products");
    assertEquals(200, rev.status);
  }

  @Test
  void topProducts_costKeysRideOnlyForManagers() throws IOException {
    ReportService service = stubbedService();
    when(service.topProducts(any(), any(), any(), any(), any())).thenReturn(costedTopProducts());

    Resp staff = new Resp();
    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.STAFF), Map.of()),
            staff.mock,
            ORG,
            "/top-products");
    assertEquals(200, staff.status);
    for (JsonNode row : json(staff).get("items")) {
      for (String key : List.of("costed_quantity", "costed_net_sales", "cost", "gross_profit")) {
        assertFalse(row.has(key), "STAFF row must not carry " + key + ": " + row);
      }
      assertTrue(row.has("revenue") && row.has("quantity"), "the shipped shape is intact");
    }

    Resp manager = new Resp();
    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.MANAGER), Map.of()),
            manager.mock,
            ORG,
            "/top-products");
    JsonNode rows = json(manager).get("items");
    JsonNode costed = rows.get(0);
    assertEquals(3, costed.get("costed_quantity").asLong());
    assertEquals(
        0, new BigDecimal("150.00").compareTo(costed.get("costed_net_sales").decimalValue()));
    assertEquals(0, new BigDecimal("90.00").compareTo(costed.get("cost").decimalValue()));
    assertEquals(0, new BigDecimal("60.00").compareTo(costed.get("gross_profit").decimalValue()));
    JsonNode uncosted = rows.get(1);
    assertEquals(0, uncosted.get("costed_quantity").asLong(), "the primitive is always there");
    for (String key : List.of("costed_net_sales", "cost", "gross_profit")) {
      assertFalse(uncosted.has(key), "nothing costed → no money key, never 0: " + uncosted);
    }
  }

  @Test
  void inventoryValuation_costKeysRideOnlyForManagers() throws IOException {
    ReportService service = stubbedService();
    Resp staff = new Resp();
    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.STAFF), Map.of()),
            staff.mock,
            ORG,
            "/inventory-valuation");
    JsonNode s = json(staff);
    for (String key : List.of("cost_value", "uncosted_products", "uncosted_units")) {
      assertFalse(s.has(key), "STAFF envelope must not carry " + key);
    }
    assertEquals(0, new BigDecimal("1100.00").compareTo(s.get("retail_value").decimalValue()));

    Resp manager = new Resp();
    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.MANAGER), Map.of()),
            manager.mock,
            ORG,
            "/inventory-valuation");
    JsonNode m = json(manager);
    assertEquals(0, new BigDecimal("640.00").compareTo(m.get("cost_value").decimalValue()));
    assertEquals(1, m.get("uncosted_products").asLong());
    assertEquals(5, m.get("uncosted_units").asLong());
  }

  @Test
  void inventoryValuation_noCostedProduct_costValueAbsentEvenForManager() throws IOException {
    ReportService service = stubbedService();
    when(service.inventoryValuation(any()))
        .thenReturn(
            new InventoryValuationReport(NOW, new InventoryValuation(2, 7, BigDecimal.TEN, 0)));
    Resp manager = new Resp();
    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.MANAGER), Map.of()),
            manager.mock,
            ORG,
            "/inventory-valuation");
    JsonNode m = json(manager);
    assertFalse(m.has("cost_value"), "no costed product → no key, never 0");
    assertEquals(2, m.get("uncosted_products").asLong());
    assertEquals(7, m.get("uncosted_units").asLong());
  }

  @Test
  void everyReport_readableByViewer() throws IOException {
    ReportService service = stubbedService();
    for (String report : ALL_REPORTS) {
      Resp resp = new Resp();
      handler(service)
          .handle(
              "GET", reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of()), resp.mock, ORG, "/" + report);
      assertEquals(200, resp.status, report + " must be readable by VIEWER");
    }
  }

  @Test
  void revenue_unauthenticated_is401_serviceNeverCalled() throws IOException {
    ReportService service = stubbedService();
    Resp resp = new Resp();

    handler(service).handle("GET", reqWith(null, Map.of()), resp.mock, ORG, "/revenue");

    assertEquals(401, resp.status);
    verify(service, never()).revenue(any(), any(), any(), any());
  }

  @Test
  void revenue_nonMember_is403_serviceNeverCalled() throws IOException {
    ReportService service = stubbedService();
    Resp resp = new Resp();

    // A caller who holds a role in a DIFFERENT org is not a member of ORG → 403.
    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(OTHER_ORG, OrgRole.OWNER), Map.of()),
            resp.mock,
            ORG,
            "/revenue");

    assertEquals(403, resp.status);
    verify(service, never()).revenue(any(), any(), any(), any());
  }

  @Test
  void mutatingVerbs_onKnownReport_are405_serviceNeverCalled() throws IOException {
    ReportService service = stubbedService();
    for (String method : List.of("POST", "PUT", "DELETE", "PATCH")) {
      Resp resp = new Resp();
      handler(service)
          .handle(
              method, reqWith(ctxWith(ORG, OrgRole.OWNER), Map.of()), resp.mock, ORG, "/revenue");
      assertEquals(405, resp.status, method + " on a report must be 405");
    }
    verify(service, never()).revenue(any(), any(), any(), any());
  }

  @Test
  void unknownReport_is404() throws IOException {
    ReportService service = stubbedService();
    Resp resp = new Resp();

    handler(service)
        .handle("GET", reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of()), resp.mock, ORG, "/margins");

    assertEquals(404, resp.status);
  }

  @Test
  void bareReportsRoot_is404() throws IOException {
    ReportService service = stubbedService();
    Resp resp = new Resp();

    handler(service)
        .handle("GET", reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of()), resp.mock, ORG, "");

    assertEquals(404, resp.status);
  }

  @Test
  void serviceValidationException_surfacesAs400() throws IOException {
    ReportService service = stubbedService();
    when(service.revenue(any(), any(), any(), any()))
        .thenThrow(new ValidationException("'bucket' must be one of day, week, month"));
    Resp resp = new Resp();

    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of("bucket", "hour")),
            resp.mock,
            ORG,
            "/revenue");

    assertEquals(400, resp.status, "a bad param must surface as 400, not 500");
  }

  // harness (mirrors MoneyReadsHandlerAuthTest)

  private static final class Resp {
    final HttpServletResponse mock;
    final ByteArrayOutputStream body = new ByteArrayOutputStream();
    int status = 200;

    Resp() throws IOException {
      mock = Mockito.mock(HttpServletResponse.class);
      Mockito.doAnswer(
              inv -> {
                status = inv.getArgument(0);
                return null;
              })
          .when(mock)
          .setStatus(Mockito.anyInt());
      when(mock.getOutputStream())
          .thenReturn(
              new ServletOutputStream() {
                @Override
                public void write(int b) {
                  body.write(b);
                }

                @Override
                public boolean isReady() {
                  return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {}
              });
    }
  }

  private HttpServletRequest reqWith(SecurityContext ctx, Map<String, String> params) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    when(req.getParameter(Mockito.anyString()))
        .thenAnswer(inv -> params.get(inv.<String>getArgument(0)));
    return req;
  }
}
