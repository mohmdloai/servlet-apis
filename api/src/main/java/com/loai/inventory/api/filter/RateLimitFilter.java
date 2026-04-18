package com.loai.inventory.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class RateLimitFilter implements Filter {

  private static final int LOGIN_LIMIT = 10;
  private static final int REFRESH_LIMIT = 30;
  private static final int WINDOW_SECONDS = 60;

  private JedisPool jedisPool;
  private ObjectMapper objectMapper;

  @Override
  public void init(FilterConfig filterConfig) {
    AppConfig config =
        (AppConfig) filterConfig.getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.jedisPool = config.jedisPool;
    this.objectMapper = config.objectMapper;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse resp = (HttpServletResponse) response;

    String path = req.getServletPath() + (req.getPathInfo() != null ? req.getPathInfo() : "");
    String ip = req.getRemoteAddr();

    int limit;
    String keyPrefix;
    if (path.endsWith("/login")) {
      keyPrefix = "rl:login:";
      limit = LOGIN_LIMIT;
    } else if (path.endsWith("/refresh")) {
      keyPrefix = "rl:refresh:";
      limit = REFRESH_LIMIT;
    } else {
      chain.doFilter(request, response);
      return;
    }

    String key = keyPrefix + ip;
    try (Jedis jedis = jedisPool.getResource()) {
      long current = jedis.incr(key);
      if (current == 1) {
        jedis.expire(key, WINDOW_SECONDS);
      }
      if (current > limit) {
        resp.setStatus(429);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
            resp.getOutputStream(), ApiError.of(429, "Too many requests. Try again later."));
        return;
      }
    }

    chain.doFilter(request, response);
  }

  @Override
  public void destroy() {}
}
