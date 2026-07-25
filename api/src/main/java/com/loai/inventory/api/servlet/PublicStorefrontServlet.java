package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.PortalMeResponse;
import com.loai.inventory.api.dto.PortalRequestCodeRequest;
import com.loai.inventory.api.dto.PortalVerifyCodeRequest;
import com.loai.inventory.api.dto.PublicAvailabilityResponse;
import com.loai.inventory.api.dto.PublicBannerResponse;
import com.loai.inventory.api.dto.PublicCategoryResponse;
import com.loai.inventory.api.dto.PublicCheckoutError;
import com.loai.inventory.api.dto.PublicCheckoutRequest;
import com.loai.inventory.api.dto.PublicCollectionResponse;
import com.loai.inventory.api.dto.PublicCommentResponse;
import com.loai.inventory.api.dto.PublicCouponResponse;
import com.loai.inventory.api.dto.PublicListingResponse;
import com.loai.inventory.api.dto.PublicListingsPageResponse;
import com.loai.inventory.api.dto.PublicOrderResponse;
import com.loai.inventory.api.dto.PublicPageResponse;
import com.loai.inventory.api.dto.PublicPageSummaryResponse;
import com.loai.inventory.api.dto.PublicReviewResponse;
import com.loai.inventory.api.dto.StorefrontProfileResponse;
import com.loai.inventory.api.dto.ValidateCouponRequest;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.service.CouponService;
import com.loai.inventory.service.ListingCommentService;
import com.loai.inventory.service.ListingReviewService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.StorefrontPageService;
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.StorefrontService.CheckoutInput;
import com.loai.inventory.service.StorefrontService.CheckoutLine;
import com.loai.inventory.service.StorefrontService.CheckoutResult;
import com.loai.inventory.service.StorefrontService.ListingPage;
import com.loai.inventory.service.auth.CustomerAuthService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Anonymous storefront front door at {@code /api/public/*} (JWT-bypassed — see {@code
 * JwtAuthFilter}, rate-limited — see {@code RateLimitFilter}/B4). Reads serve only PUBLISHED
 * listings through {@link StorefrontService} with whitelisted DTOs; the one write is anonymous
 * checkout.
 *
 * <ul>
 *   <li>{@code GET /api/public/{orgSlug}} — storefront profile (branding + payment instructions,
 *       B1); {@code Cache-Control: max-age=300}
 *   <li>{@code GET /api/public/{orgSlug}/listings[/{slug}]} — published listings (+ {@code
 *       in_stock}, B2; {@code ?q=&min_price=&max_price=&sort=} search/filters, B3); {@code
 *       max-age=60}
 *   <li>{@code GET /api/public/{orgSlug}/categories} — category nav; {@code max-age=60}
 *   <li>{@code GET /api/public/{orgSlug}/collections} — the collections rail (roadmap item 8): only
 *       collections holding ≥1 PUBLISHED listing, {@code {slug, name}} locale-resolved; {@code
 *       max-age=300}
 *   <li>{@code GET /api/public/{orgSlug}/og-image} — the stable social-share image stream (bytes,
 *       og key → logo fallback → 404, C2); {@code max-age=3600}
 *   <li>{@code GET /api/public/{orgSlug}/availability?slugs=a,b,c} — batch in-stock (B2); {@code
 *       max-age=15}
 *   <li>{@code GET /api/public/{orgSlug}/pages} — the kinds that exist (footer link source, C4);
 *       {@code max-age=300}
 *   <li>{@code GET /api/public/{orgSlug}/pages/{kind}} — one text page, both bodies verbatim (C4);
 *       {@code max-age=300}
 *   <li>{@code POST /api/public/{orgSlug}/coupons/validate} — the anonymous coupon preview (roadmap
 *       item 9): {@code {code, subtotal}} → {@code {code, discount}} or a calm 400; {@code
 *       no-store}, own strict {@code rl:pub-coupon} bucket
 *   <li>{@code POST /api/public/{orgSlug}/checkout} — anonymous checkout (B5); {@code no-store}
 * </ul>
 *
 * <p>Checkout lives here rather than in a separate servlet because {@code {orgSlug}} sits between
 * fixed path segments, which a servlet URL mapping (prefix/exact/extension only) cannot target —
 * the {@code /api/public/*} front door is the only mount that catches {@code
 * /api/public/{slug}/checkout}.
 */
public class PublicStorefrontServlet extends HttpServlet {

  private static final String CACHE_LISTINGS = "public, max-age=60";
  private static final String CACHE_PROFILE = "public, max-age=300";
  private static final String CACHE_AVAILABILITY = "public, max-age=15";
  // A whole hour: the point of the stable og-image route is a URL a social crawler's cache can hold
  // well past the ~900s presign TTL of the bytes it streams (slice C2, epic §6).
  private static final String CACHE_OG_IMAGE = "public, max-age=3600";
  private static final String CACHE_NONE = "no-store";
  // Text pages change rarely — the same slow cadence as the profile; the list/detail split (this
  // list carries no bodies) is why the footer renders links without fetching page bodies (slice
  // C4).
  private static final String CACHE_PAGES = "public, max-age=300";
  private static final int MAX_AVAILABILITY_SLUGS = 100;

  private StorefrontService service;
  private CouponService couponService;
  private StorefrontPageService pageService;
  private CustomerAuthService customerAuthService;
  private ListingReviewService reviewService;
  private ListingCommentService commentService;
  private ObjectMapper mapper;
  private boolean secureCookies;
  private int customerRefreshMaxAge;
  private java.util.Set<String> allowedOrigins;

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.service = config.storefrontService;
    this.couponService = config.couponService;
    this.pageService = config.storefrontPageService;
    this.customerAuthService = config.customerAuthService;
    this.reviewService = config.listingReviewService;
    this.commentService = config.listingCommentService;
    this.mapper = config.objectMapper;
    this.secureCookies = config.secureCookies;
    this.customerRefreshMaxAge = config.customerRefreshMaxAgeSeconds;
    this.allowedOrigins = config.corsAllowedOrigins;
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      String[] parts = splitPath(req.getPathInfo());
      String method = req.getMethod();

      if ("POST".equals(method)) {
        // Writes: anonymous checkout, and the customer-portal OTP bootstrap (request/verify-code).
        if (parts.length == 2 && "checkout".equals(parts[1])) {
          doCheckout(req, resp, parts[0]);
        } else if (parts.length == 3 && "coupons".equals(parts[1]) && "validate".equals(parts[2])) {
          doValidateCoupon(req, resp, parts[0]);
        } else if (parts.length == 3 && "portal".equals(parts[1])) {
          doPortalBootstrap(req, resp, parts[0], parts[2]);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }

      if (!"GET".equals(method)) {
        writeError(resp, 405, "Method not allowed");
        return;
      }

      if (parts.length == 0) {
        throw new ValidationException("Expected /api/public/{orgSlug}/...");
      }
      String orgSlug = parts[0];

      // Bare /{orgSlug} → storefront profile (B1).
      if (parts.length == 1) {
        writeJson(
            resp, 200, StorefrontProfileResponse.from(service.profile(orgSlug)), CACHE_PROFILE);
        return;
      }

      String resource = parts[1];
      switch (resource) {
        case "listings" -> doListings(req, resp, orgSlug, parts);
        case "categories" -> {
          if (parts.length != 2) {
            throw new ValidationException("Unknown route");
          }
          // L3: ?locale= resolves each nav name server-side (unknown → 400); the locale is part of
          // the URL, so the max-age=60 cache is naturally per-(org,locale).
          List<PublicCategoryResponse> data =
              service.listCategories(orgSlug, req.getParameter("locale")).stream()
                  .map(PublicCategoryResponse::from)
                  .toList();
          writeJson(resp, 200, data, CACHE_LISTINGS);
        }
        case "collections" -> {
          if (parts.length != 2) {
            throw new ValidationException("Unknown route");
          }
          // Roadmap item 8: the rail read. ?locale= resolves each name server-side (unknown → 400
          // in the service); the locale is part of the URL, so the profile-tier max-age cache is
          // naturally per-(org, locale). Collections change at merchandising cadence, not catalog
          // cadence, hence CACHE_PROFILE rather than CACHE_LISTINGS.
          List<PublicCollectionResponse> data =
              service.collections(orgSlug, req.getParameter("locale")).stream()
                  .map(PublicCollectionResponse::from)
                  .toList();
          writeJson(resp, 200, data, CACHE_PROFILE);
        }
        case "availability" -> doAvailability(req, resp, orgSlug, parts);
        case "pages" -> doPages(req, resp, orgSlug, parts);
        case "banners" -> {
          if (parts.length != 2) {
            throw new ValidationException("Unknown route");
          }
          // L4: ?locale= resolves each banner's headline/subheading server-side (unknown → 400);
          // the
          // locale is part of the URL, so the max-age cache is naturally per-(org,locale).
          List<PublicBannerResponse> data =
              service.banners(orgSlug, req.getParameter("locale")).stream()
                  .map(PublicBannerResponse::from)
                  .toList();
          writeJson(resp, 200, data, CACHE_LISTINGS);
        }
        case "og-image" -> {
          if (parts.length != 2) {
            throw new ValidationException("Unknown route");
          }
          StorefrontService.OgImage img = service.ogImage(orgSlug);
          writeBytes(resp, img.bytes(), img.contentType(), CACHE_OG_IMAGE);
        }
        default -> throw new ValidationException("Unknown resource: " + resource);
      }
    } catch (StorefrontService.StorefrontOutOfStockException e) {
      // Slug-keyed 409 — never a product_id (no-leak invariant).
      writeJson(resp, 409, PublicCheckoutError.of(e.getMessage(), e.shortages()), CACHE_NONE);
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      writeError(resp, 500, "Internal server error");
    }
  }

  /**
   * Pull every {@code attr_{slug}=v1,v2} query parameter into {@code slug → [values]}. Splitting on
   * commas is all that happens here; the caps, blank checks and canonicalization are the service's
   * (one place, so the 400s read the same however the read is reached). Parameter names are the
   * grammar, so a repeated {@code attr_size} folds its values together rather than one winning.
   */
  private static java.util.Map<String, List<String>> attributeFilters(HttpServletRequest req) {
    java.util.Map<String, List<String>> out = new java.util.LinkedHashMap<>();
    java.util.Enumeration<String> names = req.getParameterNames();
    if (names == null) {
      return out;
    }
    while (names.hasMoreElements()) {
      String name = names.nextElement();
      if (name == null || !name.startsWith(ATTR_PARAM_PREFIX)) {
        continue;
      }
      String attribute = name.substring(ATTR_PARAM_PREFIX.length());
      String[] raws = req.getParameterValues(name);
      if (raws == null) {
        continue;
      }
      List<String> values = new java.util.ArrayList<>();
      for (String raw : raws) {
        if (raw != null) {
          java.util.Collections.addAll(values, raw.split(",", -1));
        }
      }
      out.computeIfAbsent(attribute, k -> new java.util.ArrayList<>()).addAll(values);
    }
    return out;
  }

  /** The facet-filter parameter prefix — {@code ?attr_size=m,l}. */
  private static final String ATTR_PARAM_PREFIX = "attr_";

  private void doListings(
      HttpServletRequest req, HttpServletResponse resp, String orgSlug, String[] parts)
      throws IOException {
    if (parts.length == 2) {
      int page = intParam(req, "page", 0);
      int size = intParam(req, "size", 20);
      String category = req.getParameter("category");
      // B3 (storefront_search_and_filters.md): free-text q, inclusive EGP price band, sort — all
      // optional, parsed/validated in the service (unknown sort / bad price → 400 there). C3 adds
      // ?featured=true (the merchant's curated pinned list; bad value → 400 there too).
      // L2: ?locale= resolves each listing's title/marketing_copy server-side (unknown → 400 in the
      // service). The locale is part of the URL, so the max-age=60 cache is naturally
      // per-(org,locale).
      // Roadmap item 4 (storefront_best_sellers.md): ?sort=best_selling ranks by units actually
      // sold, and ?sold=true narrows to listings that sold at all in the window (the home strip's
      // collapse-when-empty guard). Both forwarded as-is; validated in the service.
      // Roadmap item 8 (storefront_collections.md): ?collection={slug} narrows to one merchant
      // collection's curated membership and (absent an explicit ?sort=) orders by the curated
      // position. An unknown slug is the empty result, not a 404 — a renamed shelf must not turn
      // every stale bookmark into an error wall.
      // Roadmap item 7 (storefront_attribute_facets.md): every `attr_{slug}=v1,v2` param is one
      // multi-select filter (OR within, AND across, ANDed with everything above); `include_facets`
      // opts into the counts. Both validated in the service — malformed shape → 400 naming the
      // parameter, unknown slug → the empty set (a stale bookmark must not be an error wall).
      ListingPage p =
          service.listPublished(
              orgSlug,
              category,
              req.getParameter("collection"),
              req.getParameter("q"),
              req.getParameter("min_price"),
              req.getParameter("max_price"),
              req.getParameter("sort"),
              req.getParameter("featured"),
              req.getParameter("sold"),
              req.getParameter("locale"),
              attributeFilters(req),
              req.getParameter("include_facets"),
              page,
              size);
      List<PublicListingResponse> data =
          p.items().stream().map(PublicListingResponse::from).toList();
      writeJson(
          resp,
          200,
          PublicListingsPageResponse.of(data, p.total(), p.page(), p.size(), p.facets()),
          CACHE_LISTINGS);
    } else if (parts.length == 3) {
      writeJson(
          resp,
          200,
          PublicListingResponse.from(
              service.getListing(orgSlug, parts[2], req.getParameter("locale"))),
          CACHE_LISTINGS);
    } else if (parts.length == 4 && "reviews".equals(parts[3])) {
      // Slice R1: the listing's APPROVED reviews. The slug resolves through the same
      // PUBLISHED-only resolution as the listing read (inside the service), so reviews on a
      // DRAFT/ARCHIVED listing are unreachable by construction.
      int page = intParam(req, "page", 0);
      int size = intParam(req, "size", ListingReviewService.DEFAULT_PAGE_SIZE);
      ListingReviewService.PublicPage p = reviewService.publicPage(orgSlug, parts[2], page, size);
      List<PublicReviewResponse> data = p.items().stream().map(PublicReviewResponse::from).toList();
      writeJson(resp, 200, new PageResponse<>(data, p.total(), p.page(), p.size()), CACHE_LISTINGS);
    } else if (parts.length == 4 && "comments".equals(parts[3])) {
      // Slice R2: the listing's ANSWERED Q&A pairs — same PUBLISHED-only resolution as reviews.
      int page = intParam(req, "page", 0);
      int size = intParam(req, "size", ListingCommentService.DEFAULT_PAGE_SIZE);
      ListingCommentService.PublicPage p = commentService.publicPage(orgSlug, parts[2], page, size);
      List<PublicCommentResponse> data =
          p.items().stream().map(PublicCommentResponse::from).toList();
      writeJson(resp, 200, new PageResponse<>(data, p.total(), p.page(), p.size()), CACHE_LISTINGS);
    } else {
      throw new ValidationException("Unknown route");
    }
  }

  private void doPages(
      HttpServletRequest req, HttpServletResponse resp, String orgSlug, String[] parts)
      throws IOException {
    if (parts.length == 2) {
      // The footer's link source: which kinds exist, with updated_at — no bodies.
      List<PublicPageSummaryResponse> data =
          pageService.publicList(orgSlug).stream().map(PublicPageSummaryResponse::from).toList();
      writeJson(resp, 200, data, CACHE_PAGES);
    } else if (parts.length == 3) {
      // One page, body resolved to ?locale= (L4). Unknown kind → 400, never-written → 404, unknown
      // locale → 400 (service-decided). The locale is in the URL, so CACHE_PAGES is
      // per-(org,locale).
      writeJson(
          resp,
          200,
          PublicPageResponse.from(
              pageService.publicPage(orgSlug, parts[2], req.getParameter("locale"))),
          CACHE_PAGES);
    } else {
      throw new ValidationException("Unknown route");
    }
  }

  private void doAvailability(
      HttpServletRequest req, HttpServletResponse resp, String orgSlug, String[] parts)
      throws IOException {
    if (parts.length != 2) {
      throw new ValidationException("Unknown route");
    }
    String raw = req.getParameter("slugs");
    if (raw == null || raw.isBlank()) {
      throw new ValidationException("slugs query parameter is required");
    }
    List<String> slugs = new ArrayList<>();
    for (String s : raw.split(",")) {
      String t = s.trim();
      if (!t.isEmpty()) {
        slugs.add(t);
      }
    }
    if (slugs.isEmpty()) {
      throw new ValidationException("slugs query parameter is required");
    }
    if (slugs.size() > MAX_AVAILABILITY_SLUGS) {
      throw new ValidationException("too many slugs (max " + MAX_AVAILABILITY_SLUGS + ")");
    }
    List<PublicAvailabilityResponse> data =
        service.availability(orgSlug, slugs).stream()
            .map(PublicAvailabilityResponse::from)
            .toList();
    writeJson(resp, 200, data, CACHE_AVAILABILITY);
  }

  private void doCheckout(HttpServletRequest req, HttpServletResponse resp, String orgSlug)
      throws IOException {
    // Idempotency-Key is REQUIRED — a missing/blank header is a 400 before any work.
    String idempotencyKey = req.getHeader("Idempotency-Key");
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ValidationException("Idempotency-Key header is required");
    }

    PublicCheckoutRequest body = readBody(req, PublicCheckoutRequest.class);
    PublicCheckoutRequest.CustomerBody c = body.getCustomer();
    SalesOrderService.CustomerInput customer =
        c == null
            ? new SalesOrderService.CustomerInput(null, null, null, null)
            : new SalesOrderService.CustomerInput(
                c.getName(), c.getEmail(), c.getPhone(), c.getAddress());
    List<CheckoutLine> lines = new ArrayList<>();
    if (body.getLines() != null) {
      for (PublicCheckoutRequest.LineBody l : body.getLines()) {
        lines.add(
            new CheckoutLine(
                l == null ? null : l.getListingSlug(),
                l == null ? null : l.getVariant(),
                l == null ? 0 : l.getQuantity()));
      }
    }
    CheckoutInput input =
        new CheckoutInput(customer, lines, body.getNotes(), body.getLocale(), body.getCoupon());

    CheckoutResult result = service.checkout(orgSlug, input, idempotencyKey.trim());
    // 201 on a fresh order; 200 when a duplicate Idempotency-Key replayed the prior order.
    writeJson(resp, result.created() ? 201 : 200, PublicOrderResponse.from(result), CACHE_NONE);
  }

  /**
   * The anonymous coupon preview (roadmap item 9): what would this code take off a basket of this
   * subtotal? <b>Advisory</b> — it takes no lock and reserves nothing, so the answer can go stale;
   * placement re-validates inside its transaction and is the authority. {@code no-store}, because a
   * cached discount is a wrong discount, and its own strict rate bucket, because a code endpoint is
   * an enumeration surface.
   *
   * <p>No {@code Idempotency-Key} is required: this writes nothing.
   */
  private void doValidateCoupon(HttpServletRequest req, HttpServletResponse resp, String orgSlug)
      throws IOException {
    ValidateCouponRequest body = readBody(req, ValidateCouponRequest.class);
    if (body.getSubtotal() == null || body.getSubtotal().signum() < 0) {
      throw new ValidationException("subtotal is required and must be >= 0");
    }
    // Org resolution goes through the same opaque 404 as every other public read.
    java.util.UUID orgId = service.profileOrgId(orgSlug);
    CouponService.Applied applied =
        couponService.preview(orgId, body.getCode(), body.getSubtotal());
    writeJson(resp, 200, PublicCouponResponse.from(applied), CACHE_NONE);
  }

  /**
   * The anonymous OTP bootstrap for the customer portal (epic §"Anon bootstrap"): {@code
   * request-code} (enumeration-uniform send) and {@code verify-code} (sets the portal cookies). Org
   * is resolved from {@code {orgSlug}} inside {@link CustomerAuthService}. Runs on the
   * JWT-bypassed, rate-limited {@code /api/public/*} front door, but still enforces the portal CSRF
   * guard (custom header + Origin allowlist on these mutations).
   */
  private void doPortalBootstrap(
      HttpServletRequest req, HttpServletResponse resp, String orgSlug, String action)
      throws IOException {
    if (!PortalCsrf.hasHeader(req)) {
      throw new ValidationException("Missing " + PortalCsrf.HEADER + " header");
    }
    if (!PortalCsrf.originAllowed(req, allowedOrigins)) {
      throw new AuthorizationException("Origin not allowed");
    }
    switch (action) {
      case "request-code" -> {
        PortalRequestCodeRequest body = readBody(req, PortalRequestCodeRequest.class);
        // Always the identical response — sent only if (org, email) maps to a real customer.
        customerAuthService.requestCode(orgSlug, body.getEmail());
        writeJson(resp, 200, java.util.Map.of("sent", true), CACHE_NONE);
      }
      case "verify-code" -> {
        PortalVerifyCodeRequest body = readBody(req, PortalVerifyCodeRequest.class);
        CustomerAuthService.SessionResult result =
            customerAuthService.verifyCode(
                orgSlug,
                body.getEmail(),
                body.getCode(),
                req.getHeader("User-Agent"),
                req.getRemoteAddr());
        CustomerAuthCookies.writeAccess(
            resp, result.accessToken(), (int) result.expiresIn(), secureCookies);
        CustomerAuthCookies.writeRefresh(
            resp, result.refreshToken(), customerRefreshMaxAge, secureCookies);
        // The UI-only hint rides every session write and dies with the session (epic §11).
        CustomerAuthCookies.writeHint(resp, customerRefreshMaxAge, secureCookies);
        writeJson(resp, 200, PortalMeResponse.from(result.customer()), CACHE_NONE);
      }
      default -> writeError(resp, 404, "Unknown portal endpoint");
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

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    try {
      return mapper.readValue(req.getInputStream(), type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ValidationException("request body is required and must be valid JSON");
    }
  }

  private void writeJson(HttpServletResponse resp, int status, Object body, String cacheControl)
      throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    if (cacheControl != null) {
      resp.setHeader("Cache-Control", cacheControl);
    }
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeBytes(
      HttpServletResponse resp, byte[] body, String contentType, String cacheControl)
      throws IOException {
    resp.setStatus(200);
    resp.setContentType(contentType == null ? "application/octet-stream" : contentType);
    resp.setContentLength(body.length);
    if (cacheControl != null) {
      resp.setHeader("Cache-Control", cacheControl);
    }
    resp.getOutputStream().write(body);
  }

  private void writeError(HttpServletResponse resp, AppException e) throws IOException {
    writeJson(resp, e.getStatusCode(), ApiError.of(e.getStatusCode(), e.getMessage()), null);
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message), null);
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
