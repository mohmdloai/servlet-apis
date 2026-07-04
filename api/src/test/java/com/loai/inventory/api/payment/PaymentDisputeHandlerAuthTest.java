package com.loai.inventory.api.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.PaymentHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.PaymentDisputeService;
import com.loai.inventory.service.PaymentDisputeService.PaymentView;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Runtime auth verification for {@link PaymentHandler}: dispute/uphold change money state, so they
 * require MANAGER (VIEWER/STAFF get 403, service never called); the read route requires VIEWER.
 */
class PaymentDisputeHandlerAuthTest {

  static {
    // PaymentDisputeService is final; the Mockito inline mock-maker needs ByteBuddy experimental
    // mode on Java 25 (mirrors SalesOrderCancelAuthTest).
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID PAYMENT = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";
  private static final String BODY = "{\"reason\":\"unauthorized\"}";

  private SecurityContext ctxWith(OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(ORG, Set.of(role)), Set.of(), 0);
  }

  private Payment aPayment() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return Payment.createReceived(
        PAYMENT,
        ORG,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        new BigDecimal("30.00"),
        "EGP",
        now);
  }

  private PaymentHandler handler(PaymentDisputeService service) {
    return new PaymentHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  @Test
  void dispute_forbiddenForStaff_serviceNeverCalled() throws IOException {
    PaymentDisputeService service = Mockito.mock(PaymentDisputeService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.STAFF), BODY),
            resp.mock,
            ORG,
            "/" + PAYMENT + "/dispute");

    assertEquals(403, resp.status, "STAFF must be forbidden from dispute (requires MANAGER)");
    verify(service, never()).dispute(any(), any(), any(), any());
  }

  @Test
  void dispute_allowedForManager_serviceCalled() throws IOException {
    PaymentDisputeService service = Mockito.mock(PaymentDisputeService.class);
    when(service.dispute(eq(ORG), eq(PAYMENT), any(), any())).thenReturn(aPayment());
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.MANAGER), BODY),
            resp.mock,
            ORG,
            "/" + PAYMENT + "/dispute");

    assertEquals(200, resp.status, "MANAGER dispute must succeed");
    verify(service).dispute(eq(ORG), eq(PAYMENT), any(), any());
  }

  @Test
  void uphold_forbiddenForViewer_serviceNeverCalled() throws IOException {
    PaymentDisputeService service = Mockito.mock(PaymentDisputeService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.VIEWER), ""),
            resp.mock,
            ORG,
            "/" + PAYMENT + "/uphold");

    assertEquals(403, resp.status, "VIEWER must be forbidden from uphold (requires MANAGER)");
    verify(service, never()).uphold(any(), any(), any());
  }

  @Test
  void get_allowedForViewer_serviceCalled() throws IOException {
    PaymentDisputeService service = Mockito.mock(PaymentDisputeService.class);
    when(service.get(eq(ORG), eq(PAYMENT)))
        .thenReturn(new PaymentView(aPayment(), java.util.List.of()));
    Resp resp = new Resp();

    handler(service)
        .handle("GET", reqWith(ctxWith(OrgRole.VIEWER), ""), resp.mock, ORG, "/" + PAYMENT);

    assertEquals(200, resp.status, "VIEWER read must succeed");
    verify(service).get(eq(ORG), eq(PAYMENT));
  }

  @Test
  void dispute_unauthenticated_is401_serviceNeverCalled() throws IOException {
    PaymentDisputeService service = Mockito.mock(PaymentDisputeService.class);
    Resp resp = new Resp();

    handler(service)
        .handle("POST", reqWith(null, BODY), resp.mock, ORG, "/" + PAYMENT + "/dispute");

    assertTrue(resp.status == 401, "missing auth must be 401, was " + resp.status);
    verify(service, never()).dispute(any(), any(), any(), any());
  }

  @Test
  void dispute_wrongVerb_is405_notValidation400() throws IOException {
    PaymentDisputeService service = Mockito.mock(PaymentDisputeService.class);
    Resp resp = new Resp();

    // GET on a known action route → 405 (the resource exists, the verb doesn't), resolved before
    // auth, so no SecurityContext is needed.
    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(OrgRole.MANAGER), ""),
            resp.mock,
            ORG,
            "/" + PAYMENT + "/dispute");

    assertEquals(405, resp.status, "GET on /dispute must be 405, not 400");
    verify(service, never()).dispute(any(), any(), any(), any());
  }

  @Test
  void dispute_malformedJsonBody_is400_notSwallowedNor500() throws IOException {
    PaymentDisputeService service = Mockito.mock(PaymentDisputeService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.MANAGER), "{ not json"),
            resp.mock,
            ORG,
            "/" + PAYMENT + "/dispute");

    assertEquals(400, resp.status, "malformed JSON must be 400 (not 500, not a swallowed null)");
    verify(service, never()).dispute(any(), any(), any(), any());
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
