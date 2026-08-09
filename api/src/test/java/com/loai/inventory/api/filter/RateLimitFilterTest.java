package com.loai.inventory.api.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.args.Rawable;
import redis.clients.jedis.params.SetParams;

/**
 * Unit coverage for {@link RateLimitFilter}'s public-surface bucketing and client-IP resolution
 * ({@code stories/public_rate_limiting.md}, B4). Mocks Jedis/JedisPool + the servlet
 * request/response — the {@code incr}/{@code expire}/429 mechanism itself is exercised end-to-end
 * by the IT.
 */
class RateLimitFilterTest {

  // bucket selection

  @Test
  void postCheckout_selectsCheckoutBucketAndLimit() throws Exception {
    Fixture f = new Fixture(120, 5, false);
    f.req("POST", "/api/public", "/acme/checkout", "1.1.1.1");
    when(f.jedis.incr("rl:pub-checkout:1.1.1.1")).thenReturn(1L);

    f.filter.doFilter(f.request, f.response, f.chain);

    verifyTtlThenCount(f, "rl:pub-checkout:1.1.1.1");
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

  // self-serve auth buckets (story 87 — previously fail-open)

  @Test
  void register_selectsItsOwnBucket_andPassesUnderLimit() throws Exception {
    Fixture f = new Fixture(120, 5, false);
    f.req("POST", "/api/auth", "/register", "4.4.4.4");
    when(f.jedis.incr("rl:auth-register:4.4.4.4")).thenReturn(1L);

    f.filter.doFilter(f.request, f.response, f.chain);

    verifyTtlThenCount(f, "rl:auth-register:4.4.4.4");
    verify(f.chain).doFilter(f.request, f.response);
  }

  @Test
  void couponValidate_selectsItsOwnStrictBucket_notThePublicReadOne() throws Exception {
    // Roadmap item 9: the preview lives under /api/public/, so without its own branch it would
    // inherit pub-read's generous budget — which is a code-scraping allowance on an endpoint whose
    // whole job is answering "does this code exist?".
    Fixture f = new Fixture(120, 5, false);
    f.req("POST", "/api/public", "/acme/coupons/validate", "6.6.6.6");
    when(f.jedis.incr("rl:pub-coupon:6.6.6.6")).thenReturn(1L);

    f.filter.doFilter(f.request, f.response, f.chain);

    verifyTtlThenCount(f, "rl:pub-coupon:6.6.6.6");
    verify(f.jedis, never()).incr("rl:pub-read:6.6.6.6");
    verify(f.chain).doFilter(f.request, f.response);
  }

  @Test
  void couponValidate_eleventhRequestInWindow_trips429() throws Exception {
    Fixture f = new Fixture(120, 5, false);
    f.req("POST", "/api/public", "/acme/coupons/validate", "7.7.7.7");
    when(f.jedis.incr("rl:pub-coupon:7.7.7.7")).thenReturn(11L); // default limit 10

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.response).setStatus(429);
    verify(f.chain, never()).doFilter(f.request, f.response);
  }

