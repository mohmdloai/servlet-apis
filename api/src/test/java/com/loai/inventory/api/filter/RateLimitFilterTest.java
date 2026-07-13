package com.loai.inventory.api.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Unit coverage for {@link RateLimitFilter}'s public-surface bucketing and client-IP resolution
 * ({@code stories/public_rate_limiting.md}, B4). Mocks Jedis/JedisPool + the servlet
 * request/response — the {@code incr}/{@code expire}/429 mechanism itself is exercised end-to-end
 * by the IT.
 */
class RateLimitFilterTest {

  // ── bucket selection ──────────────────────────────────────────────────────

  @Test
  void postCheckout_selectsCheckoutBucketAndLimit() throws Exception {
    Fixture f = new Fixture(120, 5, false);
    f.req("POST", "/api/public", "/acme/checkout", "1.1.1.1");
    when(f.jedis.incr("rl:pub-checkout:1.1.1.1")).thenReturn(1L);

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.jedis).incr("rl:pub-checkout:1.1.1.1");
    verify(f.jedis).expire("rl:pub-checkout:1.1.1.1", 60);
    verify(f.chain).doFilter(f.request, f.response);
  }

  @Test
  void getPublic_selectsReadBucketAndLimit() throws Exception {
    Fixture f = new Fixture(120, 5, false);
    f.req("GET", "/api/public", "/acme/listings", "2.2.2.2");
    when(f.jedis.incr("rl:pub-read:2.2.2.2")).thenReturn(3L);

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.jedis).incr("rl:pub-read:2.2.2.2");
    verify(f.chain).doFilter(f.request, f.response);
  }

  @Test
  void postToNonCheckoutPublicPath_usesReadBucket() throws Exception {
    Fixture f = new Fixture(120, 5, false);
    f.req("POST", "/api/public", "/acme/listings", "3.3.3.3");
    when(f.jedis.incr("rl:pub-read:3.3.3.3")).thenReturn(1L);

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.jedis).incr("rl:pub-read:3.3.3.3");
  }

  @Test
  void authPaths_keepTheirOwnBuckets() throws Exception {
    Fixture login = new Fixture(120, 5, false);
    login.req("POST", "/api/auth", "/login", "9.9.9.9");
    when(login.jedis.incr("rl:login:9.9.9.9")).thenReturn(1L);
    login.filter.doFilter(login.request, login.response, login.chain);
    verify(login.jedis).incr("rl:login:9.9.9.9");

    Fixture refresh = new Fixture(120, 5, false);
    refresh.req("POST", "/api/auth", "/refresh", "9.9.9.9");
    when(refresh.jedis.incr("rl:refresh:9.9.9.9")).thenReturn(1L);
    refresh.filter.doFilter(refresh.request, refresh.response, refresh.chain);
    verify(refresh.jedis).incr("rl:refresh:9.9.9.9");
  }

  // ── counter mechanics ──────────────────────────────────────────────────────

  @Test
  void firstHit_setsTtl_underLimit_passesThrough() throws Exception {
    Fixture f = new Fixture(120, 5, false);
    f.req("GET", "/api/public", "/acme/listings", "1.1.1.1");
    when(f.jedis.incr("rl:pub-read:1.1.1.1")).thenReturn(1L);

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.jedis).expire("rl:pub-read:1.1.1.1", 60);
    verify(f.chain).doFilter(f.request, f.response);
    verify(f.response, never()).setStatus(429);
  }

  @Test
  void overLimit_writes429_andDoesNotChain() throws Exception {
    Fixture f = new Fixture(120, 5, false);
    f.req("POST", "/api/public", "/acme/checkout", "1.1.1.1");
    when(f.jedis.incr("rl:pub-checkout:1.1.1.1")).thenReturn(6L); // limit 5

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.response).setStatus(429);
    verify(f.chain, never()).doFilter(f.request, f.response);
    verify(f.jedis, never()).expire(eq("rl:pub-checkout:1.1.1.1"), anyInt());
    assertTrue(f.body().contains("Too many requests"));
  }

  @Test
  void configuredLimits_moveTheTripPoint() throws Exception {
    // read limit 2: the 3rd request trips.
    Fixture f = new Fixture(2, 1, false);
    f.req("GET", "/api/public", "/acme/listings", "1.1.1.1");
    when(f.jedis.incr("rl:pub-read:1.1.1.1")).thenReturn(3L);
    f.filter.doFilter(f.request, f.response, f.chain);
    verify(f.response).setStatus(429);
  }

  // ── client-IP resolution ────────────────────────────────────────────────────

  @Test
  void trustProxyFalse_ignoresXff_usesRemoteAddr() throws Exception {
    Fixture f = new Fixture(120, 5, false);
    f.req("GET", "/api/public", "/acme/listings", "10.0.0.1");
    when(f.request.getHeader("X-Forwarded-For")).thenReturn("5.6.7.8");
    when(f.jedis.incr(f.keyCaptor.capture())).thenReturn(1L);

    f.filter.doFilter(f.request, f.response, f.chain);

    assertEquals("rl:pub-read:10.0.0.1", f.keyCaptor.getValue());
  }

  @Test
  void trustProxyTrue_usesFirstXffHop() throws Exception {
    Fixture f = new Fixture(120, 5, true);
    f.req("GET", "/api/public", "/acme/listings", "10.0.0.1");
    when(f.request.getHeader("X-Forwarded-For")).thenReturn("5.6.7.8, 10.0.0.1");
    when(f.jedis.incr(f.keyCaptor.capture())).thenReturn(1L);

    f.filter.doFilter(f.request, f.response, f.chain);

    assertEquals("rl:pub-read:5.6.7.8", f.keyCaptor.getValue());
  }

  @Test
  void trustProxyTrue_blankXff_fallsBackToRemoteAddr() throws Exception {
    Fixture f = new Fixture(120, 5, true);
    f.req("GET", "/api/public", "/acme/listings", "10.0.0.1");
    when(f.request.getHeader("X-Forwarded-For")).thenReturn("   ");
    when(f.jedis.incr(f.keyCaptor.capture())).thenReturn(1L);

    f.filter.doFilter(f.request, f.response, f.chain);

    assertEquals("rl:pub-read:10.0.0.1", f.keyCaptor.getValue());
  }

  // ── fixture ────────────────────────────────────────────────────────────────

  private static final class Fixture {
    final JedisPool pool = mock(JedisPool.class);
    final Jedis jedis = mock(Jedis.class);
    final ObjectMapper mapper = new ObjectMapper();
    final HttpServletRequest request = mock(HttpServletRequest.class);
    final HttpServletResponse response = mock(HttpServletResponse.class);
    final FilterChain chain = mock(FilterChain.class);
    final ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    final ByteArrayOutputStream sink = new ByteArrayOutputStream();
    final RateLimitFilter filter;

    Fixture(int readLimit, int checkoutLimit, boolean trustProxy) throws IOException {
      when(pool.getResource()).thenReturn(jedis);
      when(response.getOutputStream()).thenReturn(new CapturingStream(sink));
      this.filter = new RateLimitFilter(pool, mapper, readLimit, checkoutLimit, trustProxy);
    }

    void req(String method, String servletPath, String pathInfo, String remoteAddr) {
      when(request.getMethod()).thenReturn(method);
      when(request.getServletPath()).thenReturn(servletPath);
      when(request.getPathInfo()).thenReturn(pathInfo);
      when(request.getRemoteAddr()).thenReturn(remoteAddr);
    }

    String body() {
      return sink.toString();
    }
  }

  private static final class CapturingStream extends ServletOutputStream {
    private final ByteArrayOutputStream delegate;

    CapturingStream(ByteArrayOutputStream delegate) {
      this.delegate = delegate;
    }

    @Override
    public void write(int b) {
      delegate.write(b);
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setWriteListener(jakarta.servlet.WriteListener writeListener) {}
  }
}
