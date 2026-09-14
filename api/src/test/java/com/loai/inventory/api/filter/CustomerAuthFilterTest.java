package com.loai.inventory.api.filter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.domain.model.CustomerPrincipal;
import com.loai.inventory.service.auth.CustomerAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The customer plane's gate, branch by branch (D11).
 *
 * <p>Its decisions were only ever exercised end-to-end, through ITs that boot Postgres and Redis
 * and assert an *outcome* — which means a rejection that stopped happening for the wrong reason, or
 * happened for a different one, would still look green. These drive the filter directly and pin
 * each refusal to its own cause: the two structural barriers (a different signing key, {@code
 * aud=customer}), the CSRF pair, the refresh/logout bypass, and the two revocation checks.
 *
 * <p>{@code CustomerJwtIsolationTest} is the sibling for the *token* half — that keys are disjoint
 * and audiences are stamped. This is the half that decides what to do about it.
 */
class CustomerAuthFilterTest {

  private static final String CUSTOMER_SECRET =
      Base64.getEncoder().encodeToString("customer-secret-key-32bytes-long".getBytes());
  private static final String STAFF_SECRET =
      Base64.getEncoder().encodeToString("staff-secret-key-32-bytes-long!!".getBytes());
  private static final long TTL = 900_000L;
  private static final Set<String> ORIGINS = Set.of("https://shop.example");

  private static final UUID CUSTOMER = UUID.randomUUID();
  private static final UUID ORG = UUID.randomUUID();
  private static final UUID FAMILY = UUID.randomUUID();

  // CSRF — the first gate, before any token is looked at

