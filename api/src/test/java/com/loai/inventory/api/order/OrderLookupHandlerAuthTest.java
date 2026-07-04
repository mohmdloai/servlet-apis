package com.loai.inventory.api.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.Placed;
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
 * Runtime auth + param verification for {@code GET /sales-orders?order_number=} ({@code
 * stories/lookup_order_by_number.md}): a read, so VIEWER suffices; non-member 403 / anon 401; the
 * bare {@code GET} without the param is a 400 that never reaches the service (the route is reserved
 * for the future list slice).
 */
class OrderLookupHandlerAuthTest {

  static {
    // PaymentService is final; the Mockito inline mock-maker needs ByteBuddy experimental mode on
    // Java 25 (mirrors SalesOrderCancelAuthTest).
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final String NUMBER = "SO-2026-00001";
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  private SecurityContext ctxWith(UUID orgId, OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(orgId, Set.of(role)), Set.of(), 0);
  }

  private Placed aPlaced() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    SalesOrder order =
        SalesOrder.createDraft(
            UUID.randomUUID(),
            ORG,
            UUID.randomUUID(),
            NUMBER,
            OrderChannel.ONLINE,
            "EGP",
            "idem",
            now);
    return new Placed(order, List.of(), null);
  }

  private SalesOrderHandler handler(SalesOrderService service) {
    return new SalesOrderHandler(
        service,
        Mockito.mock(OrderCancellationService.class),
        Mockito.mock(PaymentService.class),
        com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  @Test
  void lookup_allowedForViewer_serviceCalled() throws IOException {
    SalesOrderService service = Mockito.mock(SalesOrderService.class);
    when(service.getByNumber(ORG, NUMBER)).thenReturn(aPlaced());
    Resp resp = new Resp();

    handler(service)
        .handle("GET", reqWith(ctxWith(ORG, OrgRole.VIEWER), NUMBER), resp.mock, ORG, "");

    assertEquals(200, resp.status, "VIEWER lookup must succeed");
    verify(service).getByNumber(ORG, NUMBER);
  }

  @Test
  void lookup_missingParam_is400_serviceNeverCalled() throws IOException {
    SalesOrderService service = Mockito.mock(SalesOrderService.class);
    Resp resp = new Resp();

    handler(service).handle("GET", reqWith(ctxWith(ORG, OrgRole.VIEWER), null), resp.mock, ORG, "");

    assertEquals(400, resp.status, "bare GET must 400 (route reserved for the list slice)");
    verify(service, never()).getByNumber(any(), anyString());
  }

  @Test
  void lookup_nonMember_forbidden_serviceNeverCalled() throws IOException {
    SalesOrderService service = Mockito.mock(SalesOrderService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "GET", reqWith(ctxWith(UUID.randomUUID(), OrgRole.OWNER), NUMBER), resp.mock, ORG, "");

    assertEquals(403, resp.status, "non-member must be forbidden");
    verify(service, never()).getByNumber(any(), anyString());
  }

  @Test
  void lookup_unauthenticated_is401_serviceNeverCalled() throws IOException {
    SalesOrderService service = Mockito.mock(SalesOrderService.class);
    Resp resp = new Resp();

    handler(service).handle("GET", reqWith(null, NUMBER), resp.mock, ORG, "");

    assertEquals(401, resp.status, "missing auth must be 401");
    verify(service, never()).getByNumber(any(), anyString());
  }

  // ─────────────── harness (mirrors SalesOrderCancelAuthTest) ───────────────

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

  private HttpServletRequest reqWith(SecurityContext ctx, String orderNumber) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    when(req.getParameter("order_number")).thenReturn(orderNumber);
    return req;
  }
}
