package com.loai.inventory.service.storefront;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.service.storefront.StorefrontRevalidator.Scope;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The wire contract of stories/storefront_revalidate_hook.md, against a local stub. */
class JdkStorefrontRevalidatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  record Seen(
      String method,
      String path,
      String authorization,
      String contentType,
      String upgrade,
      String body) {}

  private String stub(int status, AtomicReference<Seen> seen) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          seen.set(
              new Seen(
                  ex.getRequestMethod(),
                  ex.getRequestURI().getPath(),
                  ex.getRequestHeaders().getFirst("Authorization"),
                  ex.getRequestHeaders().getFirst("Content-Type"),
                  ex.getRequestHeaders().getFirst("Upgrade"),
                  body));
          ex.sendResponseHeaders(status, -1);
          ex.close();
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/revalidate";
  }

  @Test
  void postsTheContract_bearerSecretAndExactJsonBody() throws Exception {
    AtomicReference<Seen> seen = new AtomicReference<>();
    String url = stub(200, seen);

    new JdkStorefrontRevalidator(MAPPER, url, "s3cret")
        .send("mart-cairo", Set.of(Scope.PROFILE))
        .get(5, TimeUnit.SECONDS);

    Seen s = seen.get();
    assertEquals("POST", s.method());
    assertEquals("/api/revalidate", s.path());
    assertEquals("Bearer s3cret", s.authorization());
    assertEquals("application/json", s.contentType());
    assertEquals("{\"org_slug\":\"mart-cairo\",\"scopes\":[\"profile\"]}", s.body());
    // Plain HTTP/1.1 — an `Upgrade: h2c` offer makes the storefront's Node server drop the call.
    assertNull(s.upgrade());
  }

  @Test
  void scopesGoOutInTheContractsOrder() throws Exception {
    AtomicReference<Seen> seen = new AtomicReference<>();
    String url = stub(200, seen);

    new JdkStorefrontRevalidator(MAPPER, url, "s")
        .send("acme", EnumSet.of(Scope.REVIEWS, Scope.PROFILE, Scope.CATALOG))
        .get(5, TimeUnit.SECONDS);

    assertEquals(
        "{\"org_slug\":\"acme\",\"scopes\":[\"profile\",\"catalog\",\"reviews\"]}",
        seen.get().body());
  }

  @Test
  void aRefusal_neverThrows() throws Exception {
    for (int status : new int[] {400, 401, 500, 503}) {
      AtomicReference<Seen> seen = new AtomicReference<>();
      String url = stub(status, seen);
      JdkStorefrontRevalidator r = new JdkStorefrontRevalidator(MAPPER, url, "s");
      assertDoesNotThrow(() -> r.revalidate("acme", Set.of(Scope.PROFILE)));
      assertNull(r.send("acme", Set.of(Scope.PROFILE)).get(5, TimeUnit.SECONDS));
      server.stop(0);
    }
  }

  @Test
  void aHungStorefront_timesOutWithoutThrowing_andTheCallerNeverWaits() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          ex.close();
        });
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/revalidate";
    JdkStorefrontRevalidator r =
        new JdkStorefrontRevalidator(MAPPER, url, "s", Duration.ofMillis(300));

    long t0 = System.nanoTime();
    assertDoesNotThrow(() -> r.revalidate("acme", Set.of(Scope.PROFILE)));
    long callerMs = (System.nanoTime() - t0) / 1_000_000;
    assertEquals(true, callerMs < 250, "the write's thread waited " + callerMs + " ms");

    assertNull(r.send("acme", Set.of(Scope.PROFILE)).get(5, TimeUnit.SECONDS));
    release.countDown();
  }

  @Test
  void aRefusedConnection_neverThrows() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    JdkStorefrontRevalidator r =
        new JdkStorefrontRevalidator(MAPPER, "http://127.0.0.1:" + port + "/api/revalidate", "s");

    assertDoesNotThrow(() -> r.revalidate("acme", Set.of(Scope.PROFILE)));
    assertNull(r.send("acme", Set.of(Scope.PROFILE)).get(5, TimeUnit.SECONDS));
  }
}