  @Test
  void missingPortalHeader_is400_beforeAnythingElse() throws Exception {
    Fixture f = new Fixture().get("/api/portal/me"); // no X-Portal-Request

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.response).setStatus(400);
    verify(f.chain, never()).doFilter(any(), any());
  }

  @Test
  void mutationFromAForeignOrigin_is403() throws Exception {
    Fixture f = new Fixture().post("/api/portal/me").withHeader().origin("https://evil.example");

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.response).setStatus(403);
    verify(f.chain, never()).doFilter(any(), any());
  }

  @Test
  void mutationFromAnAllowedOrigin_passesTheCsrfGate() throws Exception {
    Fixture f =
        new Fixture()
            .post("/api/portal/me")
            .withHeader()
            .origin("https://shop.example")
            .cookie(f2 -> f2.customerToken());

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.chain).doFilter(f.request, f.response);
  }

  /** A GET is not origin-checked — only the header applies (PortalCsrf.isMutation). */
  @Test
  void readFromAForeignOrigin_isNotBlocked() throws Exception {
    Fixture f =
        new Fixture()
            .get("/api/portal/me")
            .withHeader()
            .origin("https://evil.example")
            .cookie(f2 -> f2.customerToken());

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.chain).doFilter(f.request, f.response);
  }

  /**
   * The card-payment door ({@code stories/paymob_portal_pay.md}, {@code POST /orders/{n}/pay}) is
   * an ordinary portal write: the header gate (400) and the origin gate (403) both close before the
   * servlet — or Paymob — is reached, and an allowlisted origin with a live session passes. Pinned
   * by route because the story names these codes as acceptance criteria.
   */
  @Test
  void payRoute_takesTheTwoCsrfGates_likeEveryPortalWrite() throws Exception {
    Fixture noHeader = new Fixture().post("/api/portal/orders/SO-2026-00042/pay");
    noHeader.filter.doFilter(noHeader.request, noHeader.response, noHeader.chain);
    verify(noHeader.response).setStatus(400);
    verify(noHeader.chain, never()).doFilter(any(), any());

    Fixture foreign =
        new Fixture()
            .post("/api/portal/orders/SO-2026-00042/pay")
            .withHeader()
            .origin("https://evil.example")
            .cookie(f -> f.customerToken());
    foreign.filter.doFilter(foreign.request, foreign.response, foreign.chain);
    verify(foreign.response).setStatus(403);
    verify(foreign.chain, never()).doFilter(any(), any());

    Fixture ok =
        new Fixture()
            .post("/api/portal/orders/SO-2026-00042/pay")
            .withHeader()
            .origin("https://shop.example")
            .cookie(f -> f.customerToken());
    ok.filter.doFilter(ok.request, ok.response, ok.chain);
    verify(ok.chain).doFilter(ok.request, ok.response);
  }

  // The bypass — refresh/logout authenticate off the refresh cookie alone

  @Test
  void refreshAndLogout_bypassTheAccessToken_butNotCsrf() throws Exception {
    for (String path : new String[] {"/api/portal/auth/refresh", "/api/portal/auth/logout"}) {
      Fixture ok = new Fixture().post(path).withHeader().origin("https://shop.example");
      ok.filter.doFilter(ok.request, ok.response, ok.chain);
      verify(ok.chain).doFilter(ok.request, ok.response); // no access cookie needed

      Fixture noHeader = new Fixture().post(path);
      noHeader.filter.doFilter(noHeader.request, noHeader.response, noHeader.chain);
      verify(noHeader.response).setStatus(400); // the CSRF gate still applies
    }
  }

  // Authentication — one refusal per cause

  @Test
  void noAccessCookie_is401() throws Exception {
    Fixture f = new Fixture().get("/api/portal/me").withHeader();

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.response).setStatus(401);
    verify(f.chain, never()).doFilter(any(), any());
  }

  /**
   * The first structural barrier: a staff token is signed with a different key, so it cannot even
   * verify here. This is the isolation guarantee the whole two-plane design rests on.
   */
  @Test
  void aStaffToken_cannotVerifyOnThisPlane() throws Exception {
    JwtUtil staff = new JwtUtil(STAFF_SECRET, TTL, "staff");
    String staffToken =
        staff.generateAccessToken(
            UUID.randomUUID(), "USER", Map.of(), Set.of(), Set.of(), 1, UUID.randomUUID());
    Fixture f = new Fixture().get("/api/portal/me").withHeader().cookieValue(staffToken);

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.response).setStatus(401);
    verify(f.chain, never()).doFilter(any(), any());
  }

  /**
   * The second barrier, independent of the key: even a correctly-signed customer-plane token is
   * refused without {@code aud=customer}. Belt and braces on purpose — one key rotation mistake
   * should not collapse the plane separation.
   */
  @Test
  void rightKeyButWrongAudience_is401() throws Exception {
    JwtUtil unstamped = new JwtUtil(CUSTOMER_SECRET, TTL); // no audience
    String token = unstamped.generateCustomerAccessToken(CUSTOMER, ORG, 1, FAMILY);
    Fixture f = new Fixture().get("/api/portal/me").withHeader().cookieValue(token);

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.response).setStatus(401);
  }

  @Test
  void aRevokedTokenVersion_is401_andNeverChains() throws Exception {
    Fixture f = new Fixture().get("/api/portal/me").withHeader().cookie(f2 -> f2.customerToken());
    when(f.authService.isTokenVersionValid(any(), any(), anyInt())).thenReturn(false);

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.response).setStatus(401);
    verify(f.chain, never()).doFilter(any(), any());
  }

  @Test
  void aRevokedDevice_is401() throws Exception {
    Fixture f = new Fixture().get("/api/portal/me").withHeader().cookie(f2 -> f2.customerToken());
    when(f.authService.isDeviceRevoked(any())).thenReturn(true);

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.response).setStatus(401);
  }

  @Test
  void garbageToken_is401_ratherThan500() throws Exception {
    Fixture f = new Fixture().get("/api/portal/me").withHeader().cookieValue("not-a-jwt");

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.response).setStatus(401);
  }

  /** The happy path publishes the identity downstream code reads — never the URL or the body. */
  @Test
  void aValidSession_publishesThePrincipal() throws Exception {
    Fixture f = new Fixture().get("/api/portal/me").withHeader().cookie(f2 -> f2.customerToken());

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.chain).doFilter(f.request, f.response);
    CustomerPrincipal principal = f.published;
    assertNotNull(principal, "the principal is what scopes every portal read to (org, customer)");
    org.junit.jupiter.api.Assertions.assertEquals(CUSTOMER, principal.customerId());
    org.junit.jupiter.api.Assertions.assertEquals(ORG, principal.orgId());
    org.junit.jupiter.api.Assertions.assertEquals(FAMILY, principal.familyId());
  }

  @Test
  void aRejectedRequestPublishesNoPrincipal() throws Exception {
    Fixture f = new Fixture().get("/api/portal/me").withHeader().cookieValue("not-a-jwt");

    f.filter.doFilter(f.request, f.response, f.chain);

    assertNull(f.published, "a refused request must leave no identity behind for a later filter");
  }

  // fixture

  private static final class Fixture {
    final HttpServletRequest request = mock(HttpServletRequest.class);
    final HttpServletResponse response = mock(HttpServletResponse.class);
    final FilterChain chain = mock(FilterChain.class);
    final CustomerAuthService authService = mock(CustomerAuthService.class);
    final JwtUtil jwt = new JwtUtil(CUSTOMER_SECRET, TTL, "customer");
    final CustomerAuthFilter filter;
    CustomerPrincipal published;

    Fixture() throws Exception {
      when(response.getOutputStream()).thenReturn(new NullStream());
      // Default to a live session; the revocation tests override these.
      when(authService.isTokenVersionValid(any(), any(), anyInt())).thenReturn(true);
      when(authService.isDeviceRevoked(any())).thenReturn(false);
      doAnswer(
              inv -> {
                if (CustomerAuthFilter.PRINCIPAL_ATTR.equals(inv.getArgument(0))) {
                  published = inv.getArgument(1);
                }
                return null;
              })
          .when(request)
          .setAttribute(any(), any());
      filter = new CustomerAuthFilter(jwt, authService, new ObjectMapper(), ORIGINS);
    }

    Fixture get(String path) {
      return method("GET", path);
    }

    Fixture post(String path) {
      return method("POST", path);
    }

    private Fixture method(String method, String path) {
      when(request.getMethod()).thenReturn(method);
      when(request.getServletPath()).thenReturn("/api/portal");
      when(request.getPathInfo()).thenReturn(path.substring("/api/portal".length()));
      return this;
    }

    Fixture withHeader() {
      when(request.getHeader(com.loai.inventory.api.servlet.PortalCsrf.HEADER)).thenReturn("1");
      return this;
    }

    Fixture origin(String origin) {
      when(request.getHeader("Origin")).thenReturn(origin);
      return this;
    }

    Fixture cookieValue(String token) {
      when(request.getCookies())
          .thenReturn(
              new Cookie[] {
                new Cookie(com.loai.inventory.api.servlet.CustomerAuthCookies.ACCESS_COOKIE, token)
              });
      return this;
    }

    Fixture cookie(java.util.function.Function<Fixture, String> token) {
      return cookieValue(token.apply(this));
    }

    String customerToken() {
      return jwt.generateCustomerAccessToken(CUSTOMER, ORG, 1, FAMILY);
    }
  }

  private static final class NullStream extends ServletOutputStream {
    private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

    @Override
    public void write(int b) {
      sink.write(b);
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setWriteListener(jakarta.servlet.WriteListener listener) {}
  }
}
