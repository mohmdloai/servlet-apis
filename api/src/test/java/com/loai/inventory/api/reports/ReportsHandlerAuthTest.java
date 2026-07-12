package com.loai.inventory.api.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.ReportsHandler;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.report.InventoryValuation;
import com.loai.inventory.service.ReportService;
import com.loai.inventory.service.ReportService.ArAgingReport;
import com.loai.inventory.service.ReportService.InventoryValuationReport;
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
 * stories/reporting_reads.md}): all five reports are VIEWER reads; anon → 401; a non-member → 403;
 * any mutating verb on a known report → 405; an unknown report name → 404; a {@link
 * ValidationException} from the service surfaces as 400.
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
            new InventoryValuationReport(NOW, new InventoryValuation(0, 0, BigDecimal.ZERO, 0)));
    return service;
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
