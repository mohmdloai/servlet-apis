package com.loai.inventory.api.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.InventoryHandler;
import com.loai.inventory.api.servlet.handler.SalesOrderHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.InventoryStockFilter;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.ReservationStatus;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.InventoryService;
import com.loai.inventory.service.InventoryService.LogPage;
import com.loai.inventory.service.InventoryService.OrderReservations;
import com.loai.inventory.service.InventoryService.OverviewPage;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Runtime auth + param verification for the inventory read slice ({@code
 * stories/inventory_reads.md}): the stock overview, the movement ledger, and the per-product /
 * per-order reservation reads are all reads, so VIEWER suffices; anon 401; an unknown {@code stock}
 * / reservation {@code status}, a non-integer or negative pager, or an unsupported action is a 400
 * that never reaches the service.
 */
class InventoryReadsHandlerAuthTest {

  static {
    // Mirror the money-reads test: ByteBuddy experimental mode for inline mocking on Java 25.
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID PRODUCT = UUID.randomUUID();
  private static final UUID ORDER = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  private SecurityContext ctxWith(UUID orgId, OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(orgId, Set.of(role)), Set.of(), 0);
  }

  private InventoryHandler inventoryHandler(InventoryService service) {
    var listings = Mockito.mock(com.loai.inventory.service.ProductListingService.class);
    when(listings.primaryImageUrlsByProductId(any(), any())).thenReturn(Map.of());
    return new InventoryHandler(
        service, listings, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  private SalesOrderHandler salesOrderHandler(InventoryService service) {
    return new SalesOrderHandler(
        Mockito.mock(com.loai.inventory.service.SalesOrderService.class),
        Mockito.mock(com.loai.inventory.service.OrderCancellationService.class),
        Mockito.mock(com.loai.inventory.service.PaymentService.class),
        Mockito.mock(com.loai.inventory.service.FulfillmentService.class),
        Mockito.mock(com.loai.inventory.service.InvoiceAdminService.class),
        service,
        Mockito.mock(com.loai.inventory.service.document.DocumentRenderService.class),
        com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  // GET /inventory — stock overview list

  @Test
  void overview_allowedForViewer_serviceCalledWithParsedFilters() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    when(service.listOverview(eq(ORG), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(new OverviewPage(List.of(), 0));
    Resp resp = new Resp();

    inventoryHandler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of("stock", "low", "low_lte", "5")),
            resp.mock,
            ORG,
            "");

    assertEquals(200, resp.status, "VIEWER stock overview must succeed");
    verify(service)
        .listOverview(eq(ORG), any(), eq(InventoryStockFilter.LOW), eq(5), eq(0), anyInt());
  }

  @Test
  void overview_unauthenticated_is401_serviceNeverCalled() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    Resp resp = new Resp();

    inventoryHandler(service).handle("GET", reqWith(null, Map.of()), resp.mock, ORG, "");

    assertEquals(401, resp.status);
    verify(service, never()).listOverview(any(), any(), any(), any(), anyInt(), anyInt());
  }

  @Test
  void overview_unknownStock_is400_serviceNeverCalled() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    Resp resp = new Resp();

    inventoryHandler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of("stock", "SHRUG")),
            resp.mock,
            ORG,
            "");

