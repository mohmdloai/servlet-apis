package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.CategoryResponse;
import com.loai.inventory.api.dto.CategoryTranslationDto;
import com.loai.inventory.api.dto.CreateCategoryRequest;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.UpdateCategoryRequest;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Category;
import com.loai.inventory.domain.model.CategoryTranslation;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.service.CategoryService;
import com.loai.inventory.service.CategoryService.CategoryView;
import com.loai.inventory.service.CategoryService.TranslatedNameInput;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Routes under {@code /api/orgs/{orgId}/categories}. VIEWER read, STAFF write, MANAGER delete. */
public class CategoryHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(CategoryHandler.class);

  private final CategoryService categoryService;
  private final ObjectMapper mapper;

  public CategoryHandler(CategoryService categoryService, ObjectMapper mapper) {
    this.categoryService = categoryService;
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
      UUID categoryId = parseId(remainingPath);
      switch (method) {
        case "GET" -> doGet(req, resp, orgId, categoryId);
        case "POST" -> doPost(req, resp, orgId, categoryId);
        case "PUT" -> doPut(req, resp, orgId, categoryId);
        case "DELETE" -> doDelete(req, resp, orgId, categoryId);
        default -> writeError(resp, 405, "Method not allowed");
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/categories{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doGet(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID categoryId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    if (categoryId == null) {
      int page = intParam(req, "page", 0);
      int size = intParam(req, "size", 10);
      List<CategoryView> categories = categoryService.getAll(orgId, page, size);
      long total = categoryService.count(orgId);
      List<CategoryResponse> data = categories.stream().map(CategoryResponse::from).toList();
      writeJson(resp, 200, new PageResponse<>(data, total, page, size));
    } else {
      CategoryView category = categoryService.getById(orgId, categoryId);
      writeJson(resp, 200, CategoryResponse.from(category));
    }
  }

  private void doPost(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID categoryId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    if (categoryId != null) {
      throw new ValidationException("POST does not accept a category id in the path");
    }
    CreateCategoryRequest body = readBody(req, CreateCategoryRequest.class);
    TranslatedNameInput content =
        new TranslatedNameInput(toDomainTranslations(body.getTranslations()), body.getName());
    Category created =
        categoryService.create(orgId, body.getSlug(), body.getParentCategoryId(), content);
    // Re-read for the full embed (all languages), mirroring the listing handler.
    writeJson(resp, 201, CategoryResponse.from(categoryService.getById(orgId, created.getId())));
  }

  private void doPut(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID categoryId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    if (categoryId == null) {
      throw new ValidationException("Category id is required for update");
    }
    UpdateCategoryRequest body = readBody(req, UpdateCategoryRequest.class);
    TranslatedNameInput content =
        new TranslatedNameInput(toDomainTranslations(body.getTranslations()), body.getName());
    categoryService.update(orgId, categoryId, body.getSlug(), body.getParentCategoryId(), content);
    // Re-read for the full embed (all languages), mirroring the listing handler.
    writeJson(resp, 200, CategoryResponse.from(categoryService.getById(orgId, categoryId)));
  }

  private static List<CategoryTranslation> toDomainTranslations(List<CategoryTranslationDto> dtos) {
    if (dtos == null) {
      return null;
    }
    return dtos.stream().map(CategoryTranslationDto::toDomain).toList();
  }

  private void doDelete(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID categoryId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    if (categoryId == null) {
      throw new ValidationException("Category id is required for delete");
    }
    categoryService.delete(orgId, categoryId);
    resp.setStatus(204);
  }

  private UUID parseId(String remainingPath) {
    if (remainingPath == null || remainingPath.isEmpty() || remainingPath.equals("/")) {
      return null;
    }
    String raw = remainingPath.startsWith("/") ? remainingPath.substring(1) : remainingPath;
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid category id format: " + raw);
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
    if (value == null) return defaultValue;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new ValidationException("Parameter '" + name + "' must be an integer");
    }
  }
}
