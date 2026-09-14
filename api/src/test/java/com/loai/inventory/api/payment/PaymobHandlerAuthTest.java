package com.loai.inventory.api.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.PaymobHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgPaymobConfig;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.OrgPaymobService;
import com.loai.inventory.service.OrgPaymobService.ConnectionStatus;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Runtime auth verification for {@link PaymobHandler}: every verb — including the read — requires
 * OWNER (MANAGER/STAFF/VIEWER get 403, service never called), unlike every other payment-adjacent
 * handler in this system which gates reads at VIEWER; unauthenticated is 401; the resource has no
 * sub-routes, so PUT/PATCH and any path segment after {@code /paymob} are 405, and a malformed body
 * is 400 (not swallowed, not 500).
 */
class PaymobHandlerAuthTest {

  static {
    // OrgPaymobService is final; the Mockito inline mock-maker needs ByteBuddy experimental mode
    // on Java 25 (mirrors PaymentDisputeHandlerAuthTest).
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";
  private static final String CONNECT_BODY =
      "{\"public_key\":\"pk\",\"secret_key\":\"sk\",\"hmac_secret\":\"hs\",\"api_key\":\"ak\","
          + "\"card_integration_id\":123,\"region\":\"EGYPT\"}";

  private SecurityContext ctxWith(OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(ORG, Set.of(role)), Set.of(), 0);
  }

  private ConnectionStatus aStatus() {
    return new ConnectionStatus(
        true,
        OrgPaymobConfig.Status.ACTIVE,
        "pk",
        123,
        "EGYPT",
        true,
        OffsetDateTime.now(),
        OffsetDateTime.now());
  }

