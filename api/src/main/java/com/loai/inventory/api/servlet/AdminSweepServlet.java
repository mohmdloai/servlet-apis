package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.service.OrderExpiryService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manual, SYSTEM_ADMIN-only trigger for the order-TTL sweep — {@code POST /api/admin/sweep}. Runs
 * the sweep synchronously and returns the {@link OrderExpiryService.Summary} counts. Intended for
 * ops and for the slice's integration tests (deterministic, no waiting on the JobRunr tick). The
 * sweep is a global operation — no {@code orgId} in the path.
 */
public class AdminSweepServlet extends HttpServlet {

  private static final Logger log = LoggerFactory.getLogger(AdminSweepServlet.class);

  private static final int DEFAULT_BATCH_LIMIT = 200;

  private OrderExpiryService orderExpiryService;
  private ObjectMapper mapper;

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.orderExpiryService = config.orderExpiryService;
    this.mapper = config.objectMapper;
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      String path = req.getPathInfo();
      if (!"/sweep".equals(path)) {
        writeJson(resp, 404, ApiError.of(404, "Unknown admin endpoint"));
        return;
      }
      AuthzHelper.requireAdmin(req);

      int batchLimit = parseBatchLimit(req.getParameter("batchLimit"));
      OrderExpiryService.Summary summary = orderExpiryService.sweep(batchLimit);
      writeJson(resp, 200, summary);
    } catch (AppException e) {
      writeJson(resp, e.getStatusCode(), ApiError.of(e.getStatusCode(), e.getMessage()));
    } catch (Exception e) {
      log.error("Unhandled exception in AdminSweepServlet", e);
      writeJson(resp, 500, ApiError.of(500, "Internal server error"));
    }
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
