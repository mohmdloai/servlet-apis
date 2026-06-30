package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.PublicCategoryResponse;
import com.loai.inventory.api.dto.PublicListingResponse;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.StorefrontService.ListingPage;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * Anonymous, CDN-cacheable storefront read API at {@code /api/public/*}. Mounted outside the JWT
 * filter (see {@code JwtAuthFilter}'s {@code /api/public/} bypass). Serves only PUBLISHED listings
 * through {@link StorefrontService} with whitelisted DTOs.
 *
 * <ul>
 *   <li>{@code GET /api/public/{orgSlug}/listings} — published listings (paged; {@code
 *       ?category=<slug>}, {@code ?page}, {@code ?size})
 *   <li>{@code GET /api/public/{orgSlug}/listings/{listingSlug}} — one published listing
 *   <li>{@code GET /api/public/{orgSlug}/categories} — category nav
 * </ul>
 */
public class PublicStorefrontServlet extends HttpServlet {

  /** Short cache: safely below the presigned image-URL TTL so cached URLs stay valid. */
  private static final String CACHE_CONTROL = "public, max-age=60";

  private StorefrontService service;
  private ObjectMapper mapper;

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.service = config.storefrontService;
    this.mapper = config.objectMapper;
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      if (!"GET".equals(req.getMethod())) {
        writeError(resp, 405, "Method not allowed");
        return;
      }

      String[] parts = splitPath(req.getPathInfo());
      // parts: [orgSlug, resource, (listingSlug)]
      if (parts.length < 2) {
        throw new ValidationException("Expected /api/public/{orgSlug}/{listings|categories}");
      }
      String orgSlug = parts[0];
      String resource = parts[1];

      switch (resource) {
        case "listings" -> {
          if (parts.length == 2) {
            int page = intParam(req, "page", 0);
            int size = intParam(req, "size", 20);
            String category = req.getParameter("category");
            ListingPage p = service.listPublished(orgSlug, category, page, size);
            List<PublicListingResponse> data =
                p.items().stream().map(PublicListingResponse::from).toList();
            writeJson(resp, 200, new PageResponse<>(data, p.total(), p.page(), p.size()));
          } else if (parts.length == 3) {
            writeJson(resp, 200, PublicListingResponse.from(service.getListing(orgSlug, parts[2])));
          } else {
            throw new ValidationException("Unknown route");
          }
        }
        case "categories" -> {
          if (parts.length != 2) {
            throw new ValidationException("Unknown route");
          }
          List<PublicCategoryResponse> data =
              service.listCategories(orgSlug).stream().map(PublicCategoryResponse::from).toList();
          writeJson(resp, 200, data);
        }
        default -> throw new ValidationException("Unknown resource: " + resource);
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      writeError(resp, 500, "Internal server error");
    }
  }

  private static String[] splitPath(String pathInfo) {
    if (pathInfo == null || pathInfo.isEmpty() || pathInfo.equals("/")) {
      return new String[0];
    }
    String raw = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    if (raw.endsWith("/")) {
      raw = raw.substring(0, raw.length() - 1);
    }
    return raw.split("/");
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    if (status == 200) {
      resp.setHeader("Cache-Control", CACHE_CONTROL);
    }
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
