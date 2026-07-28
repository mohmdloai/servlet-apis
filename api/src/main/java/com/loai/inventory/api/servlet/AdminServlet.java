package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.servlet.handler.AdminResourceHandler;
import com.loai.inventory.api.servlet.handler.AuditAdminHandler;
import com.loai.inventory.api.servlet.handler.FunnelAdminHandler;
import com.loai.inventory.api.servlet.handler.OrgAdminHandler;
import com.loai.inventory.api.servlet.handler.OverviewAdminHandler;
import com.loai.inventory.api.servlet.handler.QueuesAdminHandler;
import com.loai.inventory.api.servlet.handler.SearchAdminHandler;
import com.loai.inventory.api.servlet.handler.UserAdminHandler;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.service.NumberSequenceReconciliationService;
import com.loai.inventory.service.OrderExpiryService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single dispatcher servlet for the platform plane at {@code /api/admin/*} - the container above
 * orgs, gated on {@code SystemRole} (ADMIN/SUPPORT), not on any {@code OrgRole}. Mirrors {@code
 * OrgServlet}: one router parses {@code pathInfo} and delegates to a per-resource {@link
 * AdminResourceHandler}. Routes:
 *
 * <ul>
 *   <li>{@code POST /sweep} - the manual order-TTL sweep (folded in from the old AdminSweepServlet)
 *   <li>{@code /orgs[/{orgId}[/…]]} → {@link OrgAdminHandler} (cross-org read console + lifecycle)
 *   <li>{@code /users[/{userId}[/…]]} → {@link UserAdminHandler} (user/role admin + session ops)
 *   <li>{@code /audit} → {@link AuditAdminHandler} (the append-only platform audit ledger)
 *   <li>{@code /overview} → {@link OverviewAdminHandler} (the operator's home rollup)
 *   <li>{@code /queues/{kind}} → {@link QueuesAdminHandler} (the cross-org drill-down behind the
 *       overview's backlog tiles)
 *   <li>{@code /search?q=} → {@link SearchAdminHandler} (cross-org lookup by an identifier a
 *       customer quoted)
 * </ul>
 *
 * <p>{@code /api/admin/impersonate/*} is mapped to the more-specific {@code
 * PlatformImpersonationServlet} (Tomcat longest-path match wins), so it never reaches here.
 */
public class AdminServlet extends HttpServlet {

  private static final Logger log = LoggerFactory.getLogger(AdminServlet.class);

  private static final int DEFAULT_BATCH_LIMIT = 200;

  private OrderExpiryService orderExpiryService;
  private NumberSequenceReconciliationService numberSequenceReconciliationService;
  private Map<String, AdminResourceHandler> resources;
  private ObjectMapper mapper;

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.mapper = config.objectMapper;
    this.orderExpiryService = config.orderExpiryService;
    this.numberSequenceReconciliationService = config.numberSequenceReconciliationService;
    this.resources =
        Map.of(
            "orgs",
            new OrgAdminHandler(
                config.platformOrgService, config.platformOrgTimelineService, mapper),
            "users",
            new UserAdminHandler(
                config.userAdminService, config.authService, config.platformAuditService, mapper),
            "audit",
            new AuditAdminHandler(config.platformAuditService, mapper),
            "overview",
            new OverviewAdminHandler(config.platformOverviewService, mapper),
            "queues",
            new QueuesAdminHandler(config.platformQueueService, mapper),
            "search",
            new SearchAdminHandler(config.platformSearchService, mapper),
            "funnel",
            new FunnelAdminHandler(config.platformFunnelService, mapper));
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      Route route = parseRoute(req.getPathInfo());
      if ("sweep".equals(route.resource())) {
        handleSweep(req, resp);
        return;
      }
      if ("reconcile-number-sequences".equals(route.resource())) {
        handleReconcileNumberSequences(req, resp);
        return;
      }
      AdminResourceHandler handler = resources.get(route.resource());
      if (handler == null) {
        writeJson(resp, 404, ApiError.of(404, "Unknown admin endpoint"));
        return;
      }
      handler.handle(req.getMethod(), req, resp, route.remaining());
    } catch (AppException e) {
      writeJson(resp, e.getStatusCode(), ApiError.of(e.getStatusCode(), e.getMessage()));
    } catch (Exception e) {
      log.error("Unhandled exception in AdminServlet", e);
      writeJson(resp, 500, ApiError.of(500, "Internal server error"));
    }
  }

  /** {@code POST /api/admin/sweep} - synchronous order-TTL sweep. ADMIN only. */
  private void handleSweep(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    if (!"POST".equals(req.getMethod())) {
      writeJson(resp, 405, ApiError.of(405, "Method not allowed"));
      return;
    }
    AuthzHelper.requireAdmin(req);
    int batchLimit = parseBatchLimit(req.getParameter("batchLimit"));
    OrderExpiryService.Summary summary = orderExpiryService.sweep(batchLimit);
    writeJson(resp, 200, summary);
  }

  /**
   * {@code POST /api/admin/reconcile-number-sequences} — forward-only heal of drifted invoice /
   * credit-note counters (see {@code stories/number_sequence_integrity.md}). ADMIN only;
   * idempotent, so a healthy database returns an empty realignment list.
   */
  private void handleReconcileNumberSequences(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    if (!"POST".equals(req.getMethod())) {
      writeJson(resp, 405, ApiError.of(405, "Method not allowed"));
      return;
    }
    AuthzHelper.requireAdmin(req);
    NumberSequenceReconciliationService.Summary summary =
        numberSequenceReconciliationService.reconcile();
    writeJson(resp, 200, summary);
  }

  /**
   * @param resource the first path segment ({@code "sweep"}, {@code "orgs"}, …); empty at the root
   * @param remaining the path tail after the resource segment, with a leading slash or empty
   */
  private record Route(String resource, String remaining) {}

  private Route parseRoute(String pathInfo) {
    if (pathInfo == null || pathInfo.isEmpty() || pathInfo.equals("/")) {
      return new Route("", "");
    }
    String raw = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    int slash = raw.indexOf('/');
    if (slash < 0) {
      return new Route(raw, "");
    }
    return new Route(raw.substring(0, slash), raw.substring(slash));
  }

  private int parseBatchLimit(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_BATCH_LIMIT;
    }
    try {
      int parsed = Integer.parseInt(raw.trim());
      if (parsed <= 0) {
        throw new ValidationException("batchLimit must be > 0");
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new ValidationException("batchLimit must be an integer");
    }
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }
}
