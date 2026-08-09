package com.loai.inventory.api.filter;

import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;

/**
 * Browser CORS for the staff/admin origins.
 *
 * <p>The allowlist is <b>not</b> parsed here: it is {@link AppConfig#corsAllowedOrigins}, the same
 * trimmed set the customer portal's CSRF {@code Origin} check consumes. This filter used to run its
 * own {@code Set.of(env.split(","))} with no {@code trim()}, so a configured {@code "a, b"} stored
 * {@code " b"} and never matched — while the portal's allowlist, fed by {@code AppConfig}, matched
 * it fine. Two parsers over one env var is two answers to one question; there is now one.
 */
public class CorsFilter implements Filter {

  private Set<String> allowedOrigins;

  /** No-arg constructor for the servlet container; the allowlist is read in {@link #init}. */
  public CorsFilter() {}

  /** Test constructor: inject the allowlist directly (bypasses {@link #init}). */
  CorsFilter(Set<String> allowedOrigins) {
    this.allowedOrigins = allowedOrigins;
  }

  @Override
  public void init(FilterConfig filterConfig) {
    AppConfig config =
        (AppConfig) filterConfig.getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.allowedOrigins = config.corsAllowedOrigins;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse resp = (HttpServletResponse) response;

    String origin = req.getHeader("Origin");
    if (origin != null && allowedOrigins.contains(origin.trim())) {
      resp.setHeader("Access-Control-Allow-Origin", origin);
      resp.setHeader("Access-Control-Allow-Credentials", "true");
      resp.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH");
      // X-Portal-Request is the customer-portal CSRF header — a cross-origin storefront must be
      // allowed to send it, else the preflight blocks every /api/portal/* call.
      resp.setHeader(
          "Access-Control-Allow-Headers",
          "Content-Type, Authorization, X-Requested-With, X-Portal-Request");
    }
    // preflight request as short circuit
    if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
      resp.setStatus(200);
      return;
    }
    // continue with the req and hit the next filter or (controllers/servlets)
    chain.doFilter(request, response);
  }

  @Override
  public void destroy() {}
}
