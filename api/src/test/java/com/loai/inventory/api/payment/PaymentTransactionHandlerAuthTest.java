package com.loai.inventory.api.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.loai.inventory.domain.repository.PaymentTransactionRepository.ListFilter;
import com.loai.inventory.domain.repository.PaymentTransactionRepository.ListStats;
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
import java.util.List;
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

  // POST /{id}/not-found — "Can't find it" (stories/payment_claim_not_found.md): MANAGER+.

  @Test
  void notFound_forbiddenForStaff_serviceNeverCalled() throws IOException {
    PaymentTransactionService service = Mockito.mock(PaymentTransactionService.class);
    PaymentTransactionHandler handler =
        new PaymentTransactionHandler(
            service, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle(
        "POST",
        reqWith(ctxWith(OrgRole.STAFF), "{\"reason\":\"NO_TRANSFER\"}"),
        resp.mock,
        ORG,
        "/" + TXN + "/not-found");

    assertEquals(403, resp.status, "STAFF must be forbidden from marking a claim not found");
    verify(service, never()).markClaimNotFound(any(), any(), any(), any(), any());
  }

  @Test
  void notFound_allowedForManager_200() throws IOException {
    PaymentTransactionService service = Mockito.mock(PaymentTransactionService.class);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    PaymentTransaction txn =
        PaymentTransaction.createClaimed(
            TXN,
            ORG,
            PaymentProvider.INSTAPAY_MANUAL,
            "IPN-2",
            new BigDecimal("250.00"),
            "EGP",
            null,
            null,
            null,
            null,
            null,
            now,
            now);
    txn.markNotFound("NO_TRANSFER", "nothing arrived", now);
    when(service.markClaimNotFound(
            eq(ORG), eq(TXN), eq("NO_TRANSFER"), eq("nothing arrived"), any()))
        .thenReturn(new PaymentTransactionService.NotFoundResult(txn, null, null, false));
    PaymentTransactionHandler handler =
        new PaymentTransactionHandler(
            service, com.loai.inventory.api.config.ObjectMapperProvider.build());
    Resp resp = new Resp();

    handler.handle(
        "POST",
        reqWith(
            ctxWith(OrgRole.MANAGER), "{\"reason\":\"NO_TRANSFER\",\"note\":\"nothing arrived\"}"),
        resp.mock,
        ORG,
        "/" + TXN + "/not-found");

    assertEquals(200, resp.status);
    String body = resp.body.toString(StandardCharsets.UTF_8);
    assertTrue(body.contains("\"verification_status\":\"NOT_FOUND\""), body);
    assertTrue(body.contains("\"not_found_note\":\"nothing arrived\""), body);
  }

  // GET / — the ledger filters (stories/transaction_filters.md)

  private static PaymentTransactionHandler handler(PaymentTransactionService service) {
    return new PaymentTransactionHandler(
        service, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  @Test
  void list_bareGet_isTheLedger_withASummaryOnTheEnvelope() throws IOException {
    PaymentTransactionService service = Mockito.mock(PaymentTransactionService.class);
    when(service.list(eq(ORG), any(ListFilter.class), anyInt(), anyInt()))
        .thenReturn(new PaymentTransactionService.TransactionPage(List.of(), 0));
    Resp resp = new Resp();

    handler(service).handle("GET", reqWith(ctxWith(OrgRole.VIEWER), null), resp.mock, ORG, "");

    assertEquals(200, resp.status);
    var captor = org.mockito.ArgumentCaptor.forClass(ListFilter.class);
    verify(service).list(eq(ORG), captor.capture(), anyInt(), anyInt());
    assertTrue(captor.getValue().isEmpty(), "no parameter → the unfiltered ledger");
    String body = resp.body.toString(StandardCharsets.UTF_8);
    assertTrue(body.contains("\"summary\":{\"money_in\":0.00,\"money_out\":0.00}"), body);
  }

  @Test
  void list_filtersReachTheService_asOneFilter() throws IOException {
    PaymentTransactionService service = Mockito.mock(PaymentTransactionService.class);
    when(service.list(eq(ORG), any(ListFilter.class), anyInt(), anyInt()))
        .thenReturn(
            new PaymentTransactionService.TransactionPage(
                List.of(),
                0,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                new ListStats(0, new BigDecimal("14320.00"), new BigDecimal("250.00"))));
    HttpServletRequest req = reqWith(ctxWith(OrgRole.VIEWER), null);
    when(req.getParameter("reconciliation_status")).thenReturn("ORPHAN");
    when(req.getParameter("has_payment")).thenReturn("false");
    when(req.getParameter("provider")).thenReturn("instapay_manual");
    when(req.getParameter("q")).thenReturn("  7766 ");
    when(req.getParameter("from")).thenReturn("2026-09-04T21:00:00Z");
    when(req.getParameter("to")).thenReturn("2026-09-05T21:00:00Z");
    when(req.getParameter("min")).thenReturn("1240");
    when(req.getParameter("max")).thenReturn("1240.00");
    when(req.getParameter("sort")).thenReturn("oldest");
    Resp resp = new Resp();

    handler(service).handle("GET", req, resp.mock, ORG, "");

    assertEquals(200, resp.status);
    var captor = org.mockito.ArgumentCaptor.forClass(ListFilter.class);
    verify(service).list(eq(ORG), captor.capture(), anyInt(), anyInt());
    ListFilter f = captor.getValue();
    assertEquals(PaymentReconciliationStatus.ORPHAN, f.reconciliationStatus());
    assertEquals(Boolean.FALSE, f.hasPayment());
    assertEquals(PaymentProvider.INSTAPAY_MANUAL, f.provider());
    assertEquals("7766", f.q(), "q is trimmed");
    assertEquals(OffsetDateTime.parse("2026-09-04T21:00:00Z"), f.occurredFrom());
    assertEquals(OffsetDateTime.parse("2026-09-05T21:00:00Z"), f.occurredTo());
    assertEquals(0, new BigDecimal("1240").compareTo(f.minAmount()));
    assertEquals(0, new BigDecimal("1240.00").compareTo(f.maxAmount()));
    assertEquals(ListFilter.Sort.OLDEST, f.sort());
    String body = resp.body.toString(StandardCharsets.UTF_8);
    assertTrue(body.contains("\"money_in\":14320.00"), body);
    assertTrue(body.contains("\"money_out\":250.00"), body);
  }

  @Test
  void list_badFilterValues_are400_serviceNeverCalled() throws IOException {
    record Bad(String param, String value, String why) {}
    List<Bad> cases =
        List.of(
            new Bad("from", "2026-09-05", "a bare date is not an ISO-8601 date-time"),
            new Bad("to", "yesterday", "prose is not a date-time"),
            new Bad("sort", "amount", "sort is newest|oldest — the band does amount's job"),
            new Bad("min", "abc", "min must be a number"),
            new Bad("max", "-1", "max must not be negative"),
            new Bad("provider", "VODAFONE_CASH", "provider names the three"));
    for (Bad bad : cases) {
      PaymentTransactionService service = Mockito.mock(PaymentTransactionService.class);
      HttpServletRequest req = reqWith(ctxWith(OrgRole.VIEWER), null);
      when(req.getParameter(bad.param())).thenReturn(bad.value());
      Resp resp = new Resp();

      handler(service).handle("GET", req, resp.mock, ORG, "");

      assertEquals(400, resp.status, bad.why());
      verify(service, never()).list(any(), any(ListFilter.class), anyInt(), anyInt());
    }
  }

  @Test
  void list_invertedWindowOrBounds_are400() throws IOException {
    for (String[] pair :
        List.of(
            new String[] {"from", "2026-09-06T00:00:00Z", "to", "2026-09-05T00:00:00Z"},
            new String[] {"min", "900", "max", "100"})) {
      PaymentTransactionService service = Mockito.mock(PaymentTransactionService.class);
      HttpServletRequest req = reqWith(ctxWith(OrgRole.VIEWER), null);
      when(req.getParameter(pair[0])).thenReturn(pair[1]);
      when(req.getParameter(pair[2])).thenReturn(pair[3]);
      Resp resp = new Resp();

      handler(service).handle("GET", req, resp.mock, ORG, "");

      assertEquals(400, resp.status, pair[0] + " > " + pair[2] + " must be a 400");
      verify(service, never()).list(any(), any(ListFilter.class), anyInt(), anyInt());
    }
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
