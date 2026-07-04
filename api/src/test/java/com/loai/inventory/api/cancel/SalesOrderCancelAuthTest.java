package com.loai.inventory.api.cancel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.SalesOrderHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.OrderCancellationService;
import com.loai.inventory.service.OrderCancellationService.CancelResult;
import com.loai.inventory.service.SalesOrderService;
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
 * Runtime auth verification for {@link SalesOrderHandler}'s cancel route ({@code POST
 * /sales-orders/{id}/cancel}): money can move (a direct refund), so it requires MANAGER —
 * VIEWER/STAFF get 403 and the service is never called; MANAGER succeeds.
 */
class SalesOrderCancelAuthTest {

  static {
    // OrderCancellationService is final; the Mockito inline mock-maker needs ByteBuddy experimental
    // mode on Java 25 (mirrors InvoiceHandlerAuthTest).
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID ORDER = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";
  private static final String BODY = "{\"reason\":\"changed mind\"}";

  private SecurityContext ctxWith(OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(ORG, Set.of(role)), Set.of(), 0);
  }

  private CancelResult aCancelResult() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    SalesOrder order =
        SalesOrder.createDraft(
            ORDER,
            ORG,
            UUID.randomUUID(),
            "SO-2026-00001",
            OrderChannel.ONLINE,
            "EGP",
            "idem",
            now);
    order.cancel(now);
    return new CancelResult(order, 1, List.of(), BigDecimal.ZERO);
  }

  private SalesOrderHandler handler(OrderCancellationService cancellation) {
    return new SalesOrderHandler(
        Mockito.mock(SalesOrderService.class),
        cancellation,
        Mockito.mock(com.loai.inventory.service.PaymentService.class),
        com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  @Test
  void cancel_forbiddenForViewer_serviceNeverCalled() throws IOException {
    OrderCancellationService service = Mockito.mock(OrderCancellationService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.VIEWER), BODY),
            resp.mock,
            ORG,
            "/" + ORDER + "/cancel");

    assertEquals(403, resp.status, "VIEWER must be forbidden from cancel");
    verify(service, never()).cancel(any(), any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void cancel_forbiddenForStaff_serviceNeverCalled() throws IOException {
    OrderCancellationService service = Mockito.mock(OrderCancellationService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST", reqWith(ctxWith(OrgRole.STAFF), BODY), resp.mock, ORG, "/" + ORDER + "/cancel");

    assertEquals(403, resp.status, "STAFF must be forbidden from cancel (requires MANAGER)");
    verify(service, never()).cancel(any(), any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void cancel_allowedForManager_serviceCalled() throws IOException {
    OrderCancellationService service = Mockito.mock(OrderCancellationService.class);
    when(service.cancel(eq(ORG), eq(ORDER), any(), any(), any(), anyBoolean()))
        .thenReturn(aCancelResult());
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.MANAGER), BODY),
            resp.mock,
            ORG,
            "/" + ORDER + "/cancel");

    assertEquals(200, resp.status, "MANAGER cancel must succeed");
    verify(service).cancel(eq(ORG), eq(ORDER), any(), any(), any(), anyBoolean());
  }

  @Test
  void cancel_unauthenticated_is401_serviceNeverCalled() throws IOException {
    OrderCancellationService service = Mockito.mock(OrderCancellationService.class);
    Resp resp = new Resp();

    handler(service).handle("POST", reqWith(null, BODY), resp.mock, ORG, "/" + ORDER + "/cancel");

    assertTrue(resp.status == 401, "missing auth must be 401, was " + resp.status);
    verify(service, never()).cancel(any(), any(), any(), any(), any(), anyBoolean());
  }

  // ─────────────── harness (mirrors InvoiceHandlerAuthTest) ───────────────

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
}
