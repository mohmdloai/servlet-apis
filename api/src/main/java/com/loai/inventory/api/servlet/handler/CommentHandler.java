package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.CommentAdminResponse;
import com.loai.inventory.api.dto.CommentReplyRequest;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.ListingCommentService;
import com.loai.inventory.service.ListingCommentService.AdminPage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code /api/orgs/{orgId}/comments} — the staff answer worklist (slice R2, {@code
 * stories/storefront_comments.md}):
 *
 * <ul>
 *   <li>{@code GET /comments?status=&page=&size=} — filtered = queue oldest-first ({@code
 *       ?status=PENDING} is the to-answer queue), unfiltered = ledger newest-first; unknown status
 *       → 400. VIEWER+.
 *   <li>{@code POST /comments/{id}/reply {body}} — publish the Q&amp;A pair + notify the asker
 *       (first reply only; a re-reply edits the answer, no re-notification). STAFF+.
 *   <li>{@code POST /comments/{id}/dismiss} — silent, terminal. STAFF+.
 * </ul>
 */
public class CommentHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(CommentHandler.class);

  private final ListingCommentService service;
  private final ObjectMapper mapper;

  public CommentHandler(ListingCommentService service, ObjectMapper mapper) {
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
        UUID commentId = parseId(parts[0]);
        switch (parts[1]) {
          case "reply" -> doReply(req, resp, orgId, commentId);
          case "dismiss" -> doDismiss(req, resp, orgId, commentId);
          default -> throw new ValidationException("Unknown comments route: " + remainingPath);
        }
        return;
      }
      throw new ValidationException("Unknown comments route: " + remainingPath);
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/comments{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  /** {@code GET /} — the answer worklist (filtered queue) / comment ledger. */
  private void doList(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    // Clamp here too so the envelope echoes the page/size actually served.
    int page = Math.max(intParam(req, "page", 0), 0);
    int size =
        Math.min(
            Math.max(intParam(req, "size", ListingCommentService.DEFAULT_PAGE_SIZE), 1),
            ListingCommentService.MAX_PAGE_SIZE);
    AdminPage result = service.adminList(orgId, req.getParameter("status"), page, size);
    List<CommentAdminResponse> data =
        result.items().stream().map(CommentAdminResponse::from).toList();
    writeJson(resp, 200, new PageResponse<>(data, result.total(), page, size));
  }

  /** Answering IS the moderation act (epic §4) — this publishes the pair and closes the loop. */
  private void doReply(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    CommentReplyRequest body = readBody(req, CommentReplyRequest.class);
    var comment = service.reply(orgId, sc.actorId(), id, body.getBody());
    writeJson(
        resp,
        200,
        Map.of(
            "id",
            comment.getId(),
            "status",
            comment.getStatus().name(),
            "reply_body",
            comment.getReplyBody()));
  }

  private void doDismiss(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    var comment = service.dismiss(orgId, id);
    writeJson(resp, 200, Map.of("id", comment.getId(), "status", comment.getStatus().name()));
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
      throw new ValidationException("Invalid comment id: " + s);
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

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    try {
      return mapper.readValue(req.getInputStream(), type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ValidationException("request body is required and must be valid JSON");
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
}
