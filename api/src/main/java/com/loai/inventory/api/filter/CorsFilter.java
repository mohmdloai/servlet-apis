package com.loai.inventory.api.filter;

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

public class CorsFilter implements Filter {

  private Set<String> allowedOrigins;

  @Override
  public void init(FilterConfig filterConfig) {
    String envOrigins = System.getenv("CORS_ALLOWED_ORIGINS");
    if (envOrigins != null && !envOrigins.isBlank()) {
      allowedOrigins = Set.of(envOrigins.split(","));
    } else {
      allowedOrigins = Set.of("http://localhost:3000", "http://localhost:5173");
    }
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
