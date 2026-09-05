package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.ChangePasswordRequest;
import com.loai.inventory.api.dto.MeResponse;
import com.loai.inventory.api.dto.PushConfigResponse;
import com.loai.inventory.api.dto.PushSubscribeRequest;
import com.loai.inventory.api.dto.PushSubscriptionResponse;
import com.loai.inventory.api.dto.PushUnsubscribeRequest;
import com.loai.inventory.api.util.ClientIp;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.AuthenticationException;
import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.service.PushSubscriptionService;
import com.loai.inventory.service.auth.AuthService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The caller's own identity, mounted at {@code /api/me/*}. Any authenticated actor (no org scope).
 * Fills the gap that login echoes only ids — the app can now render "signed in as … — OWNER here".
 * See {@code stories/09_st_org_settings.md}.
 *
 * <ul>
 *   <li>{@code GET /api/me} — profile: identity (fresh DB read) + roles (from the token) +
 *       impersonation signal.
 *   <li>{@code POST /api/me/password} — self password change; blocked while impersonating.
 *   <li>{@code GET /api/me/push/config} — whether Web Push is on, and the VAPID public key.
 *   <li>{@code GET|POST|DELETE /api/me/push-subscriptions} — this browser's push subscription (V96,
 *       {@code stories/web_push_channel.md}); own-account only, blocked while impersonating.
 * </ul>
 */
public class MeServlet extends HttpServlet {

  private static final Logger log = LoggerFactory.getLogger(MeServlet.class);

  private static final String PUSH_CONFIG = "/push/config";
  private static final String PUSH_SUBSCRIPTIONS = "/push-subscriptions";

  private UserRepository userRepository;
  private AuthService authService;
  private PushSubscriptionService pushSubscriptionService;
  private ObjectMapper mapper;
  private boolean secureCookies;

  /** Container-constructed; wired in {@link #init()}. */
  public MeServlet() {}

