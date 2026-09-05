package com.loai.inventory.api.invoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.InvoiceHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.InvoiceAdminService;
import com.loai.inventory.service.InvoiceAdminService.InvoiceView;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Runtime auth verification for {@link InvoiceHandler}: drives {@code handle(...)} directly with a
 * {@link SecurityContext} attached to the request (the same attribute {@code JwtAuthFilter} sets
 * and {@code AuthzHelper.requireOrgAccess} reads), mocking the service. The existing IT bypasses
 * the handler entirely, so this is the only place the MANAGER/VIEWER gate is exercised at runtime.
 *
 * <ul>
 *   <li>{@code POST /invoices/{id}/void} and {@code .../reissue} require MANAGER: VIEWER/STAFF →
 *       403 and the service is NEVER called; MANAGER → 2xx and the service IS called.
 *   <li>{@code GET /invoices/{id}} requires VIEWER: VIEWER → 200.
 * </ul>
 */
class InvoiceHandlerAuthTest {

  static {
    // InvoiceAdminService is a final class; on Java 25 the Mockito inline mock-maker needs
    // ByteBuddy's
    // experimental mode to instrument it. Set before any mock is created (and before ByteBuddy
    // loads)
    // so the suite passes under a plain `mvn test` with no special argLine.
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID INVOICE = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  private SecurityContext ctxWith(OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(ORG, Set.of(role)), Set.of(), 0);
  }

