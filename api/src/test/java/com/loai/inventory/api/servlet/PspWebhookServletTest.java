package com.loai.inventory.api.servlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.service.PaymobWebhookService;
import com.loai.inventory.service.PaymobWebhookService.Outcome;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The webhook servlet's contract with Paymob's retry loop ({@code
 * stories/paymob_card_checkout.md}): the route, the {@code ?hmac=} read from the query string
 * (never the body), and the code mapping — REJECTED → 400, every other outcome → 200, a thrown
 * transient failure → 500 so it IS retried.
 */
class PspWebhookServletTest {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final String BODY = "{\"type\":\"TRANSACTION\",\"obj\":{\"id\":1}}";

  private PspWebhookServlet servlet(PaymobWebhookService service) {
    return new PspWebhookServlet(service, ObjectMapperProvider.build());
  }

  @Test
  void post_passesOrgBodyAndQueryHmac_toTheService_and200sOnSettled() throws IOException {
    PaymobWebhookService service = Mockito.mock(PaymobWebhookService.class);
    when(service.handle(eq(ORG), eq(BODY), eq("abc123")))
        .thenReturn(new Outcome(Outcome.Kind.SETTLED, "MATCHED"));
    Resp resp = new Resp();

    servlet(service)
        .service(req("POST", "/paymob/" + ORG + "/webhook", "hmac=abc123", BODY), resp.mock);

    assertEquals(200, resp.status);
    assertTrue(resp.body().contains("\"SETTLED\""));
    verify(service).handle(ORG, BODY, "abc123");
  }

  @Test
  void rejected_is400_ignoredReplayedOrphanFailed_are200() throws IOException {
    for (Outcome.Kind kind : Outcome.Kind.values()) {
      PaymobWebhookService service = Mockito.mock(PaymobWebhookService.class);
      when(service.handle(any(), any(), any())).thenReturn(new Outcome(kind, "why"));
      Resp resp = new Resp();

      servlet(service)
          .service(req("POST", "/paymob/" + ORG + "/webhook", "hmac=x", BODY), resp.mock);

      assertEquals(kind == Outcome.Kind.REJECTED ? 400 : 200, resp.status, kind.name());
    }
  }

  @Test
  void transientFailure_is500_soPaymobRetries() throws IOException {
    PaymobWebhookService service = Mockito.mock(PaymobWebhookService.class);
    when(service.handle(any(), any(), any())).thenThrow(new IllegalStateException("db down"));
    Resp resp = new Resp();

    servlet(service).service(req("POST", "/paymob/" + ORG + "/webhook", "hmac=x", BODY), resp.mock);

    assertEquals(500, resp.status);
  }

  @Test
  void wrongRoute_is404_wrongVerb_is405_badOrgId_is400_serviceNeverCalled() throws IOException {
    PaymobWebhookService service = Mockito.mock(PaymobWebhookService.class);

    Resp r1 = new Resp();
    servlet(service).service(req("POST", "/stripe/" + ORG + "/webhook", null, BODY), r1.mock);
    assertEquals(404, r1.status);

    Resp r2 = new Resp();
    servlet(service).service(req("POST", "/paymob/" + ORG, null, BODY), r2.mock);
    assertEquals(404, r2.status);

    Resp r3 = new Resp();
    servlet(service).service(req("GET", "/paymob/" + ORG + "/webhook", "hmac=x", ""), r3.mock);
    assertEquals(405, r3.status);

    Resp r4 = new Resp();
    servlet(service).service(req("POST", "/paymob/not-a-uuid/webhook", "hmac=x", BODY), r4.mock);
    assertEquals(400, r4.status);

    verify(service, never()).handle(any(), any(), any());
  }

  @Test
  void absentHmac_isPassedAsNull_theServiceDecides() throws IOException {
    PaymobWebhookService service = Mockito.mock(PaymobWebhookService.class);
    when(service.handle(eq(ORG), eq(BODY), eq(null)))
        .thenReturn(new Outcome(Outcome.Kind.REJECTED, "signature mismatch"));
    Resp resp = new Resp();

    servlet(service).service(req("POST", "/paymob/" + ORG + "/webhook", null, BODY), resp.mock);

    assertEquals(400, resp.status);
    verify(service).handle(ORG, BODY, null);
  }

  @Test
  void queryParam_readsFirstValue_decodes_ignoresOthers() {
    assertEquals("abc", PspWebhookServlet.queryParam("hmac=abc", "hmac"));
    assertEquals("abc", PspWebhookServlet.queryParam("x=1&hmac=abc&y=2", "hmac"));
    assertEquals("a b", PspWebhookServlet.queryParam("hmac=a%20b", "hmac"));
    assertEquals("", PspWebhookServlet.queryParam("hmac=", "hmac"));
    assertNull(PspWebhookServlet.queryParam("other=1", "hmac"));
    assertNull(PspWebhookServlet.queryParam(null, "hmac"));
  }

  // helpers

  private static HttpServletRequest req(String method, String pathInfo, String query, String body)
      throws IOException {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn(method);
    when(req.getPathInfo()).thenReturn(pathInfo);
    when(req.getQueryString()).thenReturn(query);
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    when(req.getInputStream())
        .thenReturn(
            new ServletInputStream() {
              private final ByteArrayInputStream in = new ByteArrayInputStream(bytes);

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

  private static final class Resp {
    final HttpServletResponse mock;
    final ByteArrayOutputStream sink = new ByteArrayOutputStream();
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
                  sink.write(b);
                }

                @Override
                public boolean isReady() {
                  return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {}
              });
    }

    String body() {
      return sink.toString(StandardCharsets.UTF_8);
    }
  }
}
