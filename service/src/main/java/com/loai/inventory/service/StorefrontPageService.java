package com.loai.inventory.service;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.PageKind;
import com.loai.inventory.domain.model.StorefrontPage;
import com.loai.inventory.domain.model.StorefrontPageTranslation;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.StorefrontPageRepository;
import com.loai.inventory.domain.repository.StorefrontPageRepositoryFactory;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The storefront text-page resource (customization epic slice C4) — both the admin-plane editor
 * (upsert/delete/list of {@code about}/{@code policies}) and the anonymous public reads (list of
 * existing kinds, one page by kind). Unlike banners (whose public read joins the catalog and so
 * lives on {@link StorefrontService}), a page is self-contained — no cross-entity resolution — so
 * one service owns both sides.
 *
 * <p>Write-time rules: the {@code kind} must be in the closed {@link PageKind} set (else a 400 —
 * the value isn't the contract); a body in the org's default locale is required (epic §1, so an
 * AR-default org may ship AR-only copy); each body is at most {@value #MAX_BODY_CHARS} chars (a
 * policies page, not a novel). Bodies are stored verbatim — no HTML stripping, no interpretation
 * (inert by construction, epic §Out); the frontend's one rendering rule keeps them inert on the way
 * out. See {@code stories/storefront_pages.md}.
 */
public class StorefrontPageService {

  private static final Logger log = LoggerFactory.getLogger(StorefrontPageService.class);

  /** Explicit body cap (epic §Out — plain text, not long-form) — exceeding it is a 400. */
  static final int MAX_BODY_CHARS = 20_000;

  private final DSLContext rootDsl;
  private final StorefrontPageRepositoryFactory pageRepoFactory;
  private final OrgRepositoryFactory orgRepoFactory;

  public StorefrontPageService(
      DSLContext rootDsl,
      StorefrontPageRepositoryFactory pageRepoFactory,
      OrgRepositoryFactory orgRepoFactory) {
    this.rootDsl = rootDsl;
    this.pageRepoFactory = pageRepoFactory;
    this.orgRepoFactory = orgRepoFactory;
  }

  /**
   * The mutable content of a page: both bodies, carried whole each PUT (default-locale required).
   */
  public record PageInput(String bodyAr, String bodyEn) {}

  // admin reads/writes (org-scoped by id, VIEWER read / STAFF write)

  /** Every page the org has written (both bodies + updated_at), {@code kind ASC}. */
  public List<StorefrontPage> list(UUID orgId) {
    return pageRepoFactory.create(rootDsl).findAllByOrg(orgId);
  }

  /**
   * Idempotent create-or-replace of the org's {@code kind} page (PUT). Validates the kind against
   * the closed set, requires a default-locale body, and caps each body length — then writes both
   * bodies whole in one transaction.
   */
  public StorefrontPage upsert(UUID orgId, String rawKind, PageInput in) {
    if (in == null) {
      throw new ValidationException("request body is required");
    }
    PageKind kind = parseKind(rawKind);
    String bodyAr = blankToNull(in.bodyAr());
    String bodyEn = blankToNull(in.bodyEn());

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          String defaultLocale = defaultLocale(orgId, tx);
          validate(defaultLocale, bodyAr, bodyEn);

          StorefrontPage page = new StorefrontPage();
          page.setOrgId(orgId);
          page.setKind(kind);
          page.setBodyAr(bodyAr);
          page.setBodyEn(bodyEn);

          StorefrontPageRepository repo = pageRepoFactory.create(tx);
          StorefrontPage saved = repo.upsert(page);
          // Dual-write per-language rows (slice L4); legacy paired columns stay authoritative until
          // L6. One row per non-null body side (the default-locale row is guaranteed by validate).
          List<StorefrontPageTranslation> rows = new java.util.ArrayList<>(2);
          if (bodyAr != null) {
            rows.add(new StorefrontPageTranslation("ar", bodyAr));
          }
          if (bodyEn != null) {
            rows.add(new StorefrontPageTranslation("en", bodyEn));
          }
          repo.replaceTranslations(saved.getId(), rows);
          log.info("Upserted storefront_page kind={} orgId={}", kind.wire(), orgId);
          return saved;
        });
  }

  /** Remove the org's {@code kind} page. Unknown kind → 400; a never-written page → 404. */
  public void delete(UUID orgId, String rawKind) {
    PageKind kind = parseKind(rawKind);
    rootDsl.transaction(
        cfg -> {
          boolean removed = pageRepoFactory.create(DSL.using(cfg)).deleteByKind(orgId, kind);
          if (!removed) {
            throw new NotFoundException("Page not found: " + kind.wire());
          }
          log.info("Deleted storefront_page kind={} orgId={}", kind.wire(), orgId);
        });
  }

  // public reads (org-scoped by slug, anonymous)

  /**
   * The anonymous footer source: the kinds this org has written, {@code kind ASC} (the DTO keeps
   * only {@code kind} + {@code updated_at}). Unknown/inactive slug → opaque 404 (via {@link
   * #resolveOrg}).
   */
  public List<StorefrontPage> publicList(String orgSlug) {
    UUID orgId = resolveOrg(orgSlug).getId();
    return pageRepoFactory.create(rootDsl).findAllByOrg(orgId);
  }

  /**
   * One page by kind for the anonymous storefront, resolved to a <b>single</b> {@code body} by
   * {@code ?locale=} (slice L4 — reverses the shipped C4 both-bodies-client-resolve; cache keyed
   * per (org, locale)): the requested-locale translation row, else the default-locale row, else the
   * legacy default-locale column — never null. Unknown kind value → 400; unknown/inactive slug →
   * opaque 404; a valid kind the org never wrote → 404; unknown locale → 400.
   */
  public PublicPageView publicPage(String orgSlug, String rawKind, String locale) {
    PageKind kind = parseKind(rawKind);
    Org org = resolveOrg(orgSlug);
    String defaultLocale = defaultLocaleOf(org);
    String resolvedLocale = resolveRequestedLocale(locale, defaultLocale);
    StorefrontPageRepository repo = pageRepoFactory.create(rootDsl);
    StorefrontPage page =
        repo.findByKind(org.getId(), kind)
            .orElseThrow(() -> new NotFoundException("Page not found: " + kind.wire()));
    List<StorefrontPageTranslation> ts = repo.findTranslations(page.getId());
    String legacyDefault = "en".equals(defaultLocale) ? page.getBodyEn() : page.getBodyAr();
    String body = coalesce(bodyOf(ts, resolvedLocale), bodyOf(ts, defaultLocale), legacyDefault);
    return new PublicPageView(kind.wire(), body, page.getUpdatedAt());
  }

  /** A public page resolved to one locale (slice L4). */
  public record PublicPageView(String kind, String body, java.time.OffsetDateTime updatedAt) {}

  private static String bodyOf(List<StorefrontPageTranslation> ts, String lang) {
    if (ts == null) {
      return null;
    }
    for (StorefrontPageTranslation t : ts) {
      if (t.language().equals(lang)) {
        return t.body();
      }
    }
    return null;
  }

  // helpers

  private static PageKind parseKind(String rawKind) {
    try {
      return PageKind.fromWire(rawKind);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("kind must be one of: about, policies");
    }
  }

  /** Default-locale body required + each body within the length cap. */
  private void validate(String defaultLocale, String bodyAr, String bodyEn) {
    String required = "en".equals(defaultLocale) ? bodyEn : bodyAr;
    if (required == null) {
      throw new ValidationException(
          "a body in the org's default locale (" + defaultLocale + ") is required");
    }
    if (bodyAr != null && bodyAr.length() > MAX_BODY_CHARS) {
      throw new ValidationException("body_ar exceeds the " + MAX_BODY_CHARS + "-character limit");
    }
    if (bodyEn != null && bodyEn.length() > MAX_BODY_CHARS) {
      throw new ValidationException("body_en exceeds the " + MAX_BODY_CHARS + "-character limit");
    }
  }

  private String defaultLocale(UUID orgId, DSLContext tx) {
    Org org =
        orgRepoFactory
            .create(tx)
            .findById(orgId)
            .orElseThrow(() -> new NotFoundException("Org", orgId));
    String loc = org.getDefaultLocale();
    return loc == null || loc.isBlank() ? "ar" : loc.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * The BCP-47 languages the storefront serves (mirrors StorefrontService); L4 locale resolution.
   */
  private static final java.util.Set<String> SUPPORTED_LOCALES = java.util.Set.of("ar", "en");

  private static String defaultLocaleOf(Org org) {
    String loc = org.getDefaultLocale();
    return loc == null || loc.isBlank() ? "ar" : loc.trim().toLowerCase(Locale.ROOT);
  }

  private static String resolveRequestedLocale(String requested, String defaultLocale) {
    if (requested == null || requested.isBlank()) {
      return defaultLocale;
    }
    String loc = requested.trim().toLowerCase(Locale.ROOT);
    if (!SUPPORTED_LOCALES.contains(loc)) {
      throw new ValidationException("unsupported locale: " + loc);
    }
    return loc;
  }

  private static String coalesce(String a, String b, String c) {
    if (a != null) {
      return a;
    }
    return b != null ? b : c;
  }

  private Org resolveOrg(String orgSlug) {
    return orgRepoFactory
        .create(rootDsl)
        .findBySlug(orgSlug)
        .filter(Org::isActive)
        .orElseThrow(() -> new NotFoundException("Storefront not found: " + orgSlug));
  }

  /**
   * Trim, treating blank as absent — but preserve internal whitespace/newlines verbatim in a
   * non-blank body (the plain-text invariant: line breaks are kept). Only outer padding and the
   * empty-string-clears-the-field case are normalized.
   */
  private static String blankToNull(String s) {
    if (s == null) {
      return null;
    }
    return s.trim().isEmpty() ? null : s;
  }
}
