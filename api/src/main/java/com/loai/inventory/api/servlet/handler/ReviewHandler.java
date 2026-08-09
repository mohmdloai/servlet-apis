package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.ReviewAdminResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.ReviewStatus;
import com.loai.inventory.service.ListingReviewService;
import com.loai.inventory.service.ListingReviewService.AdminPage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code /api/orgs/{orgId}/reviews} — the staff review-moderation worklist (slice R1,
 * {@code stories/storefront_reviews.md}):
 *
 * <ul>
 *   <li>{@code GET /reviews?status=&page=&size=} — filtered = queue oldest-first ({@code
 *       ?status=PENDING} is the moderation queue), unfiltered = ledger newest-first; unknown status
 *       → 400. Rows carry customer name/email (staff already see CRM) + listing title. VIEWER+.
 *   <li>{@code POST /reviews/{id}/approve} · {@code /reject} — moderate (STAFF+). The merchant
 *       never rewrites a customer's words — there is no edit.
 * </ul>
 */
public class ReviewHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(ReviewHandler.class);

  private final ListingReviewService service;
  private final ObjectMapper mapper;

  public ReviewHandler(ListingReviewService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method,
      HttpServletRequest req,
      HttpServletResponse resp,
      UUID orgId,
      String remainingPath)
      throws IOException {
    try {
      String[] parts = splitPath(remainingPath);
      if (parts.length == 0) {
        if ("GET".equals(method)) {
          doList(req, resp, orgId);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      if (parts.length == 2 && "POST".equals(method)) {
        UUID reviewId = parseId(parts[0]);
        switch (parts[1]) {
          case "approve" -> doModerate(req, resp, orgId, reviewId, ReviewStatus.APPROVED);
          case "reject" -> doModerate(req, resp, orgId, reviewId, ReviewStatus.REJECTED);
          default -> throw new ValidationException("Unknown reviews route: " + remainingPath);
        }
        return;
      }
      throw new ValidationException("Unknown reviews route: " + remainingPath);
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/reviews{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  /** {@code GET /} — the moderation worklist (filtered queue) / review ledger. */
  private void doList(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    // Clamp here too so the envelope echoes the page/size actually served.
    int page = Math.max(intParam(req, "page", 0), 0);
    int size =
        Math.min(
            Math.max(intParam(req, "size", ListingReviewService.DEFAULT_PAGE_SIZE), 1),
            ListingReviewService.MAX_PAGE_SIZE);
    AdminPage result = service.adminList(orgId, req.getParameter("status"), page, size);
    List<ReviewAdminResponse> data =
        result.items().stream().map(ReviewAdminResponse::from).toList();
    writeJson(resp, 200, new PageResponse<>(data, result.total(), page, size));
  }

  private void doModerate(
      HttpServletRequest req,
      HttpServletResponse resp,
      UUID orgId,
      UUID reviewId,
      ReviewStatus decision)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    var review = service.moderate(orgId, reviewId, decision);
    // The row leaves the queue with its new status; the caller refreshes the list for context.
    writeJson(
        resp, 200, java.util.Map.of("id", review.getId(), "status", review.getStatus().name()));
  }

  private static String[] splitPath(String remainingPath) {
    if (remainingPath == null || remainingPath.isEmpty() || "/".equals(remainingPath)) {
      return new String[0];
    }
    String raw = remainingPath.startsWith("/") ? remainingPath.substring(1) : remainingPath;
    if (raw.isEmpty()) {
      return new String[0];
    }
    return raw.split("/");
  }

  private static UUID parseId(String s) {
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid review id: " + s);
    }
  }

  private static int intParam(HttpServletRequest req, String name, int defaultValue) {
    String value = req.getParameter(name);
    if (value == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new ValidationException("Parameter '" + name + "' must be an integer");
    }
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse resp, AppException e) throws IOException {
    ApiErrors.applyHeaders(resp, e);
    writeJson(resp, e.getStatusCode(), ApiErrors.body(e));
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }
}
