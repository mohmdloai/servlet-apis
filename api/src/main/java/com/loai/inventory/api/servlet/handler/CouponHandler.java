package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.CouponResponse;
import com.loai.inventory.api.dto.CreateCouponRequest;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.PatchCouponRequest;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Coupon;
import com.loai.inventory.domain.model.CouponType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.service.CouponService;
import com.loai.inventory.service.CouponService.CouponInput;
import com.loai.inventory.service.CouponService.CouponPatch;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes under {@code /api/orgs/{orgId}/coupons} (roadmap item 9, {@code
 * stories/honest_coupons.md}).
 *
 * <ul>
 *   <li>{@code GET /?page&size} — newest-first page, each row with its live {@code
 *       redemption_count}
 *   <li>{@code POST /} — create ({@code code}, {@code type}, {@code value} + optional window /
 *       minimum / cap)
 *   <li>{@code GET /{id}} · {@code PATCH /{id}} — read / edit {@code active}, expiry, cap
 *   <li>{@code DELETE /{id}} — only while never redeemed (409 naming deactivation as the remedy)
 * </ul>
 *
 * Reads are VIEWER; every write is <b>MANAGER</b> — a coupon changes what the store charges, so it
 * takes the same authority as the other money-shaped mutations, not the STAFF merchandising gate.
 */
public class CouponHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(CouponHandler.class);

  private final CouponService service;
  private final ObjectMapper mapper;

  public CouponHandler(CouponService service, ObjectMapper mapper) {
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
      String tail = normalize(remainingPath);

      if (tail.isEmpty()) {
        switch (method) {
          case "GET" -> doList(req, resp, orgId);
          case "POST" -> doCreate(req, resp, orgId);
          default -> writeError(resp, 405, "Method not allowed");
        }
        return;
      }

      String[] parts = tail.split("/");
      if (parts.length != 1) {
        throw new ValidationException("Unknown route: /coupons/" + tail);
      }
      UUID id = parseId(parts[0]);
      switch (method) {
        case "GET" -> doGetOne(req, resp, orgId, id);
        case "PATCH" -> doPatch(req, resp, orgId, id);
        case "DELETE" -> doDelete(req, resp, orgId, id);
        default -> writeError(resp, 405, "Method not allowed");
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/coupons{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doList(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    int page = intParam(req, "page", 0);
    int size = intParam(req, "size", 20);
    List<CouponResponse> data =
        service.getAll(orgId, page, size).stream().map(CouponResponse::from).toList();
    writeJson(resp, 200, new PageResponse<>(data, service.count(orgId), page, size));
  }

  private void doCreate(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    CreateCouponRequest body = readBody(req, CreateCouponRequest.class);
    Coupon created =
        service.create(
            orgId,
            new CouponInput(
                body.getCode(),
                parseType(body.getType()),
                body.getValue(),
                body.getMinSubtotal(),
                body.getStartsAt(),
                body.getExpiresAt(),
                body.getMaxRedemptions()));
    // Re-read so the response carries the redemption count in the same shape as every other read.
    writeJson(resp, 201, CouponResponse.from(service.getById(orgId, created.getId())));
  }

  private void doGetOne(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    writeJson(resp, 200, CouponResponse.from(service.getById(orgId, id)));
  }

  private void doPatch(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    PatchCouponRequest body = readBody(req, PatchCouponRequest.class);
    service.patch(
        orgId,
        id,
        new CouponPatch(body.getActive(), body.getExpiresAt(), body.getMaxRedemptions()));
    writeJson(resp, 200, CouponResponse.from(service.getById(orgId, id)));
  }

  private void doDelete(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    service.delete(orgId, id);
    resp.setStatus(204);
  }

  /** {@code type} is a required enum name; anything else is a cause-naming 400, never a default. */
  private static CouponType parseType(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ValidationException("type must be PERCENT or FIXED");
    }
    try {
      return CouponType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ValidationException("type must be PERCENT or FIXED (was '" + raw + "')");
    }
  }

  private static String normalize(String remainingPath) {
    if (remainingPath == null) {
      return "";
    }
    String t = remainingPath.startsWith("/") ? remainingPath.substring(1) : remainingPath;
    while (t.endsWith("/")) {
      t = t.substring(0, t.length() - 1);
    }
    return t;
  }

  private UUID parseId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid coupon id format: " + raw);
    }
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    try {
      return mapper.readValue(req.getInputStream(), type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ValidationException("malformed JSON body");
    }
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse resp, AppException e) throws IOException {
    writeJson(resp, e.getStatusCode(), ApiError.of(e.getStatusCode(), e.getMessage()));
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }

  private int intParam(HttpServletRequest req, String name, int defaultValue) {
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
}
