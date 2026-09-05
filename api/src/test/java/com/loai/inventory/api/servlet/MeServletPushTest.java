package com.loai.inventory.api.servlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.common.crypto.P256;
import com.loai.inventory.common.exception.ServiceUnavailableException;
import com.loai.inventory.common.security.VapidKeys;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.ImpersonationTier;
import com.loai.inventory.domain.model.PushSubscription;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.service.PushSubscriptionService;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.push.WebPushConfig;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The {@code /api/me/push*} routes at the servlet boundary: the auth gate, the impersonation
 * refusal, the status codes, and the one shape rule that matters — no read ever echoes the endpoint
 * or the keys.
 */
class MeServletPushTest {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final String SECURITY_CONTEXT_ATTR = "securityContext";
  private static final UUID USER = UUID.randomUUID();
  private static final ObjectMapper JSON = ObjectMapperProvider.build();

  private static final String SUBSCRIBE_BODY =
      "{\"endpoint\":\"https://push.example/sub/abc\",\"keys\":{\"p256dh\":\"AAA\",\"auth\":\"BBB\"},"
          + "\"user_agent\":\"Pixel 8 · Chrome\"}";

  private static SecurityContext self() {
    return new SecurityContext(USER, ActorType.USER, Set.of(), Map.of(), Set.of(), 0);
  }

  private static SecurityContext impersonating() {
    return new SecurityContext(
        USER,
        ActorType.USER,
        Set.of(),
        Map.of(),
        Set.of(),
        0,
        UUID.randomUUID(),
        ImpersonationTier.PLATFORM,
        null,
        false);
  }

  private static PushSubscription row() {
    return new PushSubscription(
        UUID.randomUUID(),
        USER,
        "https://push.example/sub/abc",
        "P256DH-SECRET",
        "AUTH-SECRET",
        "Pixel 8 · Chrome",
        3,
        OffsetDateTime.now(),
        null);
  }

  private static MeServlet servlet(PushSubscriptionService service) {
    return new MeServlet(
        Mockito.mock(UserRepository.class), Mockito.mock(AuthService.class), service, JSON, false);
  }

  @Test
  void config_unauthenticated_is401() throws IOException {
    PushSubscriptionService service = Mockito.mock(PushSubscriptionService.class);
    Resp resp = new Resp();
    servlet(service).doGet(reqWith(null, "/push/config"), resp.mock);
    assertEquals(401, resp.status);
    verify(service, never()).config();
  }

  @Test
  void config_reportsEnabledAndTheKey_orEnabledFalseWithNoKey() throws IOException {
    PushSubscriptionService service = Mockito.mock(PushSubscriptionService.class);
    WebPushConfig on = new WebPushConfig(VapidKeys.of(P256.generate()), "mailto:ops@x.test");
    when(service.config()).thenReturn(on);
    Resp resp = new Resp();
    servlet(service).doGet(reqWith(self(), "/push/config"), resp.mock);
    assertEquals(200, resp.status);
    JsonNode body = JSON.readTree(resp.body.toByteArray());
    assertTrue(body.path("enabled").asBoolean());
    assertEquals(on.publicKeyBase64Url(), body.path("public_key").asText());

    when(service.config()).thenReturn(WebPushConfig.disabled());
    Resp off = new Resp();
    servlet(service).doGet(reqWith(self(), "/push/config"), off.mock);
    JsonNode offBody = JSON.readTree(off.body.toByteArray());
    assertFalse(offBody.path("enabled").asBoolean());
    assertTrue(offBody.has("enabled"), "enabled is a primitive — always on the wire");
    assertFalse(offBody.has("public_key"), "no key when disabled");
  }

  @Test
  void subscribe_impersonating_is403_andNothingIsStored() throws IOException {
    PushSubscriptionService service = Mockito.mock(PushSubscriptionService.class);
    Resp resp = new Resp();
    servlet(service)
        .doPost(reqWith(impersonating(), "/push-subscriptions", SUBSCRIBE_BODY), resp.mock);
    assertEquals(403, resp.status);
    verify(service, never()).subscribe(any(), any(), any(), any(), any());
  }

  @Test
  void subscribe_fresh_is201_andReplayIs200_neverEchoingTheEndpointOrKeys() throws IOException {
    PushSubscriptionService service = Mockito.mock(PushSubscriptionService.class);
    when(service.subscribe(eq(USER), any(), any(), any(), any()))
        .thenReturn(new PushSubscriptionService.SubscribeResult(row(), true))
        .thenReturn(new PushSubscriptionService.SubscribeResult(row(), false));

    Resp first = new Resp();
    servlet(service).doPost(reqWith(self(), "/push-subscriptions", SUBSCRIBE_BODY), first.mock);
    assertEquals(201, first.status);
    String json = first.body.toString(StandardCharsets.UTF_8);
    assertFalse(json.contains("push.example"), "the endpoint never crosses back: " + json);
    assertFalse(json.contains("SECRET"), "the keys never cross back: " + json);
    assertTrue(json.contains("Pixel 8"), "the device label does");
    verify(service)
        .subscribe(USER, "https://push.example/sub/abc", "AAA", "BBB", "Pixel 8 · Chrome");

    Resp second = new Resp();
    servlet(service).doPost(reqWith(self(), "/push-subscriptions", SUBSCRIBE_BODY), second.mock);
    assertEquals(200, second.status);
  }

