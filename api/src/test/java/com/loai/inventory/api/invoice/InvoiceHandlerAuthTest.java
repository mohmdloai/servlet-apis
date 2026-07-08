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
            BigDecimal.ZERO,
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
        new InvoiceHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
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
        new InvoiceHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
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
        new InvoiceHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
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
        new InvoiceHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
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
        new InvoiceHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
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
        new InvoiceHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
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
        new InvoiceHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
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
        .thenReturn(new InvoiceAdminService.InvoicePage(List.of(), 0));
    InvoiceHandler handler =
        new InvoiceHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
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
        new InvoiceHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle("GET", req, resp.mock, ORG, "/");

    assertEquals(400, resp.status, "an unknown status filter must be a 400");
    verify(service, never()).list(any(), any(), Mockito.anyInt(), Mockito.anyInt());
  }

  @Test
  void postAtRoot_is405() throws IOException {
    InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
    InvoiceHandler handler =
        new InvoiceHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle("POST", reqWith(ctxWith(OrgRole.MANAGER), "{}"), resp.mock, ORG, "/");

    assertEquals(
        405, resp.status, "there is no POST /invoices — invoices are issued as a side effect");
  }

  @Test
  void void_unauthenticated_is401_serviceNeverCalled() throws IOException {
    InvoiceAdminService service = Mockito.mock(InvoiceAdminService.class);
    InvoiceHandler handler =
        new InvoiceHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    // No SecurityContext attribute → requireAuth throws AuthenticationException (401).
    handler.handle(
        "POST", reqWith(null, "{\"reason\":\"x\"}"), resp.mock, ORG, "/" + INVOICE + "/void");

    assertTrue(resp.status == 401, "missing auth must be 401, was " + resp.status);
    verify(service, never()).voidInvoice(any(), any(), anyString());
  }
}
