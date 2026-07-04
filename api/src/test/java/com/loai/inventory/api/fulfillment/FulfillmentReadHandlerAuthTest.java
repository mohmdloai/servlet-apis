package com.loai.inventory.api.fulfillment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.FulfillmentHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.Fulfillment;
import com.loai.inventory.domain.model.FulfillmentStatus;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.FulfillmentService.FulfillmentPage;
import com.loai.inventory.service.FulfillmentService.FulfillmentView;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Runtime auth + query-param verification for the fulfillment reads ({@code
 * stories/fulfillment_reads.md}): reads require VIEWER (non-member 403 / anon 401, service never
 * called); an unknown {@code status} or non-integer paging param is a 400 that never reaches the
 * service; page/size are clamped in the handler so the envelope echoes what was served.
 */
class FulfillmentReadHandlerAuthTest {

  static {
    // FulfillmentService is final; the Mockito inline mock-maker needs ByteBuddy experimental mode
    // on Java 25 (mirrors FailedFulfillmentHandlerAuthTest).
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID FULFILLMENT = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  private SecurityContext ctxWith(UUID orgId, OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(orgId, Set.of(role)), Set.of(), 0);
  }

  private FulfillmentView aView() {
    Fulfillment f =
        Fulfillment.createPending(
            FULFILLMENT,
            ORG,
            UUID.randomUUID(),
            null,
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));
    return new FulfillmentView(f, List.of());
  }

  private FulfillmentHandler handler(FulfillmentService service) {
    return new FulfillmentHandler(
        service, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  @Test
  void list_allowedForViewer_statusParsedAndForwarded() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    when(service.list(eq(ORG), eq(FulfillmentStatus.PENDING), anyInt(), anyInt()))
        .thenReturn(new FulfillmentPage(List.of(aView()), 1));
    Resp resp = new Resp();

    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of("status", "pending")),
            resp.mock,
            ORG,
            "");

    assertEquals(200, resp.status, "VIEWER list must succeed");
    // Case-insensitive parse, clamped defaults (page 0, size DEFAULT_PAGE_SIZE).
    verify(service).list(ORG, FulfillmentStatus.PENDING, 0, FulfillmentService.DEFAULT_PAGE_SIZE);
  }

  @Test
  void list_unknownStatus_is400_serviceNeverCalled() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of("status", "TELEPORTED")),
            resp.mock,
            ORG,
            "");

    assertEquals(400, resp.status, "unknown status must fail loudly");
    verify(service, never()).list(any(), any(), anyInt(), anyInt());
  }

  @Test
  void list_nonIntegerPage_is400_serviceNeverCalled() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of("page", "one")),
            resp.mock,
            ORG,
            "");

    assertEquals(400, resp.status);
    verify(service, never()).list(any(), any(), anyInt(), anyInt());
  }

  @Test
  void get_allowedForViewer_serviceCalled() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    when(service.get(ORG, FULFILLMENT)).thenReturn(aView());
    Resp resp = new Resp();

    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of()),
            resp.mock,
            ORG,
            "/" + FULFILLMENT);

    assertEquals(200, resp.status, "VIEWER detail must succeed");
    verify(service).get(ORG, FULFILLMENT);
  }

  @Test
  void get_malformedId_is400_serviceNeverCalled() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "GET", reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of()), resp.mock, ORG, "/not-a-uuid");

    assertEquals(400, resp.status);
    verify(service, never()).get(any(), any());
  }

  @Test
  void reads_nonMember_forbidden_serviceNeverCalled() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    Resp list = new Resp();
    Resp get = new Resp();

    FulfillmentHandler handler = handler(service);
    handler.handle(
        "GET", reqWith(ctxWith(UUID.randomUUID(), OrgRole.OWNER), Map.of()), list.mock, ORG, "");
    handler.handle(
        "GET",
        reqWith(ctxWith(UUID.randomUUID(), OrgRole.OWNER), Map.of()),
        get.mock,
        ORG,
        "/" + FULFILLMENT);

    assertEquals(403, list.status, "non-member list must be forbidden");
    assertEquals(403, get.status, "non-member detail must be forbidden");
    verify(service, never()).list(any(), any(), anyInt(), anyInt());
    verify(service, never()).get(any(), any());
  }

  @Test
  void reads_unauthenticated_is401_serviceNeverCalled() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    Resp resp = new Resp();

    handler(service).handle("GET", reqWith(null, Map.of()), resp.mock, ORG, "");

    assertEquals(401, resp.status, "missing auth must be 401");
    verify(service, never()).list(any(), any(), anyInt(), anyInt());
  }

  // ─────────────── harness (mirrors FailedFulfillmentHandlerAuthTest) ───────────────

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
    params.forEach((k, v) -> when(req.getParameter(k)).thenReturn(v));
    return req;
  }
}