  private PaymobHandler handler(OrgPaymobService service) {
    return new PaymobHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  @Test
  void get_forbiddenForManager_serviceNeverCalled() throws IOException {
    OrgPaymobService service = Mockito.mock(OrgPaymobService.class);
    Resp resp = new Resp();

    handler(service).handle("GET", reqWith(ctxWith(OrgRole.MANAGER), null), resp.mock, ORG, "");

    assertEquals(403, resp.status, "MANAGER must be forbidden from reading (requires OWNER)");
    verify(service, never()).status(any());
  }

  @Test
  void get_forbiddenForViewer_serviceNeverCalled() throws IOException {
    OrgPaymobService service = Mockito.mock(OrgPaymobService.class);
    Resp resp = new Resp();

    handler(service).handle("GET", reqWith(ctxWith(OrgRole.VIEWER), null), resp.mock, ORG, "");

    assertEquals(403, resp.status, "VIEWER must be forbidden from reading (requires OWNER)");
    verify(service, never()).status(any());
  }

  @Test
  void get_allowedForOwner_serviceCalled() throws IOException {
    OrgPaymobService service = Mockito.mock(OrgPaymobService.class);
    when(service.status(ORG)).thenReturn(aStatus());
    Resp resp = new Resp();

    handler(service).handle("GET", reqWith(ctxWith(OrgRole.OWNER), null), resp.mock, ORG, "");

    assertEquals(200, resp.status, "OWNER read must succeed");
    verify(service).status(ORG);
  }

  @Test
  void connect_forbiddenForManager_serviceNeverCalled() throws IOException {
    OrgPaymobService service = Mockito.mock(OrgPaymobService.class);
    Resp resp = new Resp();

    handler(service)
        .handle("POST", reqWith(ctxWith(OrgRole.MANAGER), CONNECT_BODY), resp.mock, ORG, "");

    assertEquals(403, resp.status, "MANAGER must be forbidden from connect (requires OWNER)");
    verify(service, never()).connect(any(), any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void connect_allowedForOwner_serviceCalled() throws IOException {
    OrgPaymobService service = Mockito.mock(OrgPaymobService.class);
    when(service.connect(eq(ORG), any(), any(), any(), any(), eq(123), eq("EGYPT")))
        .thenReturn(aStatus());
    Resp resp = new Resp();

    handler(service)
        .handle("POST", reqWith(ctxWith(OrgRole.OWNER), CONNECT_BODY), resp.mock, ORG, "");

    assertEquals(200, resp.status, "OWNER connect must succeed");
    verify(service).connect(eq(ORG), eq("pk"), eq("sk"), eq("hs"), eq("ak"), eq(123), eq("EGYPT"));
  }

  @Test
  void disconnect_forbiddenForManager_serviceNeverCalled() throws IOException {
    OrgPaymobService service = Mockito.mock(OrgPaymobService.class);
    Resp resp = new Resp();

    handler(service).handle("DELETE", reqWith(ctxWith(OrgRole.MANAGER), null), resp.mock, ORG, "");

    assertEquals(403, resp.status, "MANAGER must be forbidden from disconnect (requires OWNER)");
    verify(service, never()).disconnect(any());
  }

  @Test
  void disconnect_allowedForOwner_serviceCalled() throws IOException {
    OrgPaymobService service = Mockito.mock(OrgPaymobService.class);
    Resp resp = new Resp();

    handler(service).handle("DELETE", reqWith(ctxWith(OrgRole.OWNER), null), resp.mock, ORG, "");

    assertEquals(204, resp.status, "OWNER disconnect must succeed");
    verify(service).disconnect(ORG);
  }

  @Test
  void get_forbiddenForOwnerOfAnotherOrg_serviceNeverCalled() throws IOException {
    OrgPaymobService service = Mockito.mock(OrgPaymobService.class);
    Resp resp = new Resp();
    SecurityContext ownerOfAnotherOrg =
        new SecurityContext(
            UUID.randomUUID(),
            ActorType.USER,
            Set.of(),
            Map.of(UUID.randomUUID(), Set.of(OrgRole.OWNER)),
            Set.of(),
            0);

    handler(service).handle("GET", reqWith(ownerOfAnotherOrg, null), resp.mock, ORG, "");

    assertEquals(403, resp.status, "OWNER of a different org must be forbidden here");
    verify(service, never()).status(any());
  }

  @Test
  void unauthenticated_is401_serviceNeverCalled() throws IOException {
    OrgPaymobService service = Mockito.mock(OrgPaymobService.class);
    Resp resp = new Resp();

    handler(service).handle("GET", reqWith(null, null), resp.mock, ORG, "");

    assertTrue(resp.status == 401, "missing auth must be 401, was " + resp.status);
    verify(service, never()).status(any());
  }

  @Test
  void put_is405_notAllowed() throws IOException {
    OrgPaymobService service = Mockito.mock(OrgPaymobService.class);
    Resp resp = new Resp();

    // Resolved before auth — this resource defines no PUT verb, regardless of role.
    handler(service)
        .handle("PUT", reqWith(ctxWith(OrgRole.OWNER), CONNECT_BODY), resp.mock, ORG, "");

    assertEquals(405, resp.status, "PUT must be 405, not 400 or 404");
    verify(service, never()).connect(any(), any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void subPath_is405_notFoundNorRouted() throws IOException {
    OrgPaymobService service = Mockito.mock(OrgPaymobService.class);
    Resp resp = new Resp();

    // Unlike WhatsAppHandler's /enable /disable, this resource has no sub-routes at all.
    handler(service)
        .handle("POST", reqWith(ctxWith(OrgRole.OWNER), CONNECT_BODY), resp.mock, ORG, "/enable");

    assertEquals(405, resp.status, "any /paymob/... sub-path must be 405");
    verify(service, never()).connect(any(), any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void connect_malformedJsonBody_is400_notSwallowedNor500() throws IOException {
    OrgPaymobService service = Mockito.mock(OrgPaymobService.class);
    Resp resp = new Resp();

    handler(service)
        .handle("POST", reqWith(ctxWith(OrgRole.OWNER), "{ not json"), resp.mock, ORG, "");

    assertEquals(400, resp.status, "malformed JSON must be 400 (not 500, not a swallowed null)");
    verify(service, never()).connect(any(), any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void connect_nonNumericCardIntegrationId_is400() throws IOException {
    OrgPaymobService service = Mockito.mock(OrgPaymobService.class);
    Resp resp = new Resp();

    String body =
        "{\"public_key\":\"pk\",\"secret_key\":\"sk\",\"hmac_secret\":\"hs\",\"api_key\":\"ak\","
            + "\"card_integration_id\":\"abc\",\"region\":\"EGYPT\"}";
    handler(service).handle("POST", reqWith(ctxWith(OrgRole.OWNER), body), resp.mock, ORG, "");

    assertEquals(400, resp.status, "a non-numeric card_integration_id must be 400, not 500");
    verify(service, never()).connect(any(), any(), any(), any(), any(), anyInt(), any());
  }

  @Test
  void connect_emptyBody_is400_notNullPointer() throws IOException {
    OrgPaymobService service = Mockito.mock(OrgPaymobService.class);
    Resp resp = new Resp();

    handler(service).handle("POST", reqWith(ctxWith(OrgRole.OWNER), ""), resp.mock, ORG, "");

    assertEquals(400, resp.status, "empty body must be 400 — connect always requires a body");
    verify(service, never()).connect(any(), any(), any(), any(), any(), anyInt(), any());
  }

  // harness (mirrors PaymentDisputeHandlerAuthTest)

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
