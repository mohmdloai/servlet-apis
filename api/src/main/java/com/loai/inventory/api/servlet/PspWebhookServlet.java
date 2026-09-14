package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.service.PaymobWebhookService;
import com.loai.inventory.service.PaymobWebhookService.Outcome;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inbound PSP callbacks at {@code /api/psp/*} — anonymous, JWT-bypassed, on the wide {@code
 * rl:psp-webhook} bucket. The HMAC is the capability ({@code stories/paymob_card_checkout.md}).
 *
 * <ul>
 *   <li>{@code POST /paymob/{orgId}/webhook?hmac=…} — Paymob's transaction callback for one org.
 * </ul>
 *
 * <p>The response code is a control signal for Paymob's retry loop, not a status report: {@code
 * 400} for anything unverifiable (a forgery must not be retried), {@code 200} for every verified
 * delivery whether it settled, replayed, or was deliberately ignored, and {@code 500} only for a
 * transient failure — the one case retries exist for.
 */
public class PspWebhookServlet extends HttpServlet {

  private static final Logger log = LoggerFactory.getLogger(PspWebhookServlet.class);

  /** Paymob's transaction callback is a few KB; anything past this is not one. */
  static final int MAX_BODY_BYTES = 256 * 1024;

  private PaymobWebhookService paymobWebhookService;
  private ObjectMapper mapper;

  /** No-arg constructor for the servlet container; collaborators are read in {@link #init}. */
  public PspWebhookServlet() {}

  /** Test constructor: inject the collaborators directly (bypasses {@link #init}). */
  PspWebhookServlet(PaymobWebhookService paymobWebhookService, ObjectMapper mapper) {
    this.paymobWebhookService = paymobWebhookService;
    this.mapper = mapper;
  }

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.paymobWebhookService = config.paymobWebhookService;
    this.mapper = config.objectMapper;
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String[] parts = splitPath(req.getPathInfo());
    // /paymob/{orgId}/webhook — the only route.
    if (parts.length != 3 || !"paymob".equals(parts[0]) || !"webhook".equals(parts[2])) {
      writeError(resp, 404, "Not found");
      return;
    }
    if (!"POST".equals(req.getMethod())) {
      writeError(resp, 405, "Method not allowed");
      return;
    }
    UUID orgId;
    try {
      orgId = UUID.fromString(parts[1]);
    } catch (IllegalArgumentException e) {
      writeError(resp, 400, "malformed org id");
      return;
    }

    String body;
    try {
      body = readBody(req);
    } catch (IllegalArgumentException e) {
      writeError(resp, 400, e.getMessage());
      return;
    }
    // From the query string, never the body: a JSON POST has no form parameters to parse, and
    // reading it as one would consume the stream the signature is computed over.
    String hmac = queryParam(req.getQueryString(), "hmac");

    Outcome outcome;
    try {
      outcome = paymobWebhookService.handle(orgId, body, hmac);
    } catch (RuntimeException e) {
      // Transient (DB down, lock timeout, missing credential key): let Paymob retry.
      log.error("Paymob webhook for org {} failed; answering 500 so it is retried", orgId, e);
      writeError(resp, 500, "Internal server error");
      return;
    }
    if (outcome.rejected()) {
      writeError(resp, 400, outcome.detail());
      return;
    }
    writeJson(resp, 200, Map.of("status", outcome.kind().name(), "detail", outcome.detail()));
  }

  private static String readBody(HttpServletRequest req) throws IOException {
    try (InputStream in = req.getInputStream()) {
      byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
      if (bytes.length > MAX_BODY_BYTES) {
        throw new IllegalArgumentException("body too large");
      }
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  /** The first value of {@code name} in a raw query string, URL-decoded; null when absent. */
  static String queryParam(String query, String name) {
    if (query == null || query.isBlank()) {
      return null;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      String key = eq < 0 ? pair : pair.substring(0, eq);
      if (name.equals(URLDecoder.decode(key, StandardCharsets.UTF_8))) {
        return eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  /** Split {@code /a/b/c} into {@code [a,b,c]}; missing/empty → {@code []}. */
  private static String[] splitPath(String pathInfo) {
    if (pathInfo == null || pathInfo.length() < 2) {
      return new String[0];
    }
    String raw = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    if (raw.endsWith("/")) {
      raw = raw.substring(0, raw.length() - 1);
    }
    if (raw.isBlank()) {
      return new String[0];
    }
    return raw.split("/");
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    resp.setHeader("Cache-Control", "no-store");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }
}
