package com.loai.inventory.api.payment;

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
import com.loai.inventory.service.OrderCancellationService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentService.OrderPayments;
import com.loai.inventory.service.SalesOrderService;
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
 * Runtime auth verification for {@code GET /sales-orders/{id}/payments} ({@code
 * stories/list_order_payments.md}): a read, so VIEWER suffices; a caller with no role in the org
 * gets 403 and the service is never called; unauthenticated is 401.
 */
class OrderPaymentsHandlerAuthTest {

  static {
    // PaymentService is final; the Mockito inline mock-maker needs ByteBuddy experimental mode on
    // Java 25 (mirrors SalesOrderCancelAuthTest).
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID ORDER = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  private SecurityContext ctxWith(UUID orgId, OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(orgId, Set.of(role)), Set.of(), 0);
  }

  private OrderPayments anOrderPayments() {
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
    return new OrderPayments(order, List.of());
  }

  private SalesOrderHandler handler(PaymentService paymentService) {
    return new SalesOrderHandler(
        Mockito.mock(SalesOrderService.class),
        Mockito.mock(OrderCancellationService.class),
        paymentService,
        Mockito.mock(com.loai.inventory.service.FulfillmentService.class),
        Mockito.mock(com.loai.inventory.service.InvoiceAdminService.class),
        com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  @Test
  void payments_allowedForViewer_serviceCalled() throws IOException {
    PaymentService service = Mockito.mock(PaymentService.class);
    when(service.listForOrder(ORG, ORDER)).thenReturn(anOrderPayments());
    Resp resp = new Resp();

    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER)),
            resp.mock,
            ORG,
            "/" + ORDER + "/payments");

    assertEquals(200, resp.status, "VIEWER read must succeed");
    verify(service).listForOrder(ORG, ORDER);
  }

  @Test
  void payments_nonMember_forbidden_serviceNeverCalled() throws IOException {
    PaymentService service = Mockito.mock(PaymentService.class);
    Resp resp = new Resp();

    // A role in a different org gives no access here.
    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(UUID.randomUUID(), OrgRole.OWNER)),
            resp.mock,
            ORG,
            "/" + ORDER + "/payments");

    assertEquals(403, resp.status, "non-member must be forbidden");
    verify(service, never()).listForOrder(any(), any());
  }

  @Test
  void payments_unauthenticated_is401_serviceNeverCalled() throws IOException {
    PaymentService service = Mockito.mock(PaymentService.class);
    Resp resp = new Resp();

    handler(service).handle("GET", reqWith(null), resp.mock, ORG, "/" + ORDER + "/payments");

    assertEquals(401, resp.status, "missing auth must be 401");
    verify(service, never()).listForOrder(any(), any());
  }

  @Test
  void malformedOrderId_is400() throws IOException {
    PaymentService service = Mockito.mock(PaymentService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "GET", reqWith(ctxWith(ORG, OrgRole.VIEWER)), resp.mock, ORG, "/not-a-uuid/payments");

    assertEquals(400, resp.status, "malformed id must be 400");
    verify(service, never()).listForOrder(any(), any());
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

  private HttpServletRequest reqWith(SecurityContext ctx) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    return req;
  }
}
