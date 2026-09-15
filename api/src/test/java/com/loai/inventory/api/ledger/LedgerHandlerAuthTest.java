package com.loai.inventory.api.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.loai.inventory.api.servlet.handler.LedgerHandler;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.ledger.AccountStatement;
import com.loai.inventory.domain.model.ledger.AccountType;
import com.loai.inventory.domain.model.ledger.JournalPage;
import com.loai.inventory.domain.model.ledger.LedgerChart;
import com.loai.inventory.domain.model.ledger.LedgerHealth;
import com.loai.inventory.domain.model.ledger.PostingSummary;
import com.loai.inventory.domain.model.ledger.Side;
import com.loai.inventory.domain.model.ledger.TrialBalanceRow;
import com.loai.inventory.service.LedgerService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Runtime auth + routing for {@code /api/orgs/{orgId}/ledger/*} (stories/general_ledger.md): every
 * read is MANAGER-plane (anon 401, non-member 403, VIEWER/STAFF 403, MANAGER 200), the rebuild is
 * OWNER-only, a mutating verb on a read route is 405, an unknown route 404, a service {@link
 * ValidationException} 400, and the statement route hands the account code from the path to the
 * service.
 */
class LedgerHandlerAuthTest {

  static {
    // LedgerService is final; the Mockito inline mock-maker needs ByteBuddy experimental on Java
    // 25.
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID OTHER_ORG = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";
  private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);
  private static final String[] READS = {
    "/trial-balance", "/journal", "/health", "/accounts/1100/lines"
  };

  private static SecurityContext ctxWith(UUID orgId, OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(orgId, Set.of(role)), Set.of(), 0);
  }

  private static LedgerHandler handler(LedgerService service) {
    return new LedgerHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  private static LedgerService stubbedService() {
    LedgerService service = Mockito.mock(LedgerService.class);
    LedgerChart.Account cash = LedgerChart.byCode(LedgerChart.CASH);
    when(service.trialBalance(any(), any(), any()))
        .thenReturn(
            new LedgerService.TrialBalance(
                NOW,
                NOW,
                List.of(
                    new TrialBalanceRow(
                        cash.code(),
                        cash.name(),
                        AccountType.ASSET,
                        Side.DR,
                        BigDecimal.ZERO,
                        new BigDecimal("30.00"),
                        new BigDecimal("30.00"),
                        BigDecimal.ZERO))));
    when(service.journal(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new LedgerService.Journal(
                NOW, NOW, null, new LedgerService.Page(0, 50), new JournalPage(List.of(), 0)));
    when(service.statement(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new LedgerService.Statement(
                NOW,
                NOW,
                new LedgerService.Page(0, 50),
                new AccountStatement(cash, BigDecimal.ZERO, List.of(), 0L, BigDecimal.ZERO)));
    when(service.health(any()))
        .thenReturn(new LedgerHealth(0, 0, Map.of(), Map.of(), 0, List.of(), null));
    when(service.rebuild(any(), Mockito.anyBoolean()))
        .thenReturn(new LedgerService.Rebuilt(3, new PostingSummary(Map.of("INVOICE/ISSUED", 3))));
    return service;
  }

  @Test
  void anonymousIs401_onEveryRead() throws IOException {
    LedgerService service = stubbedService();
    for (String route : READS) {
      Resp resp = new Resp();
      handler(service).handle("GET", reqWith(null, Map.of()), resp.mock, ORG, route);
      assertEquals(401, resp.status, route);
    }
    verify(service, never()).trialBalance(any(), any(), any());
  }

  @Test
  void nonMemberViewerAndStaffAre403_managerIs200() throws IOException {
    LedgerService service = stubbedService();
    for (String route : READS) {
      for (OrgRole below : new OrgRole[] {OrgRole.VIEWER, OrgRole.STAFF}) {
        Resp resp = new Resp();
        handler(service)
            .handle("GET", reqWith(ctxWith(ORG, below), Map.of()), resp.mock, ORG, route);
        assertEquals(403, resp.status, route + " as " + below);
      }
      Resp outsider = new Resp();
      handler(service)
          .handle(
              "GET",
              reqWith(ctxWith(OTHER_ORG, OrgRole.OWNER), Map.of()),
              outsider.mock,
              ORG,
              route);
      assertEquals(403, outsider.status, route + " as a member of another org");

      Resp ok = new Resp();
      handler(service)
          .handle("GET", reqWith(ctxWith(ORG, OrgRole.MANAGER), Map.of()), ok.mock, ORG, route);
      assertEquals(200, ok.status, route + " as MANAGER");
    }
  }

  @Test
  void trialBalance_serialisesTotalsAndRows() throws IOException {
    Resp resp = new Resp();
    handler(stubbedService())
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.OWNER), Map.of()),
            resp.mock,
            ORG,
            "/trial-balance");
    JsonNode body = json(resp);
    // Money crosses as a bare scale-2 number; the reader parses it as a double, so compare values.
    assertEquals(0, new BigDecimal("30.00").compareTo(body.get("total_debit").decimalValue()));
    assertEquals(0, new BigDecimal("30.00").compareTo(body.get("total_credit").decimalValue()));
    assertEquals("1000", body.get("accounts").get(0).get("code").asText());
    assertEquals("ASSET", body.get("accounts").get(0).get("type").asText());
    assertEquals("DR", body.get("accounts").get(0).get("normal_side").asText());
  }

  @Test
  void statementRoute_handsTheCodeFromThePathToTheService() throws IOException {
    LedgerService service = stubbedService();
    Resp resp = new Resp();
    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.MANAGER), Map.of("page", "2", "size", "10")),
            resp.mock,
            ORG,
            "/accounts/1200/lines");
    assertEquals(200, resp.status);
    verify(service).statement(eq(ORG), eq("1200"), any(), any(), eq("2"), eq("10"));
    assertEquals("1000", json(resp).get("account").get("code").asText());
  }

  @Test
  void mutatingVerbOnAReadRouteIs405_beforeAuth() throws IOException {
    LedgerService service = stubbedService();
    for (String route : READS) {
      Resp resp = new Resp();
      handler(service).handle("POST", reqWith(null, Map.of()), resp.mock, ORG, route);
      assertEquals(405, resp.status, route);
    }
    Resp get = new Resp();
    handler(service)
        .handle("GET", reqWith(ctxWith(ORG, OrgRole.OWNER), Map.of()), get.mock, ORG, "/rebuild");
    assertEquals(405, get.status, "GET /rebuild");
  }

  @Test
  void unknownRouteIs404() throws IOException {
    for (String route :
        new String[] {"", "/", "/accounts", "/accounts/12/lines", "/trial-balance/x"}) {
      Resp resp = new Resp();
      handler(stubbedService())
          .handle("GET", reqWith(ctxWith(ORG, OrgRole.OWNER), Map.of()), resp.mock, ORG, route);
      assertEquals(404, resp.status, "'" + route + "'");
    }
  }

  @Test
  void rebuild_isOwnerOnly_andPassesReset() throws IOException {
    LedgerService service = stubbedService();
    Resp manager = new Resp();
    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(ORG, OrgRole.MANAGER), Map.of()),
            manager.mock,
            ORG,
            "/rebuild");
    assertEquals(403, manager.status, "MANAGER cannot rebuild");
    verify(service, never()).rebuild(any(), Mockito.anyBoolean());

    Resp owner = new Resp();
    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(ORG, OrgRole.OWNER), Map.of("reset", "true")),
            owner.mock,
            ORG,
            "/rebuild");
    assertEquals(200, owner.status);
    verify(service).rebuild(ORG, true);
    JsonNode body = json(owner);
    assertEquals(true, body.get("reset").asBoolean());
    assertEquals(3, body.get("posted").asInt());
    assertEquals(3, body.get("posted_by_kind").get("INVOICE/ISSUED").asInt());
  }

  @Test
  void serviceValidationSurfacesAs400() throws IOException {
    LedgerService service = stubbedService();
    when(service.journal(any(), any(), any(), any(), any(), any()))
        .thenThrow(new ValidationException("'account' is not a ledger account code: 9999"));
    Resp resp = new Resp();
    handler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.MANAGER), Map.of("account", "9999")),
            resp.mock,
            ORG,
            "/journal");
    assertEquals(400, resp.status);
  }

  // harness (mirrors ReportsHandlerAuthTest)

  private static JsonNode json(Resp resp) throws IOException {
    return com.loai.inventory.api.config.ObjectMapperProvider.build()
        .readTree(resp.body.toByteArray());
  }

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

  private static HttpServletRequest reqWith(SecurityContext ctx, Map<String, String> params) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    when(req.getParameter(Mockito.anyString()))
        .thenAnswer(inv -> params.get(inv.<String>getArgument(0)));
    return req;
  }
}
