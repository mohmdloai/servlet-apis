package com.loai.inventory.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.ImpersonationTier;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.service.auth.AuthService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class JwtAuthFilter implements Filter {

  public static final String SECURITY_CONTEXT_ATTR = "securityContext";
  public static final String ENVIRONMENT_ATTR = "environment";

  private JwtUtil jwtUtil;
  private AuthService authService;
  private ObjectMapper objectMapper;

  @Override
  public void init(FilterConfig filterConfig) {
    AppConfig config =
        (AppConfig) filterConfig.getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.jwtUtil = config.jwtUtil;
    this.authService = config.authService;
    this.objectMapper = config.objectMapper;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse resp = (HttpServletResponse) response;

    String path = req.getServletPath() + (req.getPathInfo() != null ? req.getPathInfo() : "");

    // Anonymous surfaces: auth bootstrap + the public storefront read API.
    if (path.equals("/api/auth/login")
        || path.equals("/api/auth/refresh")
        || path.startsWith("/api/public/")) {
      chain.doFilter(request, response);
      return;
    }

    String token = extractToken(req);
    if (token == null) {
      writeUnauthorized(resp, "Missing authentication token");
      return;
    }

    try {
      Claims claims = jwtUtil.parseAndVerify(token);
      UUID userId = UUID.fromString(claims.getSubject());
      int tokenVersion = claims.get("token_version", Integer.class);

      if (!authService.isTokenVersionValid(userId, tokenVersion)) {
        writeUnauthorized(resp, "Token has been revoked");
        return;
      }

      // Per-device access-token kill-switch: a revoked device's outstanding token dies here, even
      // before it expires. Device-less overlay tokens carry no fam claim and skip this check.
      UUID familyId = parseUuidClaim(claims, "fam");
      if (familyId != null && authService.isDeviceRevoked(familyId)) {
        writeUnauthorized(resp, "Session revoked");
        return;
      }

      ActorType actorType = ActorType.valueOf(claims.get("actor_type", String.class));
      Set<SystemRole> systemRoles = parseSystemRoles(claims);
      Map<UUID, Set<OrgRole>> orgRoles = parseOrgRoles(claims);
      Set<String> allowedActions = parseAllowedActions(claims);

      UUID impersonatorId = parseUuidClaim(claims, "act");
      ImpersonationTier impersonationTier =
          impersonatorId == null ? null : parseTier(claims.get("act_tier", String.class));
      UUID impersonationScopeOrg = parseUuidClaim(claims, "act_scope_org");
      boolean impersonationReadOnly = "READONLY".equals(claims.get("act_mode", String.class));

      SecurityContext secCtx =
          new SecurityContext(
              userId,
              actorType,
              systemRoles,
              orgRoles,
              allowedActions,
              tokenVersion,
              impersonatorId,
              impersonationTier,
              impersonationScopeOrg,
              impersonationReadOnly);
      Environment env =
          new Environment(Instant.now(), req.getRemoteAddr(), req.getHeader("User-Agent"));

      req.setAttribute(SECURITY_CONTEXT_ATTR, secCtx);
      req.setAttribute(ENVIRONMENT_ATTR, env);

      chain.doFilter(request, response);

    } catch (JwtException e) {
      writeUnauthorized(resp, "Invalid token");
    }
  }

  @Override
  public void destroy() {}

  private String extractToken(HttpServletRequest req) {
    String authHeader = req.getHeader("Authorization");
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
      return authHeader.substring(7);
    }
    Cookie[] cookies = req.getCookies();
    if (cookies != null) {
      for (Cookie cookie : cookies) {
        if ("access_token".equals(cookie.getName())) {
          return cookie.getValue();
        }
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private Set<SystemRole> parseSystemRoles(Claims claims) {
    List<String> roles = claims.get("system_roles", List.class);
    if (roles == null) return Collections.emptySet();
    Set<SystemRole> result = new HashSet<>();
    for (String r : roles) {
      result.add(SystemRole.valueOf(r));
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private Map<UUID, Set<OrgRole>> parseOrgRoles(Claims claims) {
    Map<String, List<String>> raw = claims.get("org_roles", Map.class);
    if (raw == null) return Collections.emptyMap();
    Map<UUID, Set<OrgRole>> result = new HashMap<>();
    for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
      UUID orgId = UUID.fromString(entry.getKey());
      Set<OrgRole> roles = new HashSet<>();
      for (String r : entry.getValue()) {
        roles.add(OrgRole.valueOf(r));
      }
      result.put(orgId, roles);
    }
    return result;
  }

  private UUID parseUuidClaim(Claims claims, String name) {
    String raw = claims.get(name, String.class);
    return raw == null ? null : UUID.fromString(raw);
  }

  private ImpersonationTier parseTier(String raw) {
    return raw == null ? null : ImpersonationTier.valueOf(raw);
  }

  @SuppressWarnings("unchecked")
  private Set<String> parseAllowedActions(Claims claims) {
    List<String> actions = claims.get("allowed_actions", List.class);
    if (actions == null) return Collections.emptySet();
    return new HashSet<>(actions);
  }

  private void writeUnauthorized(HttpServletResponse resp, String message) throws IOException {
    resp.setStatus(401);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(resp.getOutputStream(), ApiError.of(401, message));
  }
}
