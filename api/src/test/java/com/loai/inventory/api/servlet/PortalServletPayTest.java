package com.loai.inventory.api.servlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.filter.CustomerAuthFilter;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.UpstreamFailureException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.domain.model.CustomerPrincipal;
import com.loai.inventory.domain.model.PaymentIntent;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.service.CustomerPortalService;
import com.loai.inventory.service.ListingCommentService;
import com.loai.inventory.service.ListingReviewService;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.PaymentIntentService;
import com.loai.inventory.service.PaymentIntentService.PayResult;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.ReturnTarget;
import com.loai.inventory.service.WishlistService;
import com.loai.inventory.service.auth.CustomerAuthService;
import com.loai.inventory.service.document.DocumentRenderService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The signed-in door to a card payment ({@code stories/paymob_portal_pay.md}, {@code POST
 * /api/portal/orders/{n}/pay}), decision by decision: the session is the capability (no principal →
 * 401), ownership is resolved before Paymob is ever spoken to (a foreign or unknown number → the
 * portal's opaque 404 with the intent service never called), the happy path is the public door's
 * body with a {@code private, no-store} header, the service's 409/502 pass through unchanged, and
 * the portal's verb rule applies. The CSRF gate in front of it is {@link CustomerAuthFilter}'s and
 * is pinned for this route in {@code CustomerAuthFilterTest} — a missing {@code X-Portal-Request}
 * is that filter's 400 and a foreign {@code Origin} its 403, both before the servlet sees the
 * request.
 */
class PortalServletPayTest {

  static {
    // PaymentIntentService is final; the inline mock-maker needs ByteBuddy experimental on Java 25
    // (mirrors PaymobHandlerAuthTest).
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final ObjectMapper JSON = ObjectMapperProvider.build();
  private static final UUID ORG = UUID.randomUUID();
  private static final UUID CUSTOMER = UUID.randomUUID();
  private static final UUID ORDER_ID = UUID.randomUUID();
  private static final String ORDER_NUMBER = "SO-2026-00042";

  // the servlet

  @Test
  void noSession_is401_andNothingIsResolved() throws IOException {
    CustomerPortalService portal = mock(CustomerPortalService.class);
    PaymentIntentService intents = mock(PaymentIntentService.class);
    Resp resp = new Resp();

    servlet(portal, intents)
        .service(req("POST", "/orders/" + ORDER_NUMBER + "/pay", null), resp.mock);

    assertEquals(401, resp.status);
    verify(portal, never()).getOrder(any(), any(), any());
    verify(intents, never()).pay(any(), any(), any(), any());
  }

  @Test
  void aForeignOrUnknownOrder_isTheOpaque404_beforePaymobIsCalled() throws IOException {
    CustomerPortalService portal = mock(CustomerPortalService.class);
    when(portal.getOrder(ORG, CUSTOMER, "SO-2026-99999"))
        .thenThrow(new NotFoundException("Order not found: SO-2026-99999"));
    PaymentIntentService intents = mock(PaymentIntentService.class);
    Resp resp = new Resp();

    servlet(portal, intents)
        .service(req("POST", "/orders/SO-2026-99999/pay", principal()), resp.mock);

    assertEquals(404, resp.status);
    verify(intents, never()).pay(any(), any(), any(), any());
  }

  @Test
  void ownedPendingOrder_is200WithTheCheckoutBody_andThePortalReturnTarget() throws IOException {
    CustomerPortalService.OrderView owned = ownedOrder();
    CustomerPortalService portal = mock(CustomerPortalService.class);
    when(portal.getOrder(ORG, CUSTOMER, ORDER_NUMBER)).thenReturn(owned);
    PaymentIntentService intents = mock(PaymentIntentService.class);
    OffsetDateTime expires = OffsetDateTime.of(2026, 9, 15, 10, 20, 0, 0, ZoneOffset.UTC);
    when(intents.pay(
            eq(ORG), eq(ORDER_ID), eq(CUSTOMER), eq(ReturnTarget.portalOrder(ORDER_NUMBER))))
        .thenReturn(
            new PayResult(
                "https://accept.paymob.com/unifiedcheckout/?publicKey=pk&clientSecret=cs",
                expires,
                anIntent(),
                false));
    Resp resp = new Resp();

    servlet(portal, intents)
        .service(req("POST", "/orders/" + ORDER_NUMBER + "/pay", principal()), resp.mock);

    assertEquals(200, resp.status);
    String body = resp.body();
    assertTrue(
        body.contains(
            "\"checkout_url\":\"https://accept.paymob.com/unifiedcheckout/?publicKey=pk&clientSecret=cs\""),
        body);
    assertTrue(body.contains("\"expires_at\":\"2026-09-15T10:20:00Z\""), body);
    verify(resp.mock).setHeader("Cache-Control", "private, no-store");
    // The order id the service is handed is the one ownership resolved — never a parsed body.
    verify(intents).pay(ORG, ORDER_ID, CUSTOMER, ReturnTarget.portalOrder(ORDER_NUMBER));
  }

  @Test
  void theServices409And502_passThroughWithTheirMessages() throws IOException {
    CustomerPortalService.OrderView owned = ownedOrder();
    CustomerPortalService portal = mock(CustomerPortalService.class);
    when(portal.getOrder(ORG, CUSTOMER, ORDER_NUMBER)).thenReturn(owned);

    PaymentIntentService refuses = mock(PaymentIntentService.class);
    when(refuses.pay(any(), any(), any(), any()))
        .thenThrow(new ConflictException("order " + ORDER_NUMBER + " has nothing left to pay"));
    Resp r409 = new Resp();
    servlet(portal, refuses)
        .service(req("POST", "/orders/" + ORDER_NUMBER + "/pay", principal()), r409.mock);
    assertEquals(409, r409.status);
    assertTrue(r409.body().contains("nothing left to pay"), r409.body());

    PaymentIntentService silent = mock(PaymentIntentService.class);
    when(silent.pay(any(), any(), any(), any()))
        .thenThrow(new UpstreamFailureException("payment provider did not answer"));
    Resp r502 = new Resp();
    servlet(portal, silent)
        .service(req("POST", "/orders/" + ORDER_NUMBER + "/pay", principal()), r502.mock);
    assertEquals(502, r502.status);
  }

  /** The portal answers a wrong verb with its 400 "Method not allowed", as on every route. */
  @Test
  void get_isTheVerbRefusal_serviceNeverCalled() throws IOException {
    CustomerPortalService portal = mock(CustomerPortalService.class);
    PaymentIntentService intents = mock(PaymentIntentService.class);
    Resp resp = new Resp();

    servlet(portal, intents)
        .service(req("GET", "/orders/" + ORDER_NUMBER + "/pay", principal()), resp.mock);

    assertEquals(400, resp.status);
    assertTrue(resp.body().contains("Method not allowed"), resp.body());
    verify(portal, never()).getOrder(any(), any(), any());
    verify(intents, never()).pay(any(), any(), any(), any());
  }

  // helpers

  private static PortalServlet servlet(CustomerPortalService portal, PaymentIntentService intents) {
    return new PortalServlet(
        mock(CustomerAuthService.class),
        portal,
        mock(PaymentTransactionService.class),
        intents,
        mock(ObjectStorage.class),
        mock(DocumentRenderService.class),
        mock(NotificationService.class),
        mock(ListingReviewService.class),
        mock(ListingCommentService.class),
        mock(WishlistService.class),
        JSON,
        false,
        3600);
  }

  private static CustomerPrincipal principal() {
    return new CustomerPrincipal(CUSTOMER, ORG, 1, UUID.randomUUID());
  }

  /** Built BEFORE any {@code when(...)} — a mock stubbed inside another stubbing is unfinished. */
  private static CustomerPortalService.OrderView ownedOrder() {
    SalesOrder order = Mockito.mock(SalesOrder.class);
    when(order.getId()).thenReturn(ORDER_ID);
    when(order.getOrderNumber()).thenReturn(ORDER_NUMBER);
    return new CustomerPortalService.OrderView(order, List.of());
  }

  private static PaymentIntent anIntent() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return PaymentIntent.create(
        UUID.randomUUID(),
        ORG,
        ORDER_ID,
        PaymentProvider.PAYMOB_CARD,
        new BigDecimal("250.00"),
        "EGP",
        now.plusMinutes(20),
        now);
  }

  private static HttpServletRequest req(String method, String pathInfo, CustomerPrincipal p) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn(method);
    when(req.getPathInfo()).thenReturn(pathInfo);
    when(req.getAttribute(CustomerAuthFilter.PRINCIPAL_ATTR)).thenReturn(p);
    return req;
  }

  private static final class Resp {
    final HttpServletResponse mock = Mockito.mock(HttpServletResponse.class);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    int status;

    Resp() throws IOException {
      Mockito.doAnswer(
              inv -> {
                status = inv.getArgument(0);
                return null;
              })
          .when(mock)
          .setStatus(anyInt());
      when(mock.getOutputStream())
          .thenReturn(
              new ServletOutputStream() {
                @Override
                public void write(int b) {
                  out.write(b);
                }

                @Override
                public boolean isReady() {
                  return true;
                }

                @Override
                public void setWriteListener(WriteListener l) {}
              });
    }

    String body() {
      return out.toString(StandardCharsets.UTF_8);
    }
  }
}