  /** Visible for test: pre-wired, no servlet context. */
  MeServlet(
      UserRepository userRepository,
      AuthService authService,
      PushSubscriptionService pushSubscriptionService,
      ObjectMapper mapper,
      boolean secureCookies) {
    this.userRepository = userRepository;
    this.authService = authService;
    this.pushSubscriptionService = pushSubscriptionService;
    this.mapper = mapper;
    this.secureCookies = secureCookies;
  }

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.userRepository = config.userRepository;
    this.authService = config.authService;
    this.pushSubscriptionService = config.pushSubscriptionService;
    this.mapper = config.objectMapper;
    this.secureCookies = config.secureCookies;
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      if (isRoot(req.getPathInfo())) {
        handleProfile(req, resp);
      } else if (PUSH_CONFIG.equals(req.getPathInfo())) {
        handlePushConfig(req, resp);
      } else if (PUSH_SUBSCRIPTIONS.equals(req.getPathInfo())) {
        handleListPushSubscriptions(req, resp);
      } else {
        writeJson(resp, 404, ApiError.of(404, "Unknown endpoint"));
      }
    } catch (AppException e) {
      ApiErrors.applyHeaders(resp, e);
      writeJson(resp, e.getStatusCode(), ApiErrors.body(e));
    } catch (Exception e) {
      log.error("Unhandled exception in MeServlet", e);
      writeJson(resp, 500, ApiError.of(500, "Internal server error"));
    }
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      if ("/password".equals(req.getPathInfo())) {
        handleChangePassword(req, resp);
      } else if (PUSH_SUBSCRIPTIONS.equals(req.getPathInfo())) {
        handleSubscribePush(req, resp);
      } else {
        writeJson(resp, 404, ApiError.of(404, "Unknown endpoint"));
      }
    } catch (AppException e) {
      ApiErrors.applyHeaders(resp, e);
      writeJson(resp, e.getStatusCode(), ApiErrors.body(e));
    } catch (Exception e) {
      log.error("Unhandled exception in MeServlet", e);
      writeJson(resp, 500, ApiError.of(500, "Internal server error"));
    }
  }

  @Override
  protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      if (PUSH_SUBSCRIPTIONS.equals(req.getPathInfo())) {
        handleUnsubscribePush(req, resp);
      } else {
        writeJson(resp, 404, ApiError.of(404, "Unknown endpoint"));
      }
    } catch (AppException e) {
      ApiErrors.applyHeaders(resp, e);
      writeJson(resp, e.getStatusCode(), ApiErrors.body(e));
    } catch (Exception e) {
      log.error("Unhandled exception in MeServlet", e);
      writeJson(resp, 500, ApiError.of(500, "Internal server error"));
    }
  }

  private void handleProfile(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    SecurityContext ctx = AuthzHelper.requireAuth(req);
    AppUser user =
        userRepository
            .findById(ctx.actorId())
            .orElseThrow(() -> new AuthenticationException("User not found"));
    writeJson(resp, 200, MeResponse.from(user, ctx));
  }

  private void handleChangePassword(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    SecurityContext ctx = AuthzHelper.requireAuth(req);
    // An overlay must never rotate the target's credential — even a full (non-read-only) one.
    if (ctx.isImpersonating()) {
      throw new AuthorizationException("Cannot change password while impersonating");
    }
    ChangePasswordRequest body =
        mapper.readValue(req.getInputStream(), ChangePasswordRequest.class);
    String deviceInfo = req.getHeader("User-Agent");
    String sourceIp = ClientIp.resolve(req);

    AuthService.LoginResult result =
        authService.changePassword(
            ctx.actorId(), body.getCurrentPassword(), body.getNewPassword(), deviceInfo, sourceIp);

    // Every other device was signed out; keep this one alive with fresh cookies at the new version.
    AuthCookies.writeAccess(resp, result.accessToken(), (int) result.expiresIn(), secureCookies);
    AuthCookies.writeRefresh(
        resp, result.refreshToken(), AuthCookies.REFRESH_MAX_AGE, secureCookies);
    resp.setStatus(204);
  }

  // Web Push (V96) — the device's side of the channel

  private void handlePushConfig(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    requireOwnAccount(req);
    var config = pushSubscriptionService.config();
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(resp, 200, PushConfigResponse.of(config.enabled(), config.publicKeyBase64Url()));
  }

  private void handleListPushSubscriptions(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    SecurityContext ctx = requireOwnAccount(req);
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(
        resp,
        200,
        pushSubscriptionService.listLive(ctx.actorId()).stream()
            .map(PushSubscriptionResponse::from)
            .toList());
  }

  private void handleSubscribePush(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    SecurityContext ctx = requireOwnAccount(req);
    PushSubscribeRequest body = mapper.readValue(req.getInputStream(), PushSubscribeRequest.class);
    String p256dh = body.getKeys() == null ? null : body.getKeys().getP256dh();
    String auth = body.getKeys() == null ? null : body.getKeys().getAuth();
    // The client may label the device; fall back to what the browser sent on this very request.
    String userAgent =
        body.getUserAgent() == null || body.getUserAgent().isBlank()
            ? req.getHeader("User-Agent")
            : body.getUserAgent();
    PushSubscriptionService.SubscribeResult result =
        pushSubscriptionService.subscribe(
            ctx.actorId(), body.getEndpoint(), p256dh, auth, userAgent);
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(
        resp, result.created() ? 201 : 200, PushSubscriptionResponse.from(result.subscription()));
  }

  private void handleUnsubscribePush(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    SecurityContext ctx = requireOwnAccount(req);
    PushUnsubscribeRequest body =
        mapper.readValue(req.getInputStream(), PushUnsubscribeRequest.class);
    pushSubscriptionService.unsubscribe(ctx.actorId(), body.getEndpoint());
    resp.setStatus(204);
  }

  /**
   * The push endpoints act on the caller's OWN devices. An impersonation overlay must never
   * subscribe an operator's browser to the target's notifications, nor list or drop the target's
   * devices — same rule as the password change.
   */
  private static SecurityContext requireOwnAccount(HttpServletRequest req) {
    SecurityContext ctx = AuthzHelper.requireAuth(req);
    if (ctx.isImpersonating()) {
      throw new AuthorizationException("Not available while impersonating");
    }
    return ctx;
  }

  private boolean isRoot(String pathInfo) {
    return pathInfo == null || pathInfo.isEmpty() || pathInfo.equals("/");
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }
}