  @Test
  void register_fourthRequestInWindow_trips429() throws Exception {
    Fixture f = new Fixture(120, 5, false);
    f.req("POST", "/api/auth", "/register", "4.4.4.4");
    when(f.jedis.incr("rl:auth-register:4.4.4.4")).thenReturn(4L); // default limit 3

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.response).setStatus(429);
    verify(f.chain, never()).doFilter(f.request, f.response);
  }

  @Test
  void forgotPassword_selectsItsOwnBucket_andSixthRequestTrips() throws Exception {
    Fixture ok = new Fixture(120, 5, false);
    ok.req("POST", "/api/auth", "/forgot-password", "5.5.5.5");
    when(ok.jedis.incr("rl:auth-forgot:5.5.5.5")).thenReturn(5L); // default limit 5 — at the edge
    ok.filter.doFilter(ok.request, ok.response, ok.chain);
    verify(ok.chain).doFilter(ok.request, ok.response);

    Fixture over = new Fixture(120, 5, false);
    over.req("POST", "/api/auth", "/forgot-password", "5.5.5.5");
    when(over.jedis.incr("rl:auth-forgot:5.5.5.5")).thenReturn(6L);
    over.filter.doFilter(over.request, over.response, over.chain);
    verify(over.response).setStatus(429);
    verify(over.chain, never()).doFilter(over.request, over.response);
  }

  @Test
  void resendVerification_selectsItsOwnBucket_sharingTheForgotLimit() throws Exception {
    // Story 88: same shape as forgot-password (anonymous email-send trigger), own bucket key.
    Fixture f = new Fixture(120, 5, false);
    f.req("POST", "/api/auth", "/resend-verification", "7.7.7.7");
    when(f.jedis.incr("rl:auth-resend:7.7.7.7")).thenReturn(6L); // over AUTH_FORGOT_LIMIT (5)
    f.filter.doFilter(f.request, f.response, f.chain);
    verify(f.response).setStatus(429);
    verify(f.chain, never()).doFilter(f.request, f.response);
  }

  @Test
  void resetPasswordAndActivate_stayUnbucketed_failOpen() throws Exception {
    // Deliberate: they redeem 256-bit single-use tokens — the token space is the rate limit.
    for (String path : new String[] {"/reset-password", "/activate", "/verify-email"}) {
      Fixture f = new Fixture(120, 5, false);
      f.req("POST", "/api/auth", path, "6.6.6.6");
      f.filter.doFilter(f.request, f.response, f.chain);
      verify(f.chain).doFilter(f.request, f.response);
      verify(f.jedis, never()).incr(anyString());
    }
  }

  // counter mechanics

  @Test
  void firstHit_setsTtl_underLimit_passesThrough() throws Exception {
    Fixture f = new Fixture(120, 5, false);
    f.req("GET", "/api/public", "/acme/listings", "1.1.1.1");
    when(f.jedis.incr("rl:pub-read:1.1.1.1")).thenReturn(1L);

    f.filter.doFilter(f.request, f.response, f.chain);

    verifyTtlThenCount(f, "rl:pub-read:1.1.1.1");
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
    // The window key still gets its TTL — the counter is written expiring-first, always.
    verify(f.jedis).set(eq("rl:pub-checkout:1.1.1.1"), eq("0"), any(SetParams.class));
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

  // client-IP resolution

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

  /**
   * The window counter is written <b>TTL first</b>: {@code SET key 0 NX EX 60} then {@code INCR}.
   * The old order (INCR, then EXPIRE only when the counter came back 1) leaves a window in which a
   * crash between the two commands strands a key with no expiry — and a fixed-window counter that
   * never expires blocks that IP permanently. {@code NX} is what stops the TTL write from resetting
   * a window that is already running. Order matters, hence {@link org.mockito.InOrder}.
   */
  private static void verifyTtlThenCount(Fixture f, String key) {
    ArgumentCaptor<SetParams> params = ArgumentCaptor.forClass(SetParams.class);
    InOrder order = inOrder(f.jedis);
    order.verify(f.jedis).set(eq(key), eq("0"), params.capture());
    order.verify(f.jedis).incr(key);
    assertEquals(
        render(SetParams.setParams().nx().ex(60)),
        render(params.getValue()),
        "the TTL write must be NX (never resets a live window) with the 60s window");
  }

  /** The wire tokens a {@link SetParams} contributes — it has no {@code equals}. */
  private static List<String> render(SetParams params) {
    CommandArguments args = new CommandArguments(Protocol.Command.SET);
    args.addParams(params);
    List<String> tokens = new ArrayList<>();
    for (Rawable raw : args) {
      tokens.add(new String(raw.getRaw(), StandardCharsets.UTF_8));
    }
    return tokens;
  }

  // fixture

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
