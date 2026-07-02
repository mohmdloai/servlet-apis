package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.MagicLinkService.ResolvedUnsubscribe;
import com.loai.inventory.service.NotificationService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

/**
 * Anonymous one-click unsubscribe at {@code /api/public/unsubscribe/{token}} — the customer side of
 * notification preferences (notifications-plan §4/§7). Mounted outside the JWT filter (the {@code
 * /api/public/} bypass); the unguessable {@code UNSUBSCRIBE} magic token is the capability, scoped
 * to one customer's email.
 *
 * <p>Resolving the token flips the customer's email off for the org (idempotent: re-clicks return
 * {@code 200} while the token is live). GET and POST both apply — GET supports a plain email-client
 * click; the mail-prefetch trade-off and a confirm-page/{@code List-Unsubscribe-Post} hardening are
 * a later refinement (see the story). Unknown/expired tokens answer an opaque {@code 404}.
 */
public class PublicUnsubscribeServlet extends HttpServlet {

  private MagicLinkService magicLinkService;
  private NotificationService notificationService;
  private ObjectMapper mapper;

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.magicLinkService = config.magicLinkService;
    this.notificationService = config.notificationService;
    this.mapper = config.objectMapper;
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      String method = req.getMethod();
      if (!"GET".equals(method) && !"POST".equals(method)) {
        writeError(resp, 405, "Method not allowed");
        return;
      }
      String token = extractToken(req.getPathInfo());
      if (token == null) {
        writeError(resp, 404, "Not found");
        return;
      }
      OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
      Optional<ResolvedUnsubscribe> resolved = magicLinkService.resolveUnsubscribe(token, now);
      if (resolved.isEmpty()) {
        writeError(resp, 404, "Not found");
        return;
      }
      ResolvedUnsubscribe u = resolved.get();
      notificationService.unsubscribeCustomerEmail(u.orgId(), u.customerId());
      writeJson(resp, 200, Map.of("unsubscribed", true));
    } catch (Exception e) {
      writeError(resp, 500, "Internal server error");
    }
  }

  /** {@code /{token}} → the token; anything else (missing/nested) → null. */
  private static String extractToken(String pathInfo) {
    if (pathInfo == null || pathInfo.length() < 2) {
      return null;
    }
    String raw = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    if (raw.isBlank() || raw.contains("/")) {
      return null;
    }
    return raw;
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }
}
