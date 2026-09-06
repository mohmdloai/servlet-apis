package com.loai.inventory.api.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.InventoryHandler;
import com.loai.inventory.api.servlet.handler.SalesOrderHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.InventoryListFilter;
import com.loai.inventory.domain.model.InventoryStockCounts;
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
        /* counterReturnService */ null,
        com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  // GET /inventory — stock overview list

  @Test
  void overview_allowedForViewer_serviceCalledWithParsedFilters() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    when(service.listOverview(eq(ORG), any(InventoryListFilter.class), anyInt(), anyInt()))
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
        .listOverview(
            eq(ORG),
            argThat(
                (InventoryListFilter f) ->
                    f.stock() == InventoryStockFilter.LOW
                        && Integer.valueOf(5).equals(f.lowLte())
                        && f.q() == null
                        && f.categoryId() == null
                        && f.held() == null
                        && f.rule() == null
                        && f.changedFrom() == null
                        && f.changedTo() == null
                        && f.sort() == null),
            eq(0),
            anyInt());
    // The envelope always carries the summary (inventory_filters.md); cost_value stays off for a
    // VIEWER — the cost_price rule.
    String body = resp.body.toString();
    assertTrue(body.contains("\"summary\""), body);
    assertTrue(body.contains("\"units_on_hand\""), body);
    assertTrue(!body.contains("cost_value"), "cost_value must not cross for a VIEWER: " + body);
  }

  @Test
  void overview_everyFilterDimension_isParsedIntoTheOneFilter() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    when(service.listOverview(eq(ORG), any(InventoryListFilter.class), anyInt(), anyInt()))
        .thenReturn(new OverviewPage(List.of(), 0));
    Resp resp = new Resp();
    UUID category = UUID.randomUUID();
    Map<String, String> params = new java.util.HashMap<>();
    params.put("q", "  sugar ");
    params.put("category", category.toString());
    params.put("held", "true");
    params.put("rule", "none");
    params.put("changed_from", "2026-06-07T21:00:00Z");
    params.put("changed_to", "2026-09-05T21:00:00Z");
    params.put("sort", "on_hand");

    inventoryHandler(service)
        .handle("GET", reqWith(ctxWith(ORG, OrgRole.MANAGER), params), resp.mock, ORG, "");

    assertEquals(200, resp.status);
    verify(service)
        .listOverview(
            eq(ORG),
            argThat(
                (InventoryListFilter f) ->
                    "sugar".equals(f.q())
                        && f.stock() == null
                        && category.equals(f.categoryId())
                        && Boolean.TRUE.equals(f.held())
                        && f.rule() == InventoryListFilter.ReorderRule.NONE
                        && f.changedFrom() != null
                        && f.changedTo() != null
                        && f.changedFrom().isBefore(f.changedTo())
                        && f.sort() == InventoryListFilter.Sort.ON_HAND),
            eq(0),
            anyInt());
    // A MANAGER sees the cost figure on the summary.
    assertTrue(resp.body.toString().contains("\"cost_value\""), resp.body.toString());
  }

  @Test
  void overview_badFilterValues_are400_serviceNeverCalled() throws IOException {
    Map<String, Map<String, String>> bad =
        Map.of(
            "category not a uuid", Map.of("category", "food"),
            "held not boolean", Map.of("held", "yes"),
            "rule unknown", Map.of("rule", "maybe"),
            "sort unknown", Map.of("sort", "price"),
            "changed_from not a date-time", Map.of("changed_from", "yesterday"),
            "changed window inverted",
                Map.of(
                    "changed_from", "2026-09-05T00:00:00Z", "changed_to", "2026-09-01T00:00:00Z"));
    for (var entry : bad.entrySet()) {
      InventoryService service = Mockito.mock(InventoryService.class);
      Resp resp = new Resp();

      inventoryHandler(service)
          .handle(
              "GET", reqWith(ctxWith(ORG, OrgRole.VIEWER), entry.getValue()), resp.mock, ORG, "");

      assertEquals(400, resp.status, entry.getKey() + " must be a 400");
      verify(service, never())
          .listOverview(any(), any(InventoryListFilter.class), anyInt(), anyInt());
    }
  }

  // GET /inventory/stock-counts — the tabs' numbers

  @Test
  void stockCounts_allowedForViewer_routedBeforeTheIdParse_andPassesLowLte() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    when(service.stockCounts(eq(ORG), any())).thenReturn(new InventoryStockCounts(9, 2, 1, 1, 3));
    Resp resp = new Resp();

    inventoryHandler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of("low_lte", "7")),
            resp.mock,
            ORG,
            "/stock-counts");

    assertEquals(200, resp.status, "'stock-counts' is a collection read, never an invalid id");
    verify(service).stockCounts(ORG, 7);
    String body = resp.body.toString();
    assertTrue(body.contains("\"all\":9"), body);
    assertTrue(body.contains("\"untracked\":3"), body);
  }

  @Test
  void stockCounts_unauthenticated_is401_andPostIs405() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    Resp anon = new Resp();
    inventoryHandler(service)
        .handle("GET", reqWith(null, Map.of()), anon.mock, ORG, "/stock-counts");
    assertEquals(401, anon.status);
    verify(service, never()).stockCounts(any(), any());

    Resp post = new Resp();
    inventoryHandler(service)
        .handle(
            "POST",
            reqWith(ctxWith(ORG, OrgRole.OWNER), Map.of()),
            post.mock,
            ORG,
            "/stock-counts");
    assertEquals(405, post.status);
  }

  @Test
  void overview_unauthenticated_is401_serviceNeverCalled() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    Resp resp = new Resp();

    inventoryHandler(service).handle("GET", reqWith(null, Map.of()), resp.mock, ORG, "");

    assertEquals(401, resp.status);
    verify(service, never())
        .listOverview(any(), any(InventoryListFilter.class), anyInt(), anyInt());
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
    verify(service, never())
        .listOverview(any(), any(InventoryListFilter.class), anyInt(), anyInt());
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
    verify(service, never())
        .listOverview(any(), any(InventoryListFilter.class), anyInt(), anyInt());
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
    verify(service, never())
        .listOverview(any(), any(InventoryListFilter.class), anyInt(), anyInt());
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
