package com.loai.inventory.api.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.config.AppConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D8 — one allowlist, one answer. {@code CORS_ALLOWED_ORIGINS="a, b"} must match {@code b}: the
 * filter used to split without trimming and stored {@code " b"}, so the staff CORS check silently
 * refused an origin the portal's CSRF check (fed by {@code AppConfig}) accepted.
 */
class CorsOriginAllowlistTest {

  @Test
  @DisplayName("a space after the comma does not create an origin nobody can match")
  void spacedListMatchesEveryEntry() throws Exception {
    Set<String> origins =
        AppConfig.parseAllowedOrigins("https://admin.example.com, https://shop.example.com");
    assertEquals(
        Set.of("https://admin.example.com", "https://shop.example.com"),
        origins,
        "each entry must be trimmed");

    assertEquals("https://admin.example.com", allowOriginFor(origins, "https://admin.example.com"));
    assertEquals("https://shop.example.com", allowOriginFor(origins, "https://shop.example.com"));
  }

  @Test
  @DisplayName("an origin outside the list still gets no CORS headers")
  void foreignOriginIsNotAllowed() throws Exception {
    Set<String> origins = AppConfig.parseAllowedOrigins("https://admin.example.com");
    assertNull(allowOriginFor(origins, "https://evil.example.com"));
  }

  @Test
  @DisplayName("blank config falls back to the dev defaults")
  void blankFallsBackToDevDefaults() {
    assertEquals(
        Set.of("http://localhost:3000", "http://localhost:5173"),
        AppConfig.parseAllowedOrigins("   "));
  }

  /** Run one GET through the filter and report the Access-Control-Allow-Origin it set, if any. */
  private static String allowOriginFor(Set<String> allowed, String origin) throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getHeader("Origin")).thenReturn(origin);
    when(req.getMethod()).thenReturn("GET");

    Map<String, String> headers = new HashMap<>();
    org.mockito.Mockito.doAnswer(
            inv -> {
              headers.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(resp)
        .setHeader(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());

    new CorsFilter(allowed).doFilter(req, resp, chain);
    return headers.get("Access-Control-Allow-Origin");
  }
}
