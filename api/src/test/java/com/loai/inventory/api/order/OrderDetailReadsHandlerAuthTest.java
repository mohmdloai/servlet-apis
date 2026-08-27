package com.loai.inventory.api.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.SalesOrderHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.FulfillmentService.OrderFulfillments;
import com.loai.inventory.service.InvoiceAdminService;
import com.loai.inventory.service.InvoiceAdminService.OrderInvoices;
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
 * Runtime auth verification for the order detail reads ({@code stories/fulfillment_reads.md}):
 * {@code GET /sales-orders/{id}}, {@code GET /sales-orders/{id}/fulfillments} and {@code GET
 * /sales-orders/{id}/invoices} ({@code stories/money_reads.md}) are reads, so VIEWER suffices;
 * non-member 403 / anon 401; a malformed id is a 400 that never reaches the service.
 */
class OrderDetailReadsHandlerAuthTest {

  static {
    // The mocked services are final; the Mockito inline mock-maker needs ByteBuddy experimental
    // mode on Java 25 (mirrors OrderLookupHandlerAuthTest).
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID ORDER = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  private SecurityContext ctxWith(UUID orgId, OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(orgId, Set.of(role)), Set.of(), 0);
  }

  private SalesOrder anOrder() {
    return SalesOrder.createDraft(
        ORDER,
        ORG,
        UUID.randomUUID(),
        "SO-2026-00001",
        OrderChannel.ONLINE,
        "EGP",
        "idem",
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  private SalesOrderHandler handler(
      SalesOrderService orderService, FulfillmentService fulfillmentService) {
    return handler(orderService, fulfillmentService, Mockito.mock(InvoiceAdminService.class));
  }

  private SalesOrderHandler handler(
      SalesOrderService orderService,
      FulfillmentService fulfillmentService,
      InvoiceAdminService invoiceAdminService) {
    return new SalesOrderHandler(
        orderService,
        Mockito.mock(OrderCancellationService.class),
        Mockito.mock(PaymentService.class),
        fulfillmentService,
        invoiceAdminService,
        Mockito.mock(com.loai.inventory.service.InventoryService.class),
        Mockito.mock(com.loai.inventory.service.document.DocumentRenderService.class),
        /* counterReturnService */ null,
        com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  @Test
  void getById_allowedForViewer_serviceCalled() throws IOException {
    SalesOrderService orderService = Mockito.mock(SalesOrderService.class);
    when(orderService.getById(ORG, ORDER)).thenReturn(new Placed(anOrder(), List.of(), null));
    Resp resp = new Resp();

    handler(orderService, Mockito.mock(FulfillmentService.class))
        .handle("GET", reqWith(ctxWith(ORG, OrgRole.VIEWER)), resp.mock, ORG, "/" + ORDER);

    assertEquals(200, resp.status, "VIEWER order detail must succeed");
    verify(orderService).getById(ORG, ORDER);
  }

  @Test
  void getFulfillments_allowedForViewer_serviceCalled() throws IOException {
    FulfillmentService fulfillmentService = Mockito.mock(FulfillmentService.class);
    when(fulfillmentService.listForOrder(ORG, ORDER))
        .thenReturn(new OrderFulfillments(anOrder(), List.of()));
    Resp resp = new Resp();

    handler(Mockito.mock(SalesOrderService.class), fulfillmentService)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER)),
            resp.mock,
            ORG,
            "/" + ORDER + "/fulfillments");

    assertEquals(200, resp.status, "VIEWER shipment story must succeed");
    verify(fulfillmentService).listForOrder(ORG, ORDER);
  }

  @Test
  void getInvoices_allowedForViewer_serviceCalled() throws IOException {
    InvoiceAdminService invoiceAdminService = Mockito.mock(InvoiceAdminService.class);
    when(invoiceAdminService.listForOrder(ORG, ORDER))
        .thenReturn(new OrderInvoices(anOrder(), List.of()));
    Resp resp = new Resp();

    handler(
            Mockito.mock(SalesOrderService.class),
            Mockito.mock(FulfillmentService.class),
            invoiceAdminService)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER)),
            resp.mock,
            ORG,
            "/" + ORDER + "/invoices");

    assertEquals(200, resp.status, "VIEWER billing story must succeed");
    verify(invoiceAdminService).listForOrder(ORG, ORDER);
  }

  @Test
  void getInvoices_nonMember_forbidden_serviceNeverCalled() throws IOException {
    InvoiceAdminService invoiceAdminService = Mockito.mock(InvoiceAdminService.class);
    Resp resp = new Resp();

    handler(
            Mockito.mock(SalesOrderService.class),
            Mockito.mock(FulfillmentService.class),
            invoiceAdminService)
        .handle(
            "GET",
            reqWith(ctxWith(UUID.randomUUID(), OrgRole.OWNER)),
            resp.mock,
            ORG,
            "/" + ORDER + "/invoices");

    assertEquals(403, resp.status, "non-member must be forbidden");
    verify(invoiceAdminService, never()).listForOrder(any(), any());
  }

  @Test
  void reads_nonMember_forbidden_servicesNeverCalled() throws IOException {
    SalesOrderService orderService = Mockito.mock(SalesOrderService.class);
    FulfillmentService fulfillmentService = Mockito.mock(FulfillmentService.class);
    Resp byId = new Resp();
    Resp story = new Resp();

    SalesOrderHandler handler = handler(orderService, fulfillmentService);
    handler.handle(
        "GET", reqWith(ctxWith(UUID.randomUUID(), OrgRole.OWNER)), byId.mock, ORG, "/" + ORDER);
    handler.handle(
        "GET",
        reqWith(ctxWith(UUID.randomUUID(), OrgRole.OWNER)),
        story.mock,
        ORG,
        "/" + ORDER + "/fulfillments");

    assertEquals(403, byId.status, "non-member must be forbidden");
    assertEquals(403, story.status, "non-member must be forbidden");
    verify(orderService, never()).getById(any(), any());
    verify(fulfillmentService, never()).listForOrder(any(), any());
  }

  @Test
  void reads_unauthenticated_is401_servicesNeverCalled() throws IOException {
    SalesOrderService orderService = Mockito.mock(SalesOrderService.class);
    FulfillmentService fulfillmentService = Mockito.mock(FulfillmentService.class);
    Resp byId = new Resp();
    Resp story = new Resp();

    SalesOrderHandler handler = handler(orderService, fulfillmentService);
    handler.handle("GET", reqWith(null), byId.mock, ORG, "/" + ORDER);
    handler.handle("GET", reqWith(null), story.mock, ORG, "/" + ORDER + "/fulfillments");

    assertEquals(401, byId.status, "missing auth must be 401");
    assertEquals(401, story.status, "missing auth must be 401");
    verify(orderService, never()).getById(any(), any());
    verify(fulfillmentService, never()).listForOrder(any(), any());
  }

  @Test
  void getById_malformedId_is400_serviceNeverCalled() throws IOException {
    SalesOrderService orderService = Mockito.mock(SalesOrderService.class);
    Resp resp = new Resp();

    handler(orderService, Mockito.mock(FulfillmentService.class))
        .handle("GET", reqWith(ctxWith(ORG, OrgRole.VIEWER)), resp.mock, ORG, "/not-a-uuid");

    assertEquals(400, resp.status);
    verify(orderService, never()).getById(any(), any());
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