  private SalesInvoice anInvoice() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    SalesInvoice inv =
        SalesInvoice.createDraft(
            INVOICE,
            ORG,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO, // shipping
            BigDecimal.ZERO, // discount
            "EGP",
            "Snapshot",
            null,
            null,
            null,
            now);
    inv.issue("INV-2026-0001", now);
    return inv;
  }

  // ─────────────── harness ───────────────

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

  private HttpServletRequest reqWith(SecurityContext ctx, String jsonBody) throws IOException {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    byte[] bytes = (jsonBody == null ? "" : jsonBody).getBytes(StandardCharsets.UTF_8);
    when(req.getInputStream())
        .thenReturn(
            new ServletInputStream() {
              final ByteArrayInputStream in = new ByteArrayInputStream(bytes);

              @Override
              public int read() {
                return in.read();
              }

              @Override
              public boolean isFinished() {
                return in.available() == 0;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener listener) {}
            });
    return req;
  }

  // ─────────────── void ───────────────

  @Test
  void void_forbiddenForViewer_serviceNeverCalled() throws IOException {
    InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
    InvoiceHandler handler =
        new InvoiceHandler(
            service, null, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle(
        "POST",
        reqWith(ctxWith(OrgRole.VIEWER), "{\"reason\":\"x\"}"),
        resp.mock,
        ORG,
        "/" + INVOICE + "/void");

    assertEquals(403, resp.status, "VIEWER must be forbidden from void");
    verify(service, never()).voidInvoice(any(), any(), anyString());
  }

  @Test
  void void_forbiddenForStaff_serviceNeverCalled() throws IOException {
    InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
    InvoiceHandler handler =
        new InvoiceHandler(
            service, null, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle(
        "POST",
        reqWith(ctxWith(OrgRole.STAFF), "{\"reason\":\"x\"}"),
        resp.mock,
        ORG,
        "/" + INVOICE + "/void");

    assertEquals(403, resp.status, "STAFF must be forbidden from void (requires MANAGER)");
    verify(service, never()).voidInvoice(any(), any(), anyString());
  }

  @Test
  void void_allowedForManager_serviceCalled() throws IOException {
    InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
    when(service.voidInvoice(eq(ORG), eq(INVOICE), anyString()))
        .thenReturn(new InvoiceView(anInvoice(), List.of()));
    InvoiceHandler handler =
        new InvoiceHandler(
            service, null, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle(
        "POST",
        reqWith(ctxWith(OrgRole.MANAGER), "{\"reason\":\"issued in error\"}"),
        resp.mock,
        ORG,
        "/" + INVOICE + "/void");

    assertEquals(200, resp.status, "MANAGER void must succeed");
    verify(service).voidInvoice(eq(ORG), eq(INVOICE), eq("issued in error"));
  }

  // ─────────────── reissue ───────────────

  @Test
  void reissue_forbiddenForViewer_serviceNeverCalled() throws IOException {
    InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
    InvoiceHandler handler =
        new InvoiceHandler(
            service, null, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    String body =
        "{\"reason\":\"fix\",\"lines\":[{\"description\":\"d\",\"quantity\":1,\"unit_price\":\"1.00\"}]}";
    handler.handle(
        "POST", reqWith(ctxWith(OrgRole.VIEWER), body), resp.mock, ORG, "/" + INVOICE + "/reissue");

    assertEquals(403, resp.status, "VIEWER must be forbidden from reissue");
    verify(service, never()).reissue(any(), any(), anyString(), any());
  }

  @Test
  void reissue_forbiddenForStaff_serviceNeverCalled() throws IOException {
    InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
    InvoiceHandler handler =
        new InvoiceHandler(
            service, null, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    String body =
        "{\"reason\":\"fix\",\"lines\":[{\"description\":\"d\",\"quantity\":1,\"unit_price\":\"1.00\"}]}";
    handler.handle(
        "POST", reqWith(ctxWith(OrgRole.STAFF), body), resp.mock, ORG, "/" + INVOICE + "/reissue");

    assertEquals(403, resp.status, "STAFF must be forbidden from reissue (requires MANAGER)");
    verify(service, never()).reissue(any(), any(), anyString(), any());
  }

  @Test
  void reissue_allowedForManager_serviceCalled() throws IOException {
    InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
    when(service.reissue(eq(ORG), eq(INVOICE), anyString(), any()))
        .thenReturn(
            new com.loai.inventory.service.InvoiceService.Issued(
                anInvoice(), List.of(), List.of(), List.of()));
    InvoiceHandler handler =
        new InvoiceHandler(
            service, null, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    String body =
        "{\"reason\":\"fix\",\"lines\":[{\"description\":\"d\",\"quantity\":1,\"unit_price\":\"1.00\"}]}";
    handler.handle(
        "POST",
        reqWith(ctxWith(OrgRole.MANAGER), body),
        resp.mock,
        ORG,
        "/" + INVOICE + "/reissue");

    assertEquals(201, resp.status, "MANAGER reissue must succeed (201 Created)");
    verify(service).reissue(eq(ORG), eq(INVOICE), eq("fix"), any());
  }

  // ─────────────── get ───────────────

  @Test
  void get_allowedForViewer_serviceCalled() throws IOException {
    InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
    when(service.get(eq(ORG), eq(INVOICE))).thenReturn(new InvoiceView(anInvoice(), List.of()));
    InvoiceHandler handler =
        new InvoiceHandler(
            service, null, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle("GET", reqWith(ctxWith(OrgRole.VIEWER), null), resp.mock, ORG, "/" + INVOICE);

    assertEquals(200, resp.status, "VIEWER must be allowed to GET an invoice");
    verify(service).get(eq(ORG), eq(INVOICE));
  }

  // list

  @Test
  void list_allowedForViewer_serviceCalled() throws IOException {
    InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
    when(service.list(eq(ORG), any(), Mockito.anyInt(), Mockito.anyInt()))
        .thenReturn(
            new InvoiceAdminService.InvoicePage(
                List.of(), 0, com.loai.inventory.domain.model.InvoiceListStats.empty()));
    InvoiceHandler handler =
        new InvoiceHandler(
            service, null, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle("GET", reqWith(ctxWith(OrgRole.VIEWER), null), resp.mock, ORG, "/");

    assertEquals(200, resp.status, "VIEWER must be allowed to list invoices");
    verify(service).list(eq(ORG), any(), Mockito.anyInt(), Mockito.anyInt());
  }

  @Test
  void list_unknownStatus_is400_serviceNeverCalled() throws IOException {
    InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
    HttpServletRequest req = reqWith(ctxWith(OrgRole.VIEWER), null);
    when(req.getParameter("status")).thenReturn("BOGUS");
    InvoiceHandler handler =
        new InvoiceHandler(
            service, null, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle("GET", req, resp.mock, ORG, "/");

    assertEquals(400, resp.status, "an unknown status filter must be a 400");
    verify(service, never()).list(any(), any(), Mockito.anyInt(), Mockito.anyInt());
  }

  @Test
  void list_filtersReachTheService_asOneFilter() throws IOException {
    InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
    when(service.list(eq(ORG), any(), Mockito.anyInt(), Mockito.anyInt()))
        .thenReturn(
            new InvoiceAdminService.InvoicePage(
                List.of(), 0, com.loai.inventory.domain.model.InvoiceListStats.empty()));
    HttpServletRequest req = reqWith(ctxWith(OrgRole.VIEWER), null);
    when(req.getParameter("status")).thenReturn("issued");
    when(req.getParameter("q")).thenReturn("  nadia ");
    when(req.getParameter("from")).thenReturn("2026-07-31T21:00:00Z");
    when(req.getParameter("to")).thenReturn("2026-08-31T21:00:00Z");
    when(req.getParameter("paid")).thenReturn("partial");
    when(req.getParameter("min")).thenReturn("500");
    when(req.getParameter("max")).thenReturn("2500.50");
    InvoiceHandler handler =
        new InvoiceHandler(
            service, null, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle("GET", req, resp.mock, ORG, "/");

    assertEquals(200, resp.status);
    var captor =
        org.mockito.ArgumentCaptor.forClass(
            com.loai.inventory.domain.model.InvoiceListFilter.class);
    verify(service).list(eq(ORG), captor.capture(), Mockito.anyInt(), Mockito.anyInt());
    var f = captor.getValue();
    assertEquals(com.loai.inventory.domain.model.InvoiceStatus.ISSUED, f.status());
    assertEquals("nadia", f.q(), "q is trimmed");
    assertEquals(java.time.OffsetDateTime.parse("2026-07-31T21:00:00Z"), f.issuedFrom());
    assertEquals(java.time.OffsetDateTime.parse("2026-08-31T21:00:00Z"), f.issuedTo());
    assertEquals(com.loai.inventory.domain.model.InvoiceListFilter.PaidState.PARTIAL, f.paid());
    assertEquals(0, new java.math.BigDecimal("500").compareTo(f.minTotal()));
    assertEquals(0, new java.math.BigDecimal("2500.50").compareTo(f.maxTotal()));
  }

  @Test
  void list_badFilterValues_are400_serviceNeverCalled() throws IOException {
    record Bad(String param, String value, String why) {}
    List<Bad> cases =
        List.of(
            new Bad("from", "2026-08-01", "a bare date is not an ISO-8601 date-time"),
            new Bad("to", "yesterday", "prose is not a date-time"),
            new Bad("paid", "half", "paid is none|partial"),
            new Bad("min", "abc", "min must be a number"),
            new Bad("max", "-1", "max must not be negative"));
    for (Bad bad : cases) {
      InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
      HttpServletRequest req = reqWith(ctxWith(OrgRole.VIEWER), null);
      when(req.getParameter(bad.param())).thenReturn(bad.value());
      InvoiceHandler handler =
          new InvoiceHandler(
              service, null, com.loai.inventory.api.config.ObjectMapperProvider.build());
      Resp resp = new Resp();

      handler.handle("GET", req, resp.mock, ORG, "/");

      assertEquals(400, resp.status, bad.why());
      verify(service, never()).list(any(), any(), Mockito.anyInt(), Mockito.anyInt());
    }
  }

  @Test
  void list_invertedWindowOrBounds_are400() throws IOException {
    for (String[] pair :
        List.of(
            new String[] {"from", "2026-09-01T00:00:00Z", "to", "2026-08-01T00:00:00Z"},
            new String[] {"min", "900", "max", "100"})) {
      InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
      HttpServletRequest req = reqWith(ctxWith(OrgRole.VIEWER), null);
      when(req.getParameter(pair[0])).thenReturn(pair[1]);
      when(req.getParameter(pair[2])).thenReturn(pair[3]);
      InvoiceHandler handler =
          new InvoiceHandler(
              service, null, com.loai.inventory.api.config.ObjectMapperProvider.build());
      Resp resp = new Resp();

      handler.handle("GET", req, resp.mock, ORG, "/");

      assertEquals(400, resp.status, pair[0] + " > " + pair[2] + " must be a 400");
      verify(service, never()).list(any(), any(), Mockito.anyInt(), Mockito.anyInt());
    }
  }

  @Test
  void statusCounts_allowedForViewer_serviceCalled() throws IOException {
    InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
    when(service.statusCounts(ORG))
        .thenReturn(
            new InvoiceAdminService.InvoiceStatusCounts(
                java.util.Map.of(com.loai.inventory.domain.model.InvoiceStatus.ISSUED, 2L), 2));
    InvoiceHandler handler =
        new InvoiceHandler(
            service, null, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle("GET", reqWith(ctxWith(OrgRole.VIEWER), null), resp.mock, ORG, "/status-counts");

    assertEquals(200, resp.status, "VIEWER must be allowed to read the tab counts");
    verify(service).statusCounts(ORG);
    verify(service, never()).list(any(), any(), Mockito.anyInt(), Mockito.anyInt());
  }

  @Test
  void statusCounts_post_is405() throws IOException {
    InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
    InvoiceHandler handler =
        new InvoiceHandler(
            service, null, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle(
        "POST", reqWith(ctxWith(OrgRole.MANAGER), null), resp.mock, ORG, "/status-counts");

    assertEquals(405, resp.status);
    verify(service, never()).statusCounts(any());
  }

  @Test
  void postAtRoot_is405() throws IOException {
    InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
    InvoiceHandler handler =
        new InvoiceHandler(
            service, null, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle("POST", reqWith(ctxWith(OrgRole.MANAGER), "{}"), resp.mock, ORG, "/");

    assertEquals(
        405, resp.status, "there is no POST /invoices — invoices are issued as a side effect");
  }

  @Test
  void void_unauthenticated_is401_serviceNeverCalled() throws IOException {
    InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
    InvoiceHandler handler =
        new InvoiceHandler(
            service, null, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    // No SecurityContext attribute → requireAuth throws AuthenticationException (401).
    handler.handle(
        "POST", reqWith(null, "{\"reason\":\"x\"}"), resp.mock, ORG, "/" + INVOICE + "/void");

    assertTrue(resp.status == 401, "missing auth must be 401, was " + resp.status);
    verify(service, never()).voidInvoice(any(), any(), anyString());
  }
}
