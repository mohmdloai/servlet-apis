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
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.auth.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The staff plane's gate, branch by branch (D11) — the sibling of {@code CustomerAuthFilterTest}.
 *
 * <p>{@code JwtAuthFilterAnonymousPathsTest} covers a different question (which routes need no
 * token at all). These cover what happens once a token *is* presented: the audience rule that keeps
 * a portal token off the admin plane, and the two revocation checks. All three were previously
 * exercised only through ITs asserting an outcome, so a rejection happening for the wrong reason
 * still looked green.
 */
class JwtAuthFilterAudienceTest {

  private static final String STAFF_SECRET =
      Base64.getEncoder().encodeToString("staff-secret-key-32-bytes-long!!".getBytes());
  private static final long TTL = 900_000L;
  private static final UUID USER = UUID.randomUUID();
  private static final UUID FAMILY = UUID.randomUUID();

  /**
   * The barrier that matters: a customer token must never be honoured here even if it verified.
   * Signed with the *staff* key on purpose — this asserts the audience check on its own, rather
   * than letting the key difference do the work and leaving the claim untested.
   */
  @Test
  void aCustomerAudience_isRejectedEvenWhenTheSignatureIsValid() throws Exception {
    JwtUtil asCustomer = new JwtUtil(STAFF_SECRET, TTL, "customer");
    Fixture f = new Fixture();
    f.bearer(asCustomer.generateCustomerAccessToken(USER, UUID.randomUUID(), 1, FAMILY));

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.response).setStatus(401);
    verify(f.chain, never()).doFilter(any(), any());
    assertNull(f.published, "a refused request leaves no SecurityContext behind");
  }

  @Test
  void aStaffAudience_isAccepted() throws Exception {
    Fixture f = new Fixture();
    f.bearer(
        new JwtUtil(STAFF_SECRET, TTL, "staff")
            .generateAccessToken(USER, "USER", Map.of(), Set.of(), Set.of(), 1, FAMILY));

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.chain).doFilter(f.request, f.response);
    assertNotNull(f.published);
  }

  /**
   * The grace window, deliberately still open: tokens minted before the {@code aud} hardening carry
   * none, and must keep working until they expire. Pinned so closing the window is a decision
   * someone makes, not something that happens by accident.
   */
  @Test
  void aMissingAudience_isStillAccepted() throws Exception {
    Fixture f = new Fixture();
    f.bearer(
        new JwtUtil(STAFF_SECRET, TTL)
            .generateAccessToken(USER, "USER", Map.of(), Set.of(), Set.of(), 1, FAMILY));

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.chain).doFilter(f.request, f.response);
  }

  @Test
  void aRevokedTokenVersion_is401() throws Exception {
    Fixture f = new Fixture();
    f.bearer(f.staffToken());
    when(f.authService.isTokenVersionValid(any(), anyInt())).thenReturn(false);

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.response).setStatus(401);
    verify(f.chain, never()).doFilter(any(), any());
  }

  @Test
  void aRevokedDevice_is401() throws Exception {
    Fixture f = new Fixture();
    f.bearer(f.staffToken());
    when(f.authService.isDeviceRevoked(FAMILY)).thenReturn(true);

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.response).setStatus(401);
  }

  @Test
  void garbageToken_is401_ratherThan500() throws Exception {
    Fixture f = new Fixture();
    f.bearer("not-a-jwt");

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.response).setStatus(401);
  }

  private static final class Fixture {
    final HttpServletRequest request = mock(HttpServletRequest.class);
    final HttpServletResponse response = mock(HttpServletResponse.class);
    final FilterChain chain = mock(FilterChain.class);
    final AuthService authService = mock(AuthService.class);
    final JwtUtil jwt = new JwtUtil(STAFF_SECRET, TTL, "staff");
    final JwtAuthFilter filter;
    SecurityContext published;

    Fixture() throws Exception {
      when(response.getOutputStream()).thenReturn(new NullStream());
      when(request.getServletPath()).thenReturn("/api/orgs");
      when(request.getPathInfo()).thenReturn("/some-org/products");
      when(authService.isTokenVersionValid(any(), anyInt())).thenReturn(true);
      when(authService.isDeviceRevoked(any())).thenReturn(false);
      doAnswer(
              inv -> {
                if (JwtAuthFilter.SECURITY_CONTEXT_ATTR.equals(inv.getArgument(0))) {
                  published = inv.getArgument(1);
                }
                return null;
              })
          .when(request)
          .setAttribute(any(), any());
      filter = new JwtAuthFilter(jwt, authService, new ObjectMapper());
    }

    void bearer(String token) {
      when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
    }

    String staffToken() {
      return jwt.generateAccessToken(USER, "USER", Map.of(), Set.of(), Set.of(), 1, FAMILY);
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
