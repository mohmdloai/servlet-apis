package com.loai.inventory.api.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.SalesOrderHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrderListFilter;
import com.loai.inventory.domain.model.OrderListStats;
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
 * stories/lookup_order_by_number.md}): a read, so VIEWER suffices; non-member 403 / anon 401. The
 * bare {@code GET} without the param now returns the order worklist page (the reserved route was
 * implemented — see {@code SalesOrderService#list}).
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
        Mockito.mock(com.loai.inventory.service.FulfillmentService.class),
        Mockito.mock(com.loai.inventory.service.InvoiceAdminService.class),
        Mockito.mock(com.loai.inventory.service.InventoryService.class),
        Mockito.mock(com.loai.inventory.service.document.DocumentRenderService.class),
        /* counterReturnService */ null,
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
  void bareGet_returnsTheWorklist_serviceListCalled() throws IOException {
    SalesOrderService service = Mockito.mock(SalesOrderService.class);
    // The worklist read passes every dimension (all absent here) through as one filter.
    when(service.list(eq(ORG), any(OrderListFilter.class), anyInt(), anyInt()))
        .thenReturn(new SalesOrderService.OrderListPage(List.of(), 0, OrderListStats.empty()));
    Resp resp = new Resp();

    handler(service).handle("GET", reqWith(ctxWith(ORG, OrgRole.VIEWER), null), resp.mock, ORG, "");

    assertEquals(200, resp.status, "bare GET now returns the order worklist page");
    verify(service).list(eq(ORG), eq(OrderListFilter.none()), anyInt(), anyInt());
    verify(service, never()).getByNumber(any(), anyString());
    assertTrue(
        resp.body.toString(java.nio.charset.StandardCharsets.UTF_8).contains("\"summary\""),
        "the envelope carries the summary");
  }

  @Test
  void list_filtersReachTheService_asOneFilter() throws IOException {
    SalesOrderService service = Mockito.mock(SalesOrderService.class);
    when(service.list(eq(ORG), any(OrderListFilter.class), anyInt(), anyInt()))
        .thenReturn(new SalesOrderService.OrderListPage(List.of(), 0, OrderListStats.empty()));
    HttpServletRequest req = reqWith(ctxWith(ORG, OrgRole.VIEWER), null);
    when(req.getParameter("status")).thenReturn("paid");
    when(req.getParameter("channel")).thenReturn("in_store");
    when(req.getParameter("q")).thenReturn("  nadia ");
    when(req.getParameter("from")).thenReturn("2026-07-31T21:00:00Z");
    when(req.getParameter("to")).thenReturn("2026-08-31T21:00:00Z");
    when(req.getParameter("balance")).thenReturn("owing");
    when(req.getParameter("min")).thenReturn("500");
    when(req.getParameter("max")).thenReturn("2500.50");
    when(req.getParameter("sort")).thenReturn("total");
    Resp resp = new Resp();

    handler(service).handle("GET", req, resp.mock, ORG, "");

    assertEquals(200, resp.status);
    var captor = org.mockito.ArgumentCaptor.forClass(OrderListFilter.class);
    verify(service).list(eq(ORG), captor.capture(), anyInt(), anyInt());
    OrderListFilter f = captor.getValue();
    assertEquals(com.loai.inventory.domain.model.OrderStatus.PAID, f.status());
    assertEquals(OrderChannel.IN_STORE, f.channel());
    assertEquals("nadia", f.q(), "q is trimmed");
    assertEquals(OffsetDateTime.parse("2026-07-31T21:00:00Z"), f.createdFrom());
    assertEquals(OffsetDateTime.parse("2026-08-31T21:00:00Z"), f.createdTo());
    assertEquals(OrderListFilter.Balance.OWING, f.balance());
    assertEquals(0, new java.math.BigDecimal("500").compareTo(f.minTotal()));
    assertEquals(0, new java.math.BigDecimal("2500.50").compareTo(f.maxTotal()));
    assertEquals(OrderListFilter.Sort.TOTAL, f.sort());
  }

  @Test
  void list_badFilterValues_are400_serviceNeverCalled() throws IOException {
    record Bad(String param, String value, String why) {}
    List<Bad> cases =
        List.of(
            new Bad("from", "2026-08-01", "a bare date is not an ISO-8601 date-time"),
            new Bad("to", "yesterday", "prose is not a date-time"),
            new Bad("balance", "half", "balance is owing|settled|overpaid"),
            new Bad("sort", "price", "sort is whitelisted"),
            new Bad("min", "abc", "min must be a number"),
            new Bad("max", "-1", "max must not be negative"),
            new Bad("channel", "KIOSK", "channel names the three"));
    for (Bad bad : cases) {
      SalesOrderService service = Mockito.mock(SalesOrderService.class);
      HttpServletRequest req = reqWith(ctxWith(ORG, OrgRole.VIEWER), null);
      when(req.getParameter(bad.param())).thenReturn(bad.value());
      Resp resp = new Resp();

      handler(service).handle("GET", req, resp.mock, ORG, "");

      assertEquals(400, resp.status, bad.why());
      verify(service, never()).list(any(), any(OrderListFilter.class), anyInt(), anyInt());
    }
  }

  @Test
  void list_invertedWindowOrBounds_are400() throws IOException {
    for (String[] pair :
        List.of(
            new String[] {"from", "2026-09-01T00:00:00Z", "to", "2026-08-01T00:00:00Z"},
            new String[] {"min", "900", "max", "100"})) {
      SalesOrderService service = Mockito.mock(SalesOrderService.class);
      HttpServletRequest req = reqWith(ctxWith(ORG, OrgRole.VIEWER), null);
      when(req.getParameter(pair[0])).thenReturn(pair[1]);
      when(req.getParameter(pair[2])).thenReturn(pair[3]);
      Resp resp = new Resp();

      handler(service).handle("GET", req, resp.mock, ORG, "");

      assertEquals(400, resp.status, pair[0] + " > " + pair[2] + " must be a 400");
      verify(service, never()).list(any(), any(OrderListFilter.class), anyInt(), anyInt());
    }
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

  // harness (mirrors SalesOrderCancelAuthTest)

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
