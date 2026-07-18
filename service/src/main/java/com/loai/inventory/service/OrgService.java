package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OrgService {
  private static final Logger log = LoggerFactory.getLogger(OrgService.class);
  private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$");

  private final DSLContext rootDsl;
  private final OrgRepositoryFactory orgRepoFactory;
  private final UserRepositoryFactory userRepoFactory;
  private final ObjectStorage storage;

  public OrgService(
      DSLContext rootDsl,
      OrgRepositoryFactory orgRepoFactory,
      UserRepositoryFactory userRepoFactory,
      ObjectStorage storage) {
    this.rootDsl = rootDsl;
    this.orgRepoFactory = orgRepoFactory;
    this.userRepoFactory = userRepoFactory;
    this.storage = storage;
  }

  /** A presigned logo upload: a PUT URL + the object key to attach afterwards. */
  public record LogoPresign(String uploadUrl, String objectKey, long expiresInSeconds) {}

  /**
   * Hand out a presigned PUT URL + an org-scoped object key for a storefront logo upload ({@code
   * stories/storefront_org_profile.md}). No column is written here — the client uploads the bytes
   * to the URL, then sets {@code logo_object_key} via {@code PUT /api/orgs/{orgId}} (which enforces
   * the same org key-prefix). Mirrors the listing-image presign machinery.
   */
  public LogoPresign presignLogoUpload(UUID orgId, String filename, String contentType) {
    if (filename == null || filename.isBlank()) {
      throw new ValidationException("filename is required");
    }
    orgRepoFactory
        .create(rootDsl)
        .findById(orgId)
        .orElseThrow(() -> new NotFoundException("Org", orgId));
    String objectKey = storage.newLogoKey(orgId, filename);
    String url = storage.presignPut(objectKey, contentType);
    return new LogoPresign(url, objectKey, storage.presignTtlSeconds());
  }

  /**
   * Hand out a presigned PUT URL + an org-scoped object key for a storefront og-image upload (slice
   * C2, {@code stories/storefront_seo_metadata.md}). No column is written here — the client uploads
   * the bytes, then sets {@code og_image_object_key} via {@code PUT /api/orgs/{orgId}} (which
   * enforces the same {@code {orgId}/og/} prefix). Reuses the logo presign machinery with an
   * og-scoped key prefix.
   */
  public LogoPresign presignOgImageUpload(UUID orgId, String filename, String contentType) {
    if (filename == null || filename.isBlank()) {
      throw new ValidationException("filename is required");
    }
    orgRepoFactory
        .create(rootDsl)
        .findById(orgId)
        .orElseThrow(() -> new NotFoundException("Org", orgId));
    String objectKey = storage.newOgImageKey(orgId, filename);
    String url = storage.presignPut(objectKey, contentType);
    return new LogoPresign(url, objectKey, storage.presignTtlSeconds());
  }

  /**
   * The org's current logo as a short-lived presigned GET URL, or {@code null} when none is set —
   * the admin-plane read (the public storefront profile presigns its own copy). See {@code
   * stories/storefront_org_profile.md}.
   */
  public String logoUrl(UUID orgId) {
    String key = getById(orgId).getLogoObjectKey();
    return key == null || key.isBlank() ? null : storage.presignGet(key);
  }

  public Org getById(UUID id) {
    return orgRepoFactory
        .create(rootDsl)
        .findById(id)
        .orElseThrow(() -> new NotFoundException("Org", id));
  }

  public Org getBySlug(String slug) {
    return orgRepoFactory
        .create(rootDsl)
        .findBySlug(slug)
        .orElseThrow(() -> new NotFoundException("Org with slug=" + slug));
  }

  /** Returns orgs the caller has any role in. */
  public List<Org> listForUser(List<UUID> orgIds) {
    if (orgIds == null || orgIds.isEmpty()) return List.of();
    return orgRepoFactory.create(rootDsl).findAllByIds(orgIds);
  }

  /** Atomically creates an org and assigns the caller as OWNER. */
  public Org create(String name, String slug, UUID ownerUserId) {
    validateName(name);
    validateSlug(slug);
    if (ownerUserId == null) {
      throw new ValidationException("ownerUserId is required");
    }

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          OrgRepository orgRepo = orgRepoFactory.create(txDsl);
          UserRepository userRepo = userRepoFactory.create(txDsl);

          if (orgRepo.existsBySlug(slug)) {
            throw new ConflictException("Org slug already exists: " + slug);
          }

          Org org = new Org();
          org.setName(Text.normalizeText(name));
          org.setSlug(slug);
          org.setActive(true);

          Org saved = orgRepo.insert(org);
          userRepo.insertOrgRole(ownerUserId, saved.getId(), OrgRole.OWNER);

          log.info("Created org id={} slug={} owner={}", saved.getId(), slug, ownerUserId);
          return saved;
        });
  }

  public Org update(UUID id, String name) {
    return update(id, name, null, null, null);
  }

  public Org update(UUID id, String name, java.math.BigDecimal refundApprovalThreshold) {
    return update(id, name, refundApprovalThreshold, null, null);
  }

  public Org update(
      UUID id, String name, java.math.BigDecimal refundApprovalThreshold, Integer orderTtlMinutes) {
    return update(id, name, refundApprovalThreshold, orderTtlMinutes, null);
  }

  public Org update(
      UUID id,
      String name,
      java.math.BigDecimal refundApprovalThreshold,
      Integer orderTtlMinutes,
      BillingProfile profile) {
    return update(id, name, refundApprovalThreshold, orderTtlMinutes, profile, null);
  }

  /**
   * Per-org storefront branding (V52) — the public identity the storefront + checkout confirmation
   * render. Every field is optional; a {@code null} field leaves the stored value unchanged (merge,
   * like {@link BillingProfile}), a blank string clears the nullable ones. {@code defaultLocale} is
   * NOT NULL at the DB, so a blank/null on it leaves the current value (never cleared to null). See
   * {@code stories/storefront_org_profile.md} (B1).
   */
  public record StorefrontBranding(
      String themeColor, String instapayHandle, String paymentInstructions, String defaultLocale) {}

  /**
   * Storefront SEO & social metadata (V54, slice C2) — the merchant-authored text a shared store
   * link unfurls with, plus the og-image object key. Merge semantics like {@link
   * StorefrontBranding}: a {@code null} field leaves the stored value unchanged, a blank string
   * clears it. Length caps ({@link #MAX_META_TITLE}/{@link #MAX_META_DESCRIPTION}) are generous
   * over the frontend's ~60/~160 display-truncation guidance — the server rule, not the display
   * guide. See {@code stories/storefront_seo_metadata.md} (C2).
   */
  public record SeoMetadata(String metaTitle, String metaDescription, String ogImageObjectKey) {}

  /**
   * Server-side length caps for the SEO text fields (generous over frontend display truncation).
   */
  public static final int MAX_META_TITLE = 70;

  public static final int MAX_META_DESCRIPTION = 200;

  private static final Pattern THEME_COLOR_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");

  /**
   * The org's billing profile — the seller identity a printable invoice / credit-note / receipt
   * header renders (V51). Every field is optional; a {@code null} field on an incoming profile
   * leaves the stored value unchanged (merge semantics, like the policy knobs), while a blank
   * string clears it. See {@code stories/document_pdf_rendering.md} (Part A).
   */
  public record BillingProfile(
      String legalName,
      String taxRegistrationNumber,
      String addressLine1,
      String addressLine2,
      String city,
      String country,
      String phone,
      String contactEmail,
      String logoObjectKey) {}

  /** Bounds of {@code org.order_ttl_minutes}, mirroring the V47 CHECK (15 min … 30 days). */
  public static final int MIN_ORDER_TTL_MINUTES = 15;

  public static final int MAX_ORDER_TTL_MINUTES = 43_200;

  /**
   * Update an org's name and, optionally, its business-policy knobs: {@code
   * refundApprovalThreshold} (the per-org boundary above which returning money requires an OWNER)
   * and {@code orderTtlMinutes} (the payment-hold window stamped on reserved online/phone orders —
   * {@code reservation.md} §Default TTL, "Configurable per-org"). A null leaves the current value
   * unchanged.
   */
  public Org update(
      UUID id,
      String name,
      java.math.BigDecimal refundApprovalThreshold,
      Integer orderTtlMinutes,
      BillingProfile profile,
      StorefrontBranding branding) {
    return update(id, name, refundApprovalThreshold, orderTtlMinutes, profile, branding, null);
  }

  public Org update(
      UUID id,
      String name,
      java.math.BigDecimal refundApprovalThreshold,
      Integer orderTtlMinutes,
      BillingProfile profile,
      StorefrontBranding branding,
      SeoMetadata seo) {
    validateName(name);
    validatePolicy(refundApprovalThreshold, orderTtlMinutes);
    validateBillingProfile(profile);
    validateBranding(branding);
    validateSeoMetadata(seo);
    validateLogoKeyOwnership(id, profile);
    validateOgImageKeyOwnership(id, seo);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          OrgRepository orgRepo = orgRepoFactory.create(txDsl);

          Org existing = orgRepo.findById(id).orElseThrow(() -> new NotFoundException("Org", id));
          existing.setName(Text.normalizeText(name));
          if (refundApprovalThreshold != null) {
            existing.setRefundApprovalThreshold(refundApprovalThreshold);
          }
          if (orderTtlMinutes != null) {
            existing.setOrderTtlMinutes(orderTtlMinutes);
          }
          applyBillingProfile(existing, profile);
          applyBranding(existing, branding);
          applySeoMetadata(existing, seo);

          Org updated = orgRepo.update(existing);
          log.info("Updated org id={}", id);
          return updated;
        });
  }

  /**
   * Merge storefront branding onto the org: a {@code null} field leaves the stored value unchanged;
   * a non-null field is normalized and applied (blank → null for the nullable fields; {@code
   * defaultLocale} is left unchanged on blank since its column is NOT NULL). A {@code null}
   * branding is a no-op.
   */
  public static void applyBranding(Org org, StorefrontBranding b) {
    if (b == null) {
      return;
    }
    if (b.themeColor() != null) org.setThemeColor(blankToNull(b.themeColor()));
    if (b.instapayHandle() != null) org.setInstapayHandle(Text.normalizeText(b.instapayHandle()));
    if (b.paymentInstructions() != null) {
      org.setPaymentInstructions(Text.normalizeText(b.paymentInstructions()));
    }
    if (b.defaultLocale() != null && !b.defaultLocale().isBlank()) {
      org.setDefaultLocale(b.defaultLocale().trim().toLowerCase());
    }
  }

  /**
   * Branding validation: {@code theme_color} must be a {@code #RRGGBB} hex when present (400
   * otherwise); {@code default_locale} must be one of {@code ar}/{@code en} when present. A null
   * branding or a null/blank field passes.
   */
  public static void validateBranding(StorefrontBranding b) {
    if (b == null) {
      return;
    }
    String theme = blankToNull(b.themeColor());
    if (theme != null && !THEME_COLOR_PATTERN.matcher(theme).matches()) {
      throw new ValidationException("theme_color must be a #RRGGBB hex colour");
    }
    checkLen("instapay_handle", b.instapayHandle(), 255);
    if (b.defaultLocale() != null && !b.defaultLocale().isBlank()) {
      String loc = b.defaultLocale().trim().toLowerCase();
      if (!loc.equals("ar") && !loc.equals("en")) {
        throw new ValidationException("default_locale must be 'ar' or 'en'");
      }
    }
  }

  /**
   * Merge SEO metadata onto the org (slice C2): a {@code null} field leaves the stored value
   * unchanged; a non-null field is normalized (trimmed, blank → null) and applied — so a blank
   * string clears it. A {@code null} seo is a no-op.
   */
  public static void applySeoMetadata(Org org, SeoMetadata s) {
    if (s == null) {
      return;
    }
    if (s.metaTitle() != null) org.setMetaTitle(Text.normalizeText(s.metaTitle()));
    if (s.metaDescription() != null)
      org.setMetaDescription(Text.normalizeText(s.metaDescription()));
    if (s.ogImageObjectKey() != null) org.setOgImageObjectKey(blankToNull(s.ogImageObjectKey()));
  }

  /**
   * SEO metadata validation (slice C2): {@code meta_title} ≤ {@value #MAX_META_TITLE} and {@code
   * meta_description} ≤ {@value #MAX_META_DESCRIPTION} chars — cause-naming 400s, generous over the
   * frontend's display-truncation guidance. A null seo or a null/blank field passes.
   */
  public static void validateSeoMetadata(SeoMetadata s) {
    if (s == null) {
      return;
    }
    checkLen("meta_title", s.metaTitle(), MAX_META_TITLE);
    checkLen("meta_description", s.metaDescription(), MAX_META_DESCRIPTION);
  }

  /**
   * When an org update sets a non-blank {@code og_image_object_key}, it must be one we minted for
   * this org — {@code {orgId}/og/…} — blocking a caller from attaching another tenant's (or an
   * arbitrary) object. Mirrors the logo-key attach guard (epic §4). A null/blank key
   * (leave-unchanged or clear) passes.
   */
  public static void validateOgImageKeyOwnership(UUID orgId, SeoMetadata seo) {
    if (seo == null) {
      return;
    }
    String key = blankToNull(seo.ogImageObjectKey());
    if (key != null && !key.startsWith(ObjectStorage.ogImageKeyPrefix(orgId))) {
      throw new ValidationException("og_image_object_key does not belong to this org");
    }
  }

  /**
   * Merge the incoming profile onto the org: a {@code null} field leaves the stored value
   * unchanged; a non-null field is normalized (trimmed, blank → null) and applied — so a blank
   * string clears the field. A {@code null} profile is a no-op (callers that only edit
   * name/policy).
   */
  private static void applyBillingProfile(Org org, BillingProfile p) {
    if (p == null) {
      return;
    }
    if (p.legalName() != null) org.setLegalName(Text.normalizeText(p.legalName()));
    if (p.taxRegistrationNumber() != null)
      org.setTaxRegistrationNumber(Text.normalizeNumeric(p.taxRegistrationNumber()));
    if (p.addressLine1() != null) org.setAddressLine1(Text.normalizeText(p.addressLine1()));
    if (p.addressLine2() != null) org.setAddressLine2(Text.normalizeText(p.addressLine2()));
    if (p.city() != null) org.setCity(Text.normalizeText(p.city()));
    if (p.country() != null) org.setCountry(Text.normalizeText(p.country()));
    if (p.phone() != null) org.setPhone(Text.normalizeNumeric(p.phone()));
    if (p.contactEmail() != null) org.setContactEmail(Text.normalizeEmail(p.contactEmail()));
    if (p.logoObjectKey() != null) org.setLogoObjectKey(blankToNull(p.logoObjectKey()));
  }

  private static String blankToNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

  /**
   * Length caps mirror the V51 columns; {@code contact_email} is shape-checked when present. A null
   * profile or a null/blank field passes (nothing to validate).
   */
  public static void validateBillingProfile(BillingProfile p) {
    if (p == null) {
      return;
    }
    checkLen("legal_name", p.legalName(), 255);
    checkLen("tax_registration_number", p.taxRegistrationNumber(), 64);
    checkLen("address_line1", p.addressLine1(), 255);
    checkLen("address_line2", p.addressLine2(), 255);
    checkLen("city", p.city(), 128);
    checkLen("country", p.country(), 128);
    checkLen("phone", p.phone(), 32);
    checkLen("contact_email", p.contactEmail(), 255);
    checkLen("logo_object_key", p.logoObjectKey(), 512);
    String email = blankToNull(p.contactEmail());
    if (email != null && !EMAIL_PATTERN.matcher(email).matches()) {
      throw new ValidationException("contact_email is not a valid email address");
    }
  }

  /**
   * When an org update sets a non-blank {@code logo_object_key}, it must be one we minted for this
   * org — {@code {orgId}/logo/…} — blocking a caller from attaching another tenant's (or an
   * arbitrary) object. Mirrors the listing-image attach guard. A null/blank key (leave-unchanged or
   * clear) passes.
   */
  private void validateLogoKeyOwnership(UUID orgId, BillingProfile profile) {
    if (profile == null) {
      return;
    }
    String key = blankToNull(profile.logoObjectKey());
    if (key != null && !key.startsWith(ObjectStorage.logoKeyPrefix(orgId))) {
      throw new ValidationException("logo_object_key does not belong to this org");
    }
  }

  private static void checkLen(String field, String value, int max) {
    if (value != null && value.trim().length() > max) {
      throw new ValidationException(field + " must be <= " + max + " chars");
    }
  }

  public void delete(UUID id) {
    rootDsl.transaction(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          OrgRepository orgRepo = orgRepoFactory.create(txDsl);
          orgRepo.deleteById(id);
          log.info("Deleted org id={}", id);
        });
  }

  /**
   * Shared business-policy validation (refund-approval threshold ≥ 0, order-TTL within bounds),
   * reused by the platform-admin edit path. A {@code null} leaves the knob unchanged, so it passes.
   */
  public static void validatePolicy(
      java.math.BigDecimal refundApprovalThreshold, Integer orderTtlMinutes) {
    if (refundApprovalThreshold != null && refundApprovalThreshold.signum() < 0) {
      throw new ValidationException("refund_approval_threshold must be >= 0");
    }
    if (orderTtlMinutes != null
        && (orderTtlMinutes < MIN_ORDER_TTL_MINUTES || orderTtlMinutes > MAX_ORDER_TTL_MINUTES)) {
      throw new ValidationException(
          "order_ttl_minutes must be between "
              + MIN_ORDER_TTL_MINUTES
              + " and "
              + MAX_ORDER_TTL_MINUTES
              + " (15 minutes to 30 days)");
    }
  }

  /** Shared name validation, reused by the platform-admin provisioning/edit paths. */
  public static void validateName(String name) {
    if (name == null || name.isBlank()) {
      throw new ValidationException("name is required");
    }
    if (name.length() > 255) {
      throw new ValidationException("name must be <= 255 chars");
    }
  }

  /** Shared slug validation, reused by the platform-admin provisioning path. */
  public static void validateSlug(String slug) {
    if (slug == null || slug.isBlank()) {
      throw new ValidationException("slug is required");
    }
    if (!SLUG_PATTERN.matcher(slug).matches()) {
      throw new ValidationException(
          "slug must be 3-64 chars, lowercase alphanumeric or hyphen, no leading/trailing hyphen");
    }
  }
}