    assertEquals(400, resp.status, "unknown stock filter must fail loudly");
    verify(service, never()).listOverview(any(), any(), any(), any(), anyInt(), anyInt());
  }

  @Test
  void overview_nonIntegerPaging_is400_serviceNeverCalled() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    Resp resp = new Resp();

    inventoryHandler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of("page", "one")),
            resp.mock,
            ORG,
            "");

    assertEquals(400, resp.status);
    verify(service, never()).listOverview(any(), any(), any(), any(), anyInt(), anyInt());
  }

  @Test
  void overview_negativeLowLte_is400_serviceNeverCalled() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    Resp resp = new Resp();

    inventoryHandler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of("stock", "low", "low_lte", "-1")),
            resp.mock,
            ORG,
            "");

    assertEquals(400, resp.status);
    verify(service, never()).listOverview(any(), any(), any(), any(), anyInt(), anyInt());
  }

  // GET /inventory/{productId}/log — movement ledger

  @Test
  void log_allowedForViewer_serviceCalled() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    when(service.listLog(eq(ORG), eq(PRODUCT), anyInt(), anyInt()))
        .thenReturn(new LogPage(List.of(), Map.of(), 0));
    Resp resp = new Resp();

    inventoryHandler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of()),
            resp.mock,
            ORG,
            "/" + PRODUCT + "/log");

    assertEquals(200, resp.status, "VIEWER movement ledger must succeed");
    verify(service).listLog(eq(ORG), eq(PRODUCT), eq(0), anyInt());
  }

  @Test
  void log_unauthenticated_is401_serviceNeverCalled() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    Resp resp = new Resp();

    inventoryHandler(service)
        .handle("GET", reqWith(null, Map.of()), resp.mock, ORG, "/" + PRODUCT + "/log");

    assertEquals(401, resp.status);
    verify(service, never()).listLog(any(), any(), anyInt(), anyInt());
  }

  // GET /inventory/{productId}/reservations — per-product holds

  @Test
  void productReservations_allowedForViewer_defaultsToActive() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    when(service.listProductReservations(eq(ORG), eq(PRODUCT), eq(ReservationStatus.ACTIVE)))
        .thenReturn(List.of());
    Resp resp = new Resp();

    inventoryHandler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of()),
            resp.mock,
            ORG,
            "/" + PRODUCT + "/reservations");

    assertEquals(200, resp.status);
    verify(service).listProductReservations(ORG, PRODUCT, ReservationStatus.ACTIVE);
  }

  @Test
  void productReservations_unknownStatus_is400_serviceNeverCalled() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    Resp resp = new Resp();

    inventoryHandler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of("status", "SHRUG")),
            resp.mock,
            ORG,
            "/" + PRODUCT + "/reservations");

    assertEquals(400, resp.status);
    verify(service, never()).listProductReservations(any(), any(), any());
  }

  @Test
  void unknownAction_is400() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    Resp resp = new Resp();

    inventoryHandler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of()),
            resp.mock,
            ORG,
            "/" + PRODUCT + "/frobnicate");

    assertEquals(400, resp.status);
    verify(service, never()).listLog(any(), any(), anyInt(), anyInt());
    verify(service, never()).listProductReservations(any(), any(), any());
  }

  // GET /sales-orders/{id}/reservations — order holds panel

  @Test
  void orderReservations_allowedForViewer_serviceCalled() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    SalesOrder order = Mockito.mock(SalesOrder.class);
    when(order.getId()).thenReturn(ORDER);
    when(order.getOrderNumber()).thenReturn("SO-2026-000123");
    when(order.getStatus()).thenReturn(OrderStatus.FULFILLING);
    when(order.getGrandTotal()).thenReturn(new BigDecimal("59.97"));
    when(order.getPrepaidAmount()).thenReturn(new BigDecimal("59.97"));
    when(service.listOrderReservations(ORG, ORDER))
        .thenReturn(new OrderReservations(order, List.of(), Map.of()));
    Resp resp = new Resp();

    salesOrderHandler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of()),
            resp.mock,
            ORG,
            "/" + ORDER + "/reservations");

    assertEquals(200, resp.status, "VIEWER order holds panel must succeed");
    verify(service).listOrderReservations(ORG, ORDER);
  }

  @Test
  void orderReservations_unauthenticated_is401_serviceNeverCalled() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    Resp resp = new Resp();

    salesOrderHandler(service)
        .handle("GET", reqWith(null, Map.of()), resp.mock, ORG, "/" + ORDER + "/reservations");

    assertEquals(401, resp.status);
    verify(service, never()).listOrderReservations(any(), any());
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