  @Test
  void subscribe_fallsBackToTheRequestUserAgent_whenTheBodyNamesNone() throws IOException {
    PushSubscriptionService service = Mockito.mock(PushSubscriptionService.class);
    when(service.subscribe(any(), any(), any(), any(), any()))
        .thenReturn(new PushSubscriptionService.SubscribeResult(row(), true));
    HttpServletRequest req =
        reqWith(
            self(),
            "/push-subscriptions",
            "{\"endpoint\":\"https://push.example/sub/abc\",\"keys\":{\"p256dh\":\"A\",\"auth\":\"B\"}}");
    when(req.getHeader("User-Agent")).thenReturn("Mozilla/5.0 (Android)");
    servlet(service).doPost(req, new Resp().mock);
    verify(service)
        .subscribe(USER, "https://push.example/sub/abc", "A", "B", "Mozilla/5.0 (Android)");
  }

  @Test
  void subscribe_whenTheServerHasNoKeyPair_is503() throws IOException {
    PushSubscriptionService service = Mockito.mock(PushSubscriptionService.class);
    when(service.subscribe(any(), any(), any(), any(), any()))
        .thenThrow(new ServiceUnavailableException("Web Push is not configured on this server"));
    Resp resp = new Resp();
    servlet(service).doPost(reqWith(self(), "/push-subscriptions", SUBSCRIBE_BODY), resp.mock);
    assertEquals(503, resp.status);
  }

  @Test
  void unsubscribe_is204_andImpersonatingIs403() throws IOException {
    PushSubscriptionService service = Mockito.mock(PushSubscriptionService.class);
    Resp resp = new Resp();
    servlet(service)
        .doDelete(
            reqWith(
                self(), "/push-subscriptions", "{\"endpoint\":\"https://push.example/sub/abc\"}"),
            resp.mock);
    assertEquals(204, resp.status);
    verify(service).unsubscribe(USER, "https://push.example/sub/abc");

    Resp overlay = new Resp();
    servlet(service)
        .doDelete(
            reqWith(impersonating(), "/push-subscriptions", "{\"endpoint\":\"https://x\"}"),
            overlay.mock);
    assertEquals(403, overlay.status);
    verify(service, never()).unsubscribe(any(), eq("https://x"));
  }

  @Test
  void list_carriesTheDeviceLabelAndTimestamps_andNoCapability() throws IOException {
    PushSubscriptionService service = Mockito.mock(PushSubscriptionService.class);
    when(service.listLive(USER)).thenReturn(List.of(row()));
    Resp resp = new Resp();
    servlet(service).doGet(reqWith(self(), "/push-subscriptions"), resp.mock);
    assertEquals(200, resp.status);
    JsonNode body = JSON.readTree(resp.body.toByteArray());
    assertEquals(1, body.size());
    JsonNode one = body.get(0);
    assertTrue(one.has("id") && one.has("user_agent") && one.has("created_at"));
    assertFalse(one.has("endpoint"));
    assertFalse(one.has("p256dh"));
    assertFalse(one.has("auth"));
    assertFalse(one.toString().contains("SECRET"));
  }

  @Test
  void unknownSubpath_is404() throws IOException {
    PushSubscriptionService service = Mockito.mock(PushSubscriptionService.class);
    Resp resp = new Resp();
    servlet(service).doGet(reqWith(self(), "/push"), resp.mock);
    assertEquals(404, resp.status);
    Resp del = new Resp();
    servlet(service).doDelete(reqWith(self(), "/password"), del.mock);
    assertEquals(404, del.status);
  }

  // servlet doubles (the OrgAdminHandlerAuthTest shape)

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

  private static HttpServletRequest reqWith(SecurityContext ctx, String pathInfo) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    when(req.getPathInfo()).thenReturn(pathInfo);
    return req;
  }

  private static HttpServletRequest reqWith(SecurityContext ctx, String pathInfo, String jsonBody)
      throws IOException {
    HttpServletRequest req = reqWith(ctx, pathInfo);
    byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
    when(req.getContentLength()).thenReturn(bytes.length);
    ByteArrayInputStream backing = new ByteArrayInputStream(bytes);
    when(req.getInputStream())
        .thenReturn(
            new ServletInputStream() {
              @Override
              public int read() {
                return backing.read();
              }

              @Override
              public boolean isFinished() {
                return backing.available() == 0;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener readListener) {}
            });
    return req;
  }
}
