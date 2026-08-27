package com.loai.inventory.api.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.SalesOrderHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.OrderCancellationService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.OrderStatusCounts;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Runtime auth + routing verification for {@code GET /sales-orders/status-counts} ({@code
 * stories/order_status_counts.md}): a read, so VIEWER suffices; non-member 403 / anon 401; non-GET
 * on the fixed segment is a clean 405 (never the POST fallthrough's 400); and the segment must
 * match before the {@code {id}} UUID parse — a 400 "invalid id" here is the routing regression.
 */
class OrderStatusCountsHandlerAuthTest {

  static {
    // PaymentService is final; the Mockito inline mock-maker needs ByteBuddy experimental mode on
    // Java 25 (mirrors SalesOrderCancelAuthTest).
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  private SecurityContext ctxWith(UUID orgId, OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(orgId, Set.of(role)), Set.of(), 0);
  }

  private OrderStatusCounts someCounts() {
    Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
    for (OrderStatus status : OrderStatus.values()) {
      counts.put(status, 0L);
    }
    counts.put(OrderStatus.PENDING_PAYMENT, 2L);
    return new OrderStatusCounts(counts, 2L);
  }

  private SalesOrderHandler handler(SalesOrderService service) {
    return new SalesOrderHandler(
        service,
        Mockito.mock(OrderCancellationService.class),
        Mockito.mock(PaymentService.class),
        Mockito.mock(com.loai.inventory.service.FulfillmentService.class),
        Mockito.mock(com.loai.inventory.service.InvoiceAdminService.class),
        Mockito.mock(com.loai.inventory.service.InventoryService.class),
        Mockito.mock(com.loai.inventory.service.document.DocumentRenderService.class),
        /* counterReturnService */ null,
        com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  @Test
  void get_allowedForViewer_allEightKeysOnTheWire() throws IOException {
    SalesOrderService service = Mockito.mock(SalesOrderService.class);
    when(service.statusCounts(ORG)).thenReturn(someCounts());
    Resp resp = new Resp();

    handler(service)
        .handle("GET", reqWith(ctxWith(ORG, OrgRole.VIEWER)), resp.mock, ORG, "/status-counts");

    assertEquals(200, resp.status, "VIEWER counts read must succeed");
    verify(service).statusCounts(ORG);
    verify(service, never()).getById(any(), any());
    String body = resp.body.toString();
    for (OrderStatus status : OrderStatus.values()) {
      assertTrue(body.contains("\"" + status.name() + "\""), status + " must be on the wire");
    }
    assertTrue(body.contains("\"total\":2"), "total must ride the envelope");
  }

  @Test
  void get_nonMember_forbidden_serviceNeverCalled() throws IOException {
    SalesOrderService service = Mockito.mock(SalesOrderService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(UUID.randomUUID(), OrgRole.OWNER)),
            resp.mock,
            ORG,
            "/status-counts");

    assertEquals(403, resp.status, "non-member must be forbidden");
    verify(service, never()).statusCounts(any());
  }

  @Test
  void get_unauthenticated_is401_serviceNeverCalled() throws IOException {
    SalesOrderService service = Mockito.mock(SalesOrderService.class);
    Resp resp = new Resp();

    handler(service).handle("GET", reqWith(null), resp.mock, ORG, "/status-counts");

    assertEquals(401, resp.status, "missing auth must be 401");
    verify(service, never()).statusCounts(any());
  }

  @Test
  void post_onTheFixedSegment_is405_neverTheFallthrough400() throws IOException {
    SalesOrderService service = Mockito.mock(SalesOrderService.class);
    Resp resp = new Resp();

    handler(service)
        .handle("POST", reqWith(ctxWith(ORG, OrgRole.OWNER)), resp.mock, ORG, "/status-counts");

    assertEquals(405, resp.status, "non-GET on the fixed segment is method-not-allowed");
    verify(service, never()).statusCounts(any());
  }

  // harness (mirrors OrderLookupHandlerAuthTest)

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

  private HttpServletRequest reqWith(SecurityContext ctx) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    return req;
  }
}
