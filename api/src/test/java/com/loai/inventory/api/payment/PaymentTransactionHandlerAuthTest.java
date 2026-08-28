package com.loai.inventory.api.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.PaymentTransactionHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.PaymentService.OrderRef;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.VerifyResult;
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
 * Runtime auth verification for {@link PaymentTransactionHandler}'s orphan-resolution route ({@code
 * POST /payment-transactions/{id}/resolve}): drives {@code handle(...)} directly with a {@link
 * SecurityContext} on the request, mocking the service. The IT bypasses the handler, so this is the
 * only place the MANAGER gate is exercised at runtime.
 */
class PaymentTransactionHandlerAuthTest {

  static {
    // PaymentTransactionService is final; the Mockito inline mock-maker needs ByteBuddy
    // experimental
    // mode on Java 25. Set before any mock is created (mirrors InvoiceHandlerAuthTest).
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID TXN = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";
  private static final String BODY = "{\"order_number\":\"SO-2026-00001\"}";

  private SecurityContext ctxWith(OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(ORG, Set.of(role)), Set.of(), 0);
  }

  private VerifyResult aMatchedResult() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    PaymentTransaction txn =
        PaymentTransaction.createClaimed(
            TXN,
            ORG,
            PaymentProvider.INSTAPAY_MANUAL,
            "IPN-1",
            new BigDecimal("250.00"),
            "EGP",
            null,
            null,
            null,
            null,
            null,
            now,
            now);
    txn.verify(UUID.randomUUID(), now);
    txn.applyReconciliation(PaymentReconciliationStatus.MATCHED, now);
    return new VerifyResult(txn, PaymentReconciliationStatus.MATCHED, null, null, false);
  }

  @Test
  void resolve_forbiddenForViewer_serviceNeverCalled() throws IOException {
    PaymentTransactionService service = Mockito.mock(PaymentTransactionService.class);
    PaymentTransactionHandler handler =
        new PaymentTransactionHandler(
            service, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle(
        "POST", reqWith(ctxWith(OrgRole.VIEWER), BODY), resp.mock, ORG, "/" + TXN + "/resolve");

    assertEquals(403, resp.status, "VIEWER must be forbidden from orphan resolution");
    verify(service, never()).resolveOrphan(any(), any(), any(), any());
  }

  @Test
  void resolve_forbiddenForStaff_serviceNeverCalled() throws IOException {
    PaymentTransactionService service = Mockito.mock(PaymentTransactionService.class);
    PaymentTransactionHandler handler =
        new PaymentTransactionHandler(
            service, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle(
        "POST", reqWith(ctxWith(OrgRole.STAFF), BODY), resp.mock, ORG, "/" + TXN + "/resolve");

    assertEquals(
        403, resp.status, "STAFF must be forbidden from orphan resolution (requires MANAGER)");
    verify(service, never()).resolveOrphan(any(), any(), any(), any());
  }

  @Test
  void resolve_allowedForManager_serviceCalled() throws IOException {
    PaymentTransactionService service = Mockito.mock(PaymentTransactionService.class);
    when(service.resolveOrphan(eq(ORG), eq(TXN), any(OrderRef.class), any()))
        .thenReturn(aMatchedResult());
    PaymentTransactionHandler handler =
        new PaymentTransactionHandler(
            service, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle(
        "POST", reqWith(ctxWith(OrgRole.MANAGER), BODY), resp.mock, ORG, "/" + TXN + "/resolve");

    assertEquals(201, resp.status, "MANAGER orphan resolution must succeed (201 Created)");
    verify(service).resolveOrphan(eq(ORG), eq(TXN), any(OrderRef.class), any());
  }

  // POST /{id}/verify — "Found it" on a shopper claim (stories/payment_claim_verify.md). Money-
  // shaped like /resolve: MANAGER+. STAFF see the claim and ask a manager.

  @Test
  void verifyClaim_forbiddenForStaff_serviceNeverCalled() throws IOException {
    PaymentTransactionService service = Mockito.mock(PaymentTransactionService.class);
    PaymentTransactionHandler handler =
        new PaymentTransactionHandler(
            service, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle(
        "POST", reqWith(ctxWith(OrgRole.STAFF), "{}"), resp.mock, ORG, "/" + TXN + "/verify");

    assertEquals(403, resp.status, "STAFF must be forbidden from verifying a claim");
    verify(service, never()).verifyClaim(any(), any(), any(), any());
  }

  @Test
  void verifyClaim_forbiddenForViewer_serviceNeverCalled() throws IOException {
    PaymentTransactionService service = Mockito.mock(PaymentTransactionService.class);
    PaymentTransactionHandler handler =
        new PaymentTransactionHandler(
            service, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle(
        "POST", reqWith(ctxWith(OrgRole.VIEWER), ""), resp.mock, ORG, "/" + TXN + "/verify");

    assertEquals(403, resp.status);
    verify(service, never()).verifyClaim(any(), any(), any(), any());
  }

  @Test
  void verifyClaim_allowedForManager_emptyBodyIsFine_201() throws IOException {
    PaymentTransactionService service = Mockito.mock(PaymentTransactionService.class);
    VerifyResult result = aMatchedResult();
    when(service.verifyClaim(eq(ORG), eq(TXN), any(), any())).thenReturn(result);
    when(service.contextOf(eq(ORG), any()))
        .thenReturn(new PaymentTransactionService.ClaimContext(null, null, "Sara"));
    PaymentTransactionHandler handler =
        new PaymentTransactionHandler(
            service, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    // No body at all — the claim carries the reference, the amount and the order already.
    handler.handle(
        "POST", reqWith(ctxWith(OrgRole.MANAGER), ""), resp.mock, ORG, "/" + TXN + "/verify");

    assertEquals(201, resp.status, "MANAGER verify must succeed (201 Created)");
    verify(service).verifyClaim(eq(ORG), eq(TXN), any(), any());
    String body = resp.body.toString(StandardCharsets.UTF_8);
    assertTrue(body.contains("\"verified_by_name\":\"Sara\""), body);
  }

  @Test
  void verifyClaim_replay_is200() throws IOException {
    PaymentTransactionService service = Mockito.mock(PaymentTransactionService.class);
    VerifyResult fresh = aMatchedResult();
    VerifyResult replay =
        new VerifyResult(fresh.transaction(), fresh.reconciliationStatus(), null, null, true);
    when(service.verifyClaim(eq(ORG), eq(TXN), any(), any())).thenReturn(replay);
    when(service.contextOf(eq(ORG), any()))
        .thenReturn(new PaymentTransactionService.ClaimContext(null, null, null));
    PaymentTransactionHandler handler =
        new PaymentTransactionHandler(
            service, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle(
        "POST",
        reqWith(ctxWith(OrgRole.MANAGER), "{\"amount\": 250}"),
        resp.mock,
        ORG,
        "/" + TXN + "/verify");

    assertEquals(200, resp.status, "an already-verified claim replays 200");
  }

  @Test
  void resolve_unauthenticated_is401_serviceNeverCalled() throws IOException {
    PaymentTransactionService service = Mockito.mock(PaymentTransactionService.class);
    PaymentTransactionHandler handler =
        new PaymentTransactionHandler(
            service, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle("POST", reqWith(null, BODY), resp.mock, ORG, "/" + TXN + "/resolve");

    assertTrue(resp.status == 401, "missing auth must be 401, was " + resp.status);
    verify(service, never()).resolveOrphan(any(), any(), any(), any());
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
