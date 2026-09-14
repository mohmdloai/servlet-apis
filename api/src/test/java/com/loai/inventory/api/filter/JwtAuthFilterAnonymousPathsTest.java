package com.loai.inventory.api.filter;

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
import org.junit.jupiter.api.Test;

/**
 * Regression pin for the filter's anonymous whitelist (fix 90): story 88 shipped {@code
 * /verify-email} and {@code /resend-verification} on {@code AuthServlet} without adding them here,
 * so BOTH endpoints 401'd in production before the servlet ever saw a request — a gap no other
 * suite caught, because the service ITs bypass the filter chain and the frontend e2e runs against a
 * mock. Every anonymous route the auth surface exposes must be listed in this test; a new anonymous
 * endpoint that forgets the whitelist fails here instead of in prod.
 */
class JwtAuthFilterAnonymousPathsTest {

  /**
   * Every /api/auth/* route a caller must be able to reach WITHOUT a session. Mirrors AuthServlet's
   * anonymous surface 1:1 — extend both together.
   */
  private static final String[] ANONYMOUS_AUTH_PATHS = {
    "/api/auth/login",
    "/api/auth/refresh",
    "/api/auth/register",
    "/api/auth/forgot-password",
    "/api/auth/reset-password",
    "/api/auth/activate",
    "/api/auth/verify-email",
    "/api/auth/resend-verification",
  };

  @Test
  void anonymousAuthPaths_passThroughWithoutAnyToken() throws Exception {
    for (String path : ANONYMOUS_AUTH_PATHS) {
      Fixture f = new Fixture();
      f.req("/api/auth", path.substring("/api/auth".length()));

      f.filter.doFilter(f.request, f.response, f.chain);

      verify(f.chain).doFilter(f.request, f.response);
      verify(f.response, never()).setStatus(401);
    }
  }

  @Test
  void publicPortalAndPspPrefixes_passThrough() throws Exception {
    for (String[] p :
        new String[][] {
          {"/api/public", "/acme/listings"},
          {"/api/portal", "/auth/refresh"},
          // PSP callbacks (stories/paymob_card_checkout.md): the HMAC is the capability.
          {"/api/psp", "/paymob/00000000-0000-0000-0000-000000000000/webhook"},
        }) {
      Fixture f = new Fixture();
      f.req(p[0], p[1]);

      f.filter.doFilter(f.request, f.response, f.chain);

      verify(f.chain).doFilter(f.request, f.response);
    }
  }

  @Test
  void guardedPath_withoutToken_is401_andNeverChains() throws Exception {
    Fixture f = new Fixture();
    f.req("/api/orgs", "/some-org/products");
    when(f.request.getHeader("Authorization")).thenReturn(null);
    when(f.request.getCookies()).thenReturn(null);

    f.filter.doFilter(f.request, f.response, f.chain);

    verify(f.response).setStatus(401);
    verify(f.chain, never()).doFilter(f.request, f.response);
  }

  private static final class Fixture {
    final HttpServletRequest request = mock(HttpServletRequest.class);
    final HttpServletResponse response = mock(HttpServletResponse.class);
    final FilterChain chain = mock(FilterChain.class);
    final JwtAuthFilter filter = new JwtAuthFilter(null, null, new ObjectMapper());

    Fixture() throws Exception {
      when(response.getOutputStream()).thenReturn(new NullStream());
    }

    void req(String servletPath, String pathInfo) {
      when(request.getServletPath()).thenReturn(servletPath);
      when(request.getPathInfo()).thenReturn(pathInfo.isEmpty() ? null : pathInfo);
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
