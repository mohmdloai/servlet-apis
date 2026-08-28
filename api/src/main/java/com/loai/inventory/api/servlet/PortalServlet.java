package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.NotificationPreferenceResponse;
import com.loai.inventory.api.dto.NotificationPreferencesRequest;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.PaymentClaimRequest;
import com.loai.inventory.api.dto.PaymentClaimResponse;
import com.loai.inventory.api.dto.PaymentProofPresignRequest;
import com.loai.inventory.api.dto.PaymentProofPresignResponse;
import com.loai.inventory.api.dto.PortalAddressRequest;
import com.loai.inventory.api.dto.PortalAddressResponse;
import com.loai.inventory.api.dto.PortalCheckoutRequest;
import com.loai.inventory.api.dto.PortalCommentRequest;
import com.loai.inventory.api.dto.PortalCommentResponse;
import com.loai.inventory.api.dto.PortalInvoiceResponse;
import com.loai.inventory.api.dto.PortalInvoiceSummaryResponse;
import com.loai.inventory.api.dto.PortalMeResponse;
import com.loai.inventory.api.dto.PortalNotificationResponse;
import com.loai.inventory.api.dto.PortalProfileUpdateRequest;
import com.loai.inventory.api.dto.PortalReorderResponse;
import com.loai.inventory.api.dto.PortalReviewRequest;
import com.loai.inventory.api.dto.PortalReviewResponse;
import com.loai.inventory.api.dto.PortalSessionResponse;
import com.loai.inventory.api.dto.PortalWishlistRequest;
import com.loai.inventory.api.dto.PublicCheckoutError;
import com.loai.inventory.api.dto.PublicListingResponse;
import com.loai.inventory.api.dto.PublicOrderResponse;
import com.loai.inventory.api.filter.CustomerAuthFilter;
import com.loai.inventory.api.util.ClientIp;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.AuthenticationException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.domain.model.CustomerAddress;
import com.loai.inventory.domain.model.CustomerPrincipal;
import com.loai.inventory.domain.model.InAppFeedItem;
import com.loai.inventory.domain.model.NotificationChannel;
import com.loai.inventory.domain.model.NotificationPreference;
import com.loai.inventory.service.CustomerPortalService;
import com.loai.inventory.service.ListingCommentService;
import com.loai.inventory.service.ListingReviewService;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.WishlistService;
import com.loai.inventory.service.auth.CustomerAuthService;
import com.loai.inventory.service.document.DocumentRenderService;
import com.loai.inventory.service.document.DocumentRenderService.RenderedDocument;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The authenticated customer-portal API at {@code /api/portal/*} (behind {@link CustomerAuthFilter}
 * — CSRF + customer session). Endpoints:
 *
 * <ul>
 *   <li>{@code POST /auth/refresh} — rotate the session off the {@code customer_refresh} cookie
 *   <li>{@code POST /auth/logout} — kill this device; {@code POST /auth/logout-all} — every device
 *   <li>{@code GET /auth/sessions} — this customer's active devices; {@code DELETE
 *       /auth/sessions/{familyId}} — sign one of them out
 *   <li>{@code GET|PATCH /me} — read / merge-update the caller's own profile
 *   <li>{@code GET /orders} — the caller's own orders, newest first, paged (slice P2)
 *   <li>{@code GET /orders/{orderNumber}} — one owned order (customer-safe); opaque 404 otherwise
 *   <li>{@code GET /invoices} — the caller's own live invoices, newest first, paged (slice P3)
 *   <li>{@code GET /invoices/{id}} — one owned invoice + lines (customer-safe); opaque 404
 *       otherwise
 *   <li>{@code GET /invoices/{id}/pdf} — the rendered A4 invoice PDF for an owned invoice
 *   <li>{@code GET|POST /addresses} — list / add a saved address (slice P4)
 *   <li>{@code PATCH|DELETE /addresses/{id}} — edit / remove an owned address
 *   <li>{@code POST /addresses/{id}/default} — promote an owned address to the default
 *   <li>{@code POST /orders/{orderNumber}/reorder} — resolve a past order into a buyable cart
 *   <li>{@code POST /checkout} — place an order as the logged-in customer (slice P6): bound to the
 *       session, delivery from an owned {@code address_id} or a typed address; {@code
 *       Idempotency-Key} required; 409 slug-keyed shortages; {@code track_url} → the portal order
 *       page
 *   <li>{@code GET /notifications} — the caller's own in-app feed, newest first, paged ({@code
 *       ?unread=true} narrows); {@code GET /notifications/unread-count} — the badge (slice P5)
 *   <li>{@code POST /notifications/{id}/read} · {@code /{id}/dismiss} — mark the caller's own entry
 *       (idempotent; foreign/unknown id → opaque 404)
 *   <li>{@code GET|PUT /notification-preferences} — the caller's own opt-out settings (the same
 *       rows the emailed one-click unsubscribe writes)
 *   <li>{@code GET /wishlist} — the caller's saved listings as storefront cards, newest-saved
 *       first, PUBLISHED-only; {@code POST /wishlist} {@code {listing_slug}} · {@code DELETE
 *       /wishlist/{listingSlug}} — save / unsave, both idempotent 204 (roadmap item 3)
 * </ul>
 *
 * All identity comes from the {@link CustomerPrincipal} the filter published — never from the URL
 * or body (epic decision #4). See {@code stories/portal_auth_core.md}.
 */
public class PortalServlet extends HttpServlet {

  private static final Logger log = LoggerFactory.getLogger(PortalServlet.class);

  private CustomerAuthService authService;
  private CustomerPortalService portalService;
  private PaymentTransactionService paymentTransactionService;
  private ObjectStorage objectStorage;
  private DocumentRenderService renderService;
  private NotificationService notificationService;
  private ListingReviewService reviewService;
  private ListingCommentService commentService;
  private WishlistService wishlistService;
  private ObjectMapper mapper;
  private boolean secureCookies;
  private int refreshMaxAge;

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.authService = config.customerAuthService;
    this.portalService = config.customerPortalService;
    this.paymentTransactionService = config.paymentTransactionService;
    this.objectStorage = config.objectStorage;
    this.renderService = config.documentRenderService;
    this.notificationService = config.notificationService;
    this.reviewService = config.listingReviewService;
    this.commentService = config.listingCommentService;
    this.wishlistService = config.wishlistService;
    this.mapper = config.objectMapper;
    this.secureCookies = config.secureCookies;
    this.refreshMaxAge = config.customerRefreshMaxAgeSeconds;
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
    String method = req.getMethod();
    try {
      if ("/checkout".equals(path)) {
        requirePost(method, () -> handleCheckout(req, resp));
        return;
      }
      if ("/orders".equals(path)) {
        requireGet(method, () -> handleListOrders(req, resp));
        return;
      }
      if (path.startsWith("/orders/")) {
        String rest = path.substring("/orders/".length());
        if (rest.endsWith("/reorder")) {
          String orderNumber = rest.substring(0, rest.length() - "/reorder".length());
          requirePost(method, () -> handleReorder(req, resp, orderNumber));
        } else if (rest.endsWith("/payment-claim")) {
          String orderNumber = rest.substring(0, rest.length() - "/payment-claim".length());
          requirePost(method, () -> handlePaymentClaim(req, resp, orderNumber));
        } else if (rest.endsWith("/payment-proof/presign")) {
          String orderNumber = rest.substring(0, rest.length() - "/payment-proof/presign".length());
          requirePost(method, () -> handleProofPresign(req, resp, orderNumber));
        } else {
          requireGet(method, () -> handleGetOrder(req, resp, rest));
        }
        return;
      }
      if ("/addresses".equals(path)) {
        if ("GET".equals(method)) {
          handleListAddresses(req, resp);
        } else if ("POST".equals(method)) {
          handleCreateAddress(req, resp);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      if (path.startsWith("/addresses/")) {
        String rest = path.substring("/addresses/".length());
        if (rest.endsWith("/default")) {
          String id = rest.substring(0, rest.length() - "/default".length());
          requirePost(method, () -> handleSetDefaultAddress(req, resp, id));
        } else if ("PATCH".equals(method)) {
          handleUpdateAddress(req, resp, rest);
        } else if ("DELETE".equals(method)) {
          handleDeleteAddress(req, resp, rest);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      if ("/reviews".equals(path)) {
        if ("POST".equals(method)) {
          handleSubmitReview(req, resp);
        } else if ("GET".equals(method)) {
          handleMyReviews(req, resp);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      if (path.startsWith("/reviews/")) {
        String rest = path.substring("/reviews/".length());
        if ("DELETE".equals(method)) {
          handleDeleteReview(req, resp, rest);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      if ("/comments".equals(path)) {
        if ("POST".equals(method)) {
          handleSubmitComment(req, resp);
        } else if ("GET".equals(method)) {
          handleMyComments(req, resp);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      if (path.startsWith("/comments/")) {
        String rest = path.substring("/comments/".length());
        if ("DELETE".equals(method)) {
          handleDeleteComment(req, resp, rest);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      if ("/wishlist".equals(path)) {
        if ("GET".equals(method)) {
          handleMyWishlist(req, resp);
        } else if ("POST".equals(method)) {
          handleAddToWishlist(req, resp);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      if (path.startsWith("/wishlist/")) {
        String rest = path.substring("/wishlist/".length());
        if ("DELETE".equals(method)) {
          handleRemoveFromWishlist(req, resp, rest);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      if ("/notifications".equals(path)) {
        requireGet(method, () -> handleListNotifications(req, resp));
        return;
      }
      if ("/notifications/unread-count".equals(path)) {
        requireGet(method, () -> handleUnreadCount(req, resp));
        return;
      }
      if (path.startsWith("/notifications/")) {
        String rest = path.substring("/notifications/".length());
        requirePost(method, () -> handleNotificationAction(req, resp, rest));
        return;
      }
      if ("/notification-preferences".equals(path)) {
        if ("GET".equals(method)) {
          handleGetNotificationPreferences(req, resp);
        } else if ("PUT".equals(method)) {
          handlePutNotificationPreferences(req, resp);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }
      if ("/invoices".equals(path)) {
        requireGet(method, () -> handleListInvoices(req, resp));
        return;
      }
      if (path.startsWith("/invoices/")) {
        String rest = path.substring("/invoices/".length());
        if (rest.endsWith("/pdf")) {
          String id = rest.substring(0, rest.length() - "/pdf".length());
          requireGet(method, () -> handleInvoicePdf(req, resp, id));
        } else {
          requireGet(method, () -> handleGetInvoice(req, resp, rest));
        }
        return;
      }
      if (path.startsWith("/auth/sessions/")) {
        String rest = path.substring("/auth/sessions/".length());
        requireDelete(method, () -> handleRevokeSession(req, resp, rest));
        return;
      }
      switch (path) {
        case "/auth/refresh" -> requirePost(method, () -> handleRefresh(req, resp));
        case "/auth/logout" -> requirePost(method, () -> handleLogout(req, resp));
        case "/auth/logout-all" -> requirePost(method, () -> handleLogoutAll(req, resp));
        case "/auth/sessions" -> requireGet(method, () -> handleSessions(req, resp));
        case "/me" -> {
          if ("GET".equals(method)) {
            handleGetMe(req, resp);
          } else if ("PATCH".equals(method)) {
            handlePatchMe(req, resp);
          } else {
            writeError(resp, 405, "Method not allowed");
          }
        }
        default -> writeError(resp, 404, "Unknown portal endpoint");
      }
    } catch (StorefrontService.StorefrontOutOfStockException e) {
      // Slug-keyed 409 — never a product_id (no-leak invariant), same shape as the anon checkout.
      resp.setHeader("Cache-Control", "private, no-store");
      writeJson(resp, 409, PublicCheckoutError.of(e.getMessage(), e.shortages()));
    } catch (AppException e) {
      writeError(resp, e.getStatusCode(), e.getMessage());
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      log.error("Unhandled exception in PortalServlet", e);
      writeError(resp, 500, "Internal server error");
    }
  }

  // auth

  private void handleRefresh(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String rawRefresh = extractRefreshCookie(req);
    if (rawRefresh == null) {
      throw new AuthenticationException("Missing refresh token");
    }
    CustomerAuthService.SessionResult result =
        authService.refresh(rawRefresh, ClientIp.resolve(req));
    writeCookies(resp, result);
    writeJson(resp, 200, Map.of("expires_in", result.expiresIn()));
  }

  private void handleLogout(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    authService.logout(extractRefreshCookie(req));
    CustomerAuthCookies.clearAll(resp, secureCookies);
    resp.setStatus(204);
  }

  private void handleLogoutAll(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    authService.logoutAll(principal.orgId(), principal.customerId());
    CustomerAuthCookies.clearAll(resp, secureCookies);
    resp.setStatus(204);
  }

  /**
   * {@code DELETE /auth/sessions/{familyId}} — sign one device out (204). The family id comes from
   * the shopper's own {@code GET /auth/sessions} list; ownership is re-checked against the token's
   * {@code (org_id, customer_id)} in the store, so a foreign id is a 404 and never a revoke.
   *
   * <p>Cookies are deliberately left alone: revoking the device you are *currently* on is a
   * legitimate move, and clearing the jar here would sign the caller out of the page they are
   * standing on as a side effect of a row-level action. The denylist already makes that token dead
   * on its next request; {@code /auth/logout} is the verb that means "end this device's session".
   */
  private void handleRevokeSession(HttpServletRequest req, HttpServletResponse resp, String rawId)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    UUID familyId;
    try {
      familyId = UUID.fromString(rawId);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid session id: " + rawId);
    }
    authService.revokeSession(principal.orgId(), principal.customerId(), familyId);
    resp.setStatus(204);
  }

  private void handleSessions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    // customer_refresh is Path=/api/portal/auth, so it rides this request; its family is "this
    // device". Unresolvable → all rows current:false, never a guess (SessionResponse's contract).
    UUID currentFamily = authService.sessionFamilyOf(extractRefreshCookie(req)).orElse(null);
    List<PortalSessionResponse> items =
        authService.listSessions(principal.orgId(), principal.customerId()).stream()
            .map(s -> PortalSessionResponse.from(s, s.familyId().equals(currentFamily)))
            .toList();
    writeJson(resp, 200, items);
  }

  // profile

  private void handleGetMe(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    writeJson(
        resp,
        200,
        PortalMeResponse.from(portalService.me(principal.orgId(), principal.customerId())));
  }

  private void handlePatchMe(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    PortalProfileUpdateRequest body = readBody(req, PortalProfileUpdateRequest.class);
    CustomerPortalService.ProfileUpdate update =
        new CustomerPortalService.ProfileUpdate(body.getName(), body.getPhone(), body.getAddress());
    writeJson(
        resp,
        200,
        PortalMeResponse.from(
            portalService.updateProfile(principal.orgId(), principal.customerId(), update)));
  }

  // orders (slice P2)

  private void handleListOrders(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    // Clamp here too so the envelope echoes the page/size actually served.
    int page = Math.max(intParam(req, "page", 0), 0);
    int size =
        Math.min(
            Math.max(intParam(req, "size", CustomerPortalService.DEFAULT_PAGE_SIZE), 1),
            CustomerPortalService.MAX_PAGE_SIZE);
    CustomerPortalService.OrderPage result =
        portalService.listOrders(principal.orgId(), principal.customerId(), page, size);
    List<PublicOrderResponse> data =
        result.items().stream()
            .map(v -> PublicOrderResponse.forOrderView(v.order(), v.lines()))
            .toList();
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(resp, 200, new PageResponse<>(data, result.total(), page, size));
  }

  private void handleGetOrder(HttpServletRequest req, HttpServletResponse resp, String orderNumber)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    // The detail read carries the two additive per-line fields (listing_slug + delivered) that
    // drive the "rate this item" entry (slice R1 rider) — the list rows keep the lean shape.
    CustomerPortalService.OrderDetail detail =
        portalService.getOrderDetail(principal.orgId(), principal.customerId(), orderNumber);
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(
        resp,
        200,
        PublicOrderResponse.forPortalOrderDetail(
            detail, paymentTransactionService.latestClaimFor(detail.order().getId()).orElse(null)));
  }

  // payment-proof claim (roadmap item 2) — the logged-in twin of the guest magic-link route

  private void handlePaymentClaim(
      HttpServletRequest req, HttpServletResponse resp, String orderNumber) throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    // Ownership: getOrder resolves within the session org and asserts the order is the caller's —
    // a foreign/unknown number is the same opaque 404. Yields the internal order id for the claim.
    CustomerPortalService.OrderView owned =
        portalService.getOrder(principal.orgId(), principal.customerId(), orderNumber);
    PaymentClaimRequest body = readBody(req, PaymentClaimRequest.class);
    PaymentTransactionService.ClaimResult result =
        paymentTransactionService.claim(
            principal.orgId(),
            new PaymentTransactionService.ClaimCommand(
                owned.order().getId(),
                principal.customerId(),
                body.getReference(),
                body.getProofObjectKey(),
                body.getNote()));
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(
        resp,
        result.inserted() ? 201 : 200,
        PaymentClaimResponse.from(result.transaction(), result.inserted(), result.reopened()));
  }

  private void handleProofPresign(
      HttpServletRequest req, HttpServletResponse resp, String orderNumber) throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    CustomerPortalService.OrderView owned =
        portalService.getOrder(principal.orgId(), principal.customerId(), orderNumber);
    PaymentProofPresignRequest body = readBody(req, PaymentProofPresignRequest.class);
    String objectKey =
        objectStorage.newPaymentProofKey(
            principal.orgId(), owned.order().getId(), body.getFilename());
    String uploadUrl = objectStorage.presignPut(objectKey, body.getContentType());
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(
        resp,
        200,
        PaymentProofPresignResponse.of(uploadUrl, objectKey, objectStorage.presignTtlSeconds()));
  }

  // invoices (slice P3)

  private void handleListInvoices(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    // Clamp here too so the envelope echoes the page/size actually served.
    int page = Math.max(intParam(req, "page", 0), 0);
    int size =
        Math.min(
            Math.max(intParam(req, "size", CustomerPortalService.DEFAULT_PAGE_SIZE), 1),
            CustomerPortalService.MAX_PAGE_SIZE);
    CustomerPortalService.InvoicePage result =
        portalService.listInvoices(principal.orgId(), principal.customerId(), page, size);
    List<PortalInvoiceSummaryResponse> data =
        result.items().stream().map(PortalInvoiceSummaryResponse::from).toList();
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(resp, 200, new PageResponse<>(data, result.total(), page, size));
  }

  private void handleGetInvoice(HttpServletRequest req, HttpServletResponse resp, String idRaw)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    CustomerPortalService.InvoiceDetail detail =
        portalService.getInvoice(principal.orgId(), principal.customerId(), parseInvoiceId(idRaw));
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(resp, 200, PortalInvoiceResponse.from(detail.invoice(), detail.lines()));
  }

  private void handleInvoicePdf(HttpServletRequest req, HttpServletResponse resp, String idRaw)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    UUID invoiceId = parseInvoiceId(idRaw);
    // Ownership + live gate (opaque 404 for foreign/unknown/voided) before we render anything.
    portalService.getInvoice(principal.orgId(), principal.customerId(), invoiceId);
    RenderedDocument doc = renderService.renderInvoice(principal.orgId(), invoiceId);
    writePdf(resp, doc.bytes(), doc.filename());
  }

  private UUID parseInvoiceId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid invoice id: " + raw);
    }
  }

  // saved addresses (slice P4)

  private void handleListAddresses(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    List<PortalAddressResponse> data =
        portalService.listAddresses(principal.orgId(), principal.customerId()).stream()
            .map(PortalAddressResponse::from)
            .toList();
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(resp, 200, data);
  }

  private void handleCreateAddress(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    CustomerAddress created =
        portalService.createAddress(
            principal.orgId(),
            principal.customerId(),
            toAddressInput(readBody(req, PortalAddressRequest.class)));
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(resp, 201, PortalAddressResponse.from(created));
  }

  private void handleUpdateAddress(HttpServletRequest req, HttpServletResponse resp, String idRaw)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    CustomerAddress updated =
        portalService.updateAddress(
            principal.orgId(),
            principal.customerId(),
            parseAddressId(idRaw),
            toAddressInput(readBody(req, PortalAddressRequest.class)));
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(resp, 200, PortalAddressResponse.from(updated));
  }

  private void handleDeleteAddress(HttpServletRequest req, HttpServletResponse resp, String idRaw)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    portalService.deleteAddress(principal.orgId(), principal.customerId(), parseAddressId(idRaw));
    resp.setStatus(204);
  }

  private void handleSetDefaultAddress(
      HttpServletRequest req, HttpServletResponse resp, String idRaw) throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    CustomerAddress promoted =
        portalService.setDefaultAddress(
            principal.orgId(), principal.customerId(), parseAddressId(idRaw));
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(resp, 200, PortalAddressResponse.from(promoted));
  }

  private static CustomerPortalService.AddressInput toAddressInput(PortalAddressRequest body) {
    return new CustomerPortalService.AddressInput(
        body.getLabel(),
        body.getRecipient(),
        body.getPhone(),
        body.getAddress(),
        Boolean.TRUE.equals(body.getIsDefault()));
  }

  private UUID parseAddressId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid address id: " + raw);
    }
  }

  // reorder (slice P4)

  private void handleReorder(HttpServletRequest req, HttpServletResponse resp, String orderNumber)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    CustomerPortalService.ReorderResult result =
        portalService.reorder(principal.orgId(), principal.customerId(), orderNumber);
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(resp, 200, PortalReorderResponse.from(result));
  }

  // reviews (slice R1)

  private void handleSubmitReview(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    PortalReviewRequest body = readBody(req, PortalReviewRequest.class);
    ListingReviewService.Submitted result =
        reviewService.submit(
            principal.orgId(),
            principal.customerId(),
            body.getListingSlug(),
            body.getRating(),
            body.getBody());
    resp.setHeader("Cache-Control", "private, no-store");
    // 201 on a fresh review; 200 when the (customer, listing) upsert replayed as an edit.
    writeJson(
        resp,
        result.created() ? 201 : 200,
        PortalReviewResponse.from(result.review(), result.listingSlug(), null));
  }

  private void handleMyReviews(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    List<PortalReviewResponse> data =
        reviewService.myReviews(principal.orgId(), principal.customerId()).stream()
            .map(m -> PortalReviewResponse.from(m.review(), m.listingSlug(), m.listingTitle()))
            .toList();
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(resp, 200, data);
  }

  private void handleDeleteReview(HttpServletRequest req, HttpServletResponse resp, String idRaw)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    UUID reviewId;
    try {
      reviewId = UUID.fromString(idRaw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid review id: " + idRaw);
    }
    reviewService.deleteOwn(principal.orgId(), principal.customerId(), reviewId);
    resp.setStatus(204);
  }

  // comments (slice R2)

  // wishlist (roadmap item 3)

  private void handleMyWishlist(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    List<PublicListingResponse> data =
        wishlistService
            .list(principal.orgId(), principal.customerId(), req.getParameter("locale"))
            .stream()
            .map(PublicListingResponse::from)
            .toList();
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(resp, 200, data);
  }

  private void handleAddToWishlist(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    PortalWishlistRequest body = readBody(req, PortalWishlistRequest.class);
    wishlistService.add(principal.orgId(), principal.customerId(), body.getListingSlug());
    resp.setHeader("Cache-Control", "private, no-store");
    // 204 on a fresh save AND on a replay — the login-merge re-posts the guest's whole list, so
    // idempotence beats the ceremony of distinguishing 201 from 200 for a heart.
    resp.setStatus(204);
  }

  private void handleRemoveFromWishlist(
      HttpServletRequest req, HttpServletResponse resp, String listingSlug) throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    wishlistService.remove(principal.orgId(), principal.customerId(), listingSlug);
    resp.setHeader("Cache-Control", "private, no-store");
    resp.setStatus(204);
  }

  private void handleSubmitComment(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    PortalCommentRequest body = readBody(req, PortalCommentRequest.class);
    ListingCommentService.Submitted result =
        commentService.submit(
            principal.orgId(), principal.customerId(), body.getListingSlug(), body.getBody());
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(
        resp,
        201,
        PortalCommentResponse.from(result.comment(), result.listingSlug(), result.listingTitle()));
  }

  private void handleMyComments(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    List<PortalCommentResponse> data =
        commentService.myComments(principal.orgId(), principal.customerId()).stream()
            .map(m -> PortalCommentResponse.from(m.comment(), m.listingSlug(), m.listingTitle()))
            .toList();
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(resp, 200, data);
  }

  private void handleDeleteComment(HttpServletRequest req, HttpServletResponse resp, String idRaw)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    UUID commentId;
    try {
      commentId = UUID.fromString(idRaw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid comment id: " + idRaw);
    }
    commentService.deleteOwn(principal.orgId(), principal.customerId(), commentId);
    resp.setStatus(204);
  }

  // notifications (slice P5)

  private void handleListNotifications(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    // Clamp here too so the envelope echoes the page/size actually served.
    int page = Math.max(intParam(req, "page", 0), 0);
    int size =
        Math.min(
            Math.max(intParam(req, "size", CustomerPortalService.DEFAULT_PAGE_SIZE), 1),
            CustomerPortalService.MAX_PAGE_SIZE);
    boolean unreadOnly = "true".equalsIgnoreCase(req.getParameter("unread"));
    List<InAppFeedItem> feed =
        notificationService.getCustomerFeed(
            principal.orgId(), principal.customerId(), unreadOnly, page, size);
    long total =
        notificationService.countCustomerFeed(
            principal.orgId(), principal.customerId(), unreadOnly);
    List<PortalNotificationResponse> data =
        feed.stream()
            .map(
                i ->
                    PortalNotificationResponse.from(
                        i,
                        extractPayloadString(i, "order_number"),
                        extractPayloadString(i, "listing_slug")))
            .toList();
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(resp, 200, new PageResponse<>(data, total, page, size));
  }

  private void handleUnreadCount(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    long count =
        notificationService.countCustomerFeed(
            principal.orgId(), principal.customerId(), /* unreadOnly= */ true);
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(resp, 200, Map.of("count", count));
  }

  private void handleNotificationAction(
      HttpServletRequest req, HttpServletResponse resp, String rest) throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    String[] parts = rest.split("/");
    if (parts.length != 2) {
      throw new ValidationException("Expected /notifications/{id}/read or /{id}/dismiss");
    }
    UUID notificationId;
    try {
      notificationId = UUID.fromString(parts[0]);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid notification id: " + parts[0]);
    }
    switch (parts[1]) {
      case "read" ->
          notificationService.markCustomerRead(
              principal.orgId(), principal.customerId(), notificationId);
      case "dismiss" ->
          notificationService.markCustomerDismissed(
              principal.orgId(), principal.customerId(), notificationId);
      default -> throw new ValidationException("Unknown action: " + parts[1]);
    }
    resp.setStatus(204);
  }

  private void handleGetNotificationPreferences(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    writePreferences(
        resp,
        notificationService.getCustomerPreferences(principal.orgId(), principal.customerId()));
  }

  private void handlePutNotificationPreferences(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    NotificationPreferencesRequest body = readBody(req, NotificationPreferencesRequest.class);
    if (body == null || body.getPreferences() == null || body.getPreferences().isEmpty()) {
      throw new ValidationException("preferences must not be empty");
    }
    List<NotificationService.PreferenceInput> inputs =
        new java.util.ArrayList<>(body.getPreferences().size());
    for (NotificationPreferencesRequest.Item item : body.getPreferences()) {
      if (item == null || item.getEnabled() == null) {
        throw new ValidationException("each preference needs type, channel, enabled");
      }
      inputs.add(
          new NotificationService.PreferenceInput(
              item.getType(), parseChannel(item.getChannel()), item.getEnabled()));
    }
    writePreferences(
        resp,
        notificationService.setCustomerPreferences(
            principal.orgId(), principal.customerId(), inputs));
  }

  private void writePreferences(HttpServletResponse resp, List<NotificationPreference> prefs)
      throws IOException {
    List<NotificationPreferenceResponse> data =
        prefs.stream().map(NotificationPreferenceResponse::from).toList();
    resp.setHeader("Cache-Control", "private, no-store");
    writeJson(resp, 200, Map.of("preferences", data));
  }

  private static NotificationChannel parseChannel(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ValidationException("channel is required");
    }
    try {
      return NotificationChannel.fromDbValue(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("unknown channel: " + raw);
    }
  }

  /**
   * A textual deep-link handle from the notification payload ({@code order_number} on order events,
   * {@code listing_slug} on {@code COMMENT_REPLIED} — slice R2). Null when absent — the row just
   * renders without a deep-link.
   */
  private String extractPayloadString(InAppFeedItem item, String key) {
    String payload = item.notification().getPayloadJson();
    if (payload == null) {
      return null;
    }
    try {
      com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(payload).get(key);
      return node != null && node.isTextual() ? node.asText() : null;
    } catch (IOException e) {
      return null;
    }
  }

  // checkout (slice P6)

  private void handleCheckout(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    // Idempotency-Key is REQUIRED — a missing/blank header is a 400 before any work (as anon).
    String idempotencyKey = req.getHeader("Idempotency-Key");
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ValidationException("Idempotency-Key header is required");
    }
    PortalCheckoutRequest body = readBody(req, PortalCheckoutRequest.class);
    List<CustomerPortalService.CheckoutLine> lines = new java.util.ArrayList<>();
    if (body.getLines() != null) {
      for (PortalCheckoutRequest.LineBody l : body.getLines()) {
        lines.add(
            new CustomerPortalService.CheckoutLine(
                l == null ? null : l.getListingSlug(),
                l == null ? null : l.getVariant(),
                l == null ? 0 : l.getQuantity()));
      }
    }
    CustomerPortalService.CheckoutInput input =
        new CustomerPortalService.CheckoutInput(
            lines,
            body.getNotes(),
            body.getAddressId(),
            body.getAddress() == null ? null : toAddressInput(body.getAddress()),
            Boolean.TRUE.equals(body.getSaveAddress()),
            body.getLocale(),
            body.getCoupon());
    StorefrontService.CheckoutResult result =
        portalService.checkout(
            principal.orgId(), principal.customerId(), input, idempotencyKey.trim());
    resp.setHeader("Cache-Control", "private, no-store");
    // 201 on a fresh order; 200 when a duplicate Idempotency-Key replayed the prior order.
    writeJson(resp, result.created() ? 201 : 200, PublicOrderResponse.from(result));
  }

  // helpers

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

  private void writeCookies(HttpServletResponse resp, CustomerAuthService.SessionResult result) {
    CustomerAuthCookies.writeAccess(
        resp, result.accessToken(), (int) result.expiresIn(), secureCookies);
    CustomerAuthCookies.writeRefresh(resp, result.refreshToken(), refreshMaxAge, secureCookies);
    // The UI-only hint rides every session write and dies with the session (epic §11).
    CustomerAuthCookies.writeHint(resp, refreshMaxAge, secureCookies);
  }

  private CustomerPrincipal requirePrincipal(HttpServletRequest req) {
    CustomerPrincipal principal =
        (CustomerPrincipal) req.getAttribute(CustomerAuthFilter.PRINCIPAL_ATTR);
    if (principal == null) {
      throw new AuthenticationException("Authentication required");
    }
    return principal;
  }

  private String extractRefreshCookie(HttpServletRequest req) {
    Cookie[] cookies = req.getCookies();
    if (cookies != null) {
      for (Cookie c : cookies) {
        if (CustomerAuthCookies.REFRESH_COOKIE.equals(c.getName())) {
          return c.getValue();
        }
      }
    }
    return null;
  }

  private interface Handler {
    void run() throws IOException;
  }

  private void requirePost(String method, Handler h) throws IOException {
    if (!"POST".equals(method)) {
      throw new ValidationException("Method not allowed");
    }
    h.run();
  }

  private void requireGet(String method, Handler h) throws IOException {
    if (!"GET".equals(method)) {
      throw new ValidationException("Method not allowed");
    }
    h.run();
  }

  private void requireDelete(String method, Handler h) throws IOException {
    if (!"DELETE".equals(method)) {
      throw new ValidationException("Method not allowed");
    }
    h.run();
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

  private void writePdf(HttpServletResponse resp, byte[] bytes, String filename)
      throws IOException {
    resp.setStatus(200);
    resp.setContentType("application/pdf");
    resp.setContentLength(bytes.length);
    // A finance document is per-customer and must never be cached by a shared proxy.
    resp.setHeader("Cache-Control", "private, no-store");
    resp.setHeader("Content-Disposition", "inline; filename=\"" + filename + "\"");
    resp.getOutputStream().write(bytes);
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }
}
