package com.loai.inventory.api.fulfillment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.FulfillmentHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.Fulfillment;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.FulfillmentService.FailedRefundResult;
import com.loai.inventory.service.FulfillmentService.FulfillmentView;
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
 * Runtime auth verification for the failed-fulfillment routes on {@link FulfillmentHandler}: {@code
 * fail} requires STAFF, while the money-moving {@code refund} and stock-moving {@code return} /
 * {@code replace} require MANAGER (lower roles get 403 and the service is never called). Also pins
 * malformed-JSON → 400 (not 500).
 */
class FailedFulfillmentHandlerAuthTest {

  static {
    // FulfillmentService is final; the Mockito inline mock-maker needs ByteBuddy experimental mode
    // on Java 25 (mirrors PaymentDisputeHandlerAuthTest).
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID FULFILLMENT = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  private SecurityContext ctxWith(OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(ORG, Set.of(role)), Set.of(), 0);
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

  private FailedRefundResult aRefundResult() {
    return new FailedRefundResult(aView().fulfillment(), List.of(), BigDecimal.ZERO);
  }

  private FulfillmentHandler handler(FulfillmentService service) {
    return new FulfillmentHandler(
        service, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  @Test
  void fail_forbiddenForViewer_serviceNeverCalled() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.VIEWER), "{\"reason\":\"lost\"}"),
            resp.mock,
            ORG,
            "/" + FULFILLMENT + "/fail");

    assertEquals(403, resp.status, "VIEWER must be forbidden from fail (requires STAFF)");
    verify(service, never()).markFailed(any(), any(), any());
  }

  @Test
  void fail_allowedForStaff_serviceCalled() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    when(service.markFailed(eq(ORG), eq(FULFILLMENT), any())).thenReturn(aView());
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.STAFF), "{\"reason\":\"lost\"}"),
            resp.mock,
            ORG,
            "/" + FULFILLMENT + "/fail");

    assertEquals(200, resp.status, "STAFF fail must succeed");
    verify(service).markFailed(eq(ORG), eq(FULFILLMENT), any());
  }

  @Test
  void refund_forbiddenForStaff_serviceNeverCalled() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.STAFF), ""),
            resp.mock,
            ORG,
            "/" + FULFILLMENT + "/refund");

    assertEquals(403, resp.status, "STAFF must be forbidden from refund (requires MANAGER)");
    verify(service, never()).refundFailed(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void refund_allowedForManager_serviceCalled() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    when(service.refundFailed(eq(ORG), eq(FULFILLMENT), any(), any(), anyBoolean()))
        .thenReturn(aRefundResult());
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.MANAGER), ""),
            resp.mock,
            ORG,
            "/" + FULFILLMENT + "/refund");

    assertEquals(200, resp.status, "MANAGER refund must succeed");
    verify(service).refundFailed(eq(ORG), eq(FULFILLMENT), any(), any(), anyBoolean());
  }

  @Test
  void return_forbiddenForStaff_serviceNeverCalled() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.STAFF), ""),
            resp.mock,
            ORG,
            "/" + FULFILLMENT + "/return");

    assertEquals(403, resp.status, "STAFF must be forbidden from return (requires MANAGER)");
    verify(service, never()).recordReturn(any(), any(), any());
  }

  @Test
  void return_allowedForManager_serviceCalled() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    when(service.recordReturn(eq(ORG), eq(FULFILLMENT), any())).thenReturn(aView());
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.MANAGER), ""),
            resp.mock,
            ORG,
            "/" + FULFILLMENT + "/return");

    assertEquals(200, resp.status, "MANAGER return must succeed");
    verify(service).recordReturn(eq(ORG), eq(FULFILLMENT), any());
  }

  @Test
  void refund_unauthenticated_is401_serviceNeverCalled() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    Resp resp = new Resp();

    handler(service)
        .handle("POST", reqWith(null, ""), resp.mock, ORG, "/" + FULFILLMENT + "/refund");

    assertTrue(resp.status == 401, "missing auth must be 401, was " + resp.status);
    verify(service, never()).refundFailed(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void refund_wrongVerb_is405_serviceNeverCalled() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(OrgRole.MANAGER), ""),
            resp.mock,
            ORG,
            "/" + FULFILLMENT + "/refund");

    assertEquals(405, resp.status, "GET on /refund must be 405");
    verify(service, never()).refundFailed(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void replace_forbiddenForStaff_serviceNeverCalled() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.STAFF), ""),
            resp.mock,
            ORG,
            "/" + FULFILLMENT + "/replace");

    assertEquals(403, resp.status, "STAFF must be forbidden from replace (requires MANAGER)");
    verify(service, never()).replaceFailed(any(), any(), any(), any(), any(), any());
  }

  @Test
  void replace_allowedForManager_serviceCalled() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    when(service.replaceFailed(eq(ORG), eq(FULFILLMENT), any(), any(), any(), any()))
        .thenReturn(aView());
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.MANAGER), ""),
            resp.mock,
            ORG,
            "/" + FULFILLMENT + "/replace");

    assertEquals(201, resp.status, "MANAGER replace must succeed (201)");
    verify(service).replaceFailed(eq(ORG), eq(FULFILLMENT), any(), any(), any(), any());
  }

  @Test
  void fail_malformedJsonBody_is400_notSwallowedNor500() throws IOException {
    FulfillmentService service = Mockito.mock(FulfillmentService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.STAFF), "{ not json"),
            resp.mock,
            ORG,
            "/" + FULFILLMENT + "/fail");

    assertEquals(400, resp.status, "malformed JSON must be 400 (not 500, not a swallowed null)");
    verify(service, never()).markFailed(any(), any(), any());
  }

  // ─────────────── harness (mirrors PaymentDisputeHandlerAuthTest) ───────────────

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
    when(req.getContentLength()).thenReturn(bytes.length);
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
}
