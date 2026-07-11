package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.AddMemberRequest;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.MemberResponse;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.SetMemberRoleRequest;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgMember;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.service.MemberService;
import com.loai.inventory.service.MemberService.MemberPage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes under {@code /api/orgs/{orgId}/members}: the org team roster + role management. MANAGER
 * reads the roster; every write is OWNER. The org-scoped, membership-gated counterpart to the
 * platform {@code /api/admin/users/*} console. See {@code stories/09_st_org_settings.md}.
 */
public class MemberHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(MemberHandler.class);

  private final MemberService memberService;
  private final ObjectMapper mapper;

  public MemberHandler(MemberService memberService, ObjectMapper mapper) {
    this.memberService = memberService;
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
      UUID userId = parseId(remainingPath);
      switch (method) {
        case "GET" -> doGet(req, resp, orgId, userId);
        case "POST" -> doPost(req, resp, orgId, userId);
        case "PUT" -> doPut(req, resp, orgId, userId);
        case "DELETE" -> doDelete(req, resp, orgId, userId);
        default -> writeError(resp, 405, "Method not allowed");
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/members{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doGet(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID userId)
      throws IOException {
    // Reading the team is a lead's read (MANAGER); the writes below are OWNER.
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    if (userId != null) {
      throw new ValidationException("Member detail by id is not supported; read the roster");
    }
    // Paginated envelope ({data, total, page, size}) so the roster tile reads its count as `total`
    // rather than loading the whole team — see stories/org_health_rollup.md (G4).
    int page = Math.max(intParam(req, "page", 0), 0);
    int size =
        Math.min(
            Math.max(intParam(req, "size", MemberService.DEFAULT_PAGE_SIZE), 1),
            MemberService.MAX_PAGE_SIZE);
    MemberPage result = memberService.listMembers(orgId, page, size);
    List<MemberResponse> data = result.items().stream().map(MemberResponse::from).toList();
    writeJson(resp, 200, new PageResponse<>(data, result.total(), page, size));
  }

  private void doPost(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID userId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.OWNER);
    if (userId != null) {
      throw new ValidationException("POST does not accept a user id in the path");
    }
    AddMemberRequest body = readBody(req, AddMemberRequest.class);
    OrgMember member = memberService.addMember(orgId, body.getEmail(), parseRole(body.getRole()));
    writeJson(resp, 201, MemberResponse.from(member));
  }

  private void doPut(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID userId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.OWNER);
    if (userId == null) {
      throw new ValidationException("User id is required in the path");
    }
    SetMemberRoleRequest body = readBody(req, SetMemberRoleRequest.class);
    OrgMember member = memberService.setRole(orgId, userId, parseRole(body.getRole()));
    writeJson(resp, 200, MemberResponse.from(member));
  }

  private void doDelete(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID userId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.OWNER);
    if (userId == null) {
      throw new ValidationException("User id is required in the path");
    }
    memberService.removeMember(orgId, userId);
    resp.setStatus(204);
  }

  private OrgRole parseRole(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ValidationException("role is required");
    }
    try {
      return OrgRole.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid role: " + raw);
    }
  }

  private UUID parseId(String remainingPath) {
    if (remainingPath == null || remainingPath.isEmpty() || remainingPath.equals("/")) {
      return null;
    }
    String raw = remainingPath.startsWith("/") ? remainingPath.substring(1) : remainingPath;
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid user id format: " + raw);
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
}
