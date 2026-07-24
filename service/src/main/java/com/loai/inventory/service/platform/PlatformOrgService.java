package com.loai.inventory.service.platform;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.security.PasswordHasher;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.AppUserTokenPurpose;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.OrgHealth;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.PlatformAuditEvent;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.repository.OrgHealthRepository;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import com.loai.inventory.service.OrgService;
import com.loai.inventory.service.auth.AuthMailer;
import com.loai.inventory.service.auth.CredentialTokenService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Platform-tier org operations: the cross-org read console (slice 2) and org lifecycle
 * (suspend/reactivate, slice 5). A platform ADMIN/SUPPORT can list every org and inspect one with
 * an operational rollup, holding no org role of their own; a platform ADMIN can additionally toggle
 * an org's suspension. Suspension only bites once {@code AuthzHelper.requireOrgAccess} enforces it
 * - so a toggle writes the org-active flag through the {@link OrgStatusService} cache immediately.
 */
public class PlatformOrgService {

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  private final DSLContext dsl;
  private final OrgRepositoryFactory orgRepoFactory;
  private final UserRepositoryFactory userRepoFactory;
  private final OrgHealthRepository orgHealthRepo;
  private final PlatformAuditService audit;
  private final OrgStatusService orgStatus;
  private final CredentialTokenService credentialTokenService;
  private final AuthMailer authMailer;

  public PlatformOrgService(
      DSLContext dsl,
      OrgRepositoryFactory orgRepoFactory,
      UserRepositoryFactory userRepoFactory,
      OrgHealthRepository orgHealthRepo,
      PlatformAuditService audit,
      OrgStatusService orgStatus,
      CredentialTokenService credentialTokenService,
      AuthMailer authMailer) {
    this.dsl = dsl;
    this.orgRepoFactory = orgRepoFactory;
    this.userRepoFactory = userRepoFactory;
    this.orgHealthRepo = orgHealthRepo;
    this.audit = audit;
    this.orgStatus = orgStatus;
    this.credentialTokenService = credentialTokenService;
    this.authMailer = authMailer;
  }

  /** One org plus its distinct member count, for the list view. */
  public record OrgListItem(Org org, long memberCount) {}

  /** A page of orgs with the total count for pagination. */
  public record OrgPage(List<OrgListItem> items, long total, int page, int size) {}

  /** One org plus its full operational rollup, for the drill-down view. */
  public record OrgWithHealth(Org org, OrgHealth health) {}

  /**
   * The outcome of provisioning a client org: the org, its OWNER, and whether that owner account
   * was minted fresh (an unusable password awaiting a reset/invite) or attached from an existing
   * user by email.
   */
  public record ProvisionResult(Org org, AppUser owner, boolean ownerMinted) {}

  /**
   * Paged org list. {@code active} filters by status ({@code null} = all). {@code page} is 0-based;
   * {@code size} is clamped to {@code [1, MAX_PAGE_SIZE]}.
   */
  public OrgPage list(int page, int size, Boolean active) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int offset = safeOffset(p, s);

    OrgRepository orgRepo = orgRepoFactory.create(dsl);
    List<Org> orgs = orgRepo.findAll(offset, s, active);
    long total = orgRepo.count(active);

    Map<UUID, Long> memberCounts =
        orgHealthRepo.memberCounts(orgs.stream().map(Org::getId).toList());
    List<OrgListItem> items =
        orgs.stream()
            .map(o -> new OrgListItem(o, memberCounts.getOrDefault(o.getId(), 0L)))
            .toList();
    return new OrgPage(items, total, p, s);
  }

  /** One org plus its health rollup. 404 if the org does not exist. */
  public OrgWithHealth getWithHealth(UUID orgId) {
    OrgRepository orgRepo = orgRepoFactory.create(dsl);
    Org org = orgRepo.findById(orgId).orElseThrow(() -> new NotFoundException("Org", orgId));
    return new OrgWithHealth(org, orgHealthRepo.health(orgId));
  }

  /**
   * Provision a client org with a first OWNER, atomically (see {@code
   * stories/11_st_platform_admin_console.md}, PG1). Closes the gap that org creation existed only
   * on the self-serve {@code POST /api/orgs} path (caller-becomes-OWNER), which an operator
   * onboarding a client cannot use. In one transaction: resolve {@code ownerEmail} to a user -
   * minting a fresh USER account with an unusable password (awaiting a reset/invite) when the email
   * is new - create the org, grant that user OWNER, and audit. A minted owner is also audited as
   * {@code USER_CREATE}.
   */
  public ProvisionResult provision(
      SecurityContext actor, Environment env, String name, String slug, String ownerEmail) {
    String normalizedName = Text.normalizeText(name);
    OrgService.validateName(normalizedName);
    OrgService.validateSlug(slug);
    if (ownerEmail == null || ownerEmail.isBlank()) {
      throw new ValidationException("owner_email is required");
    }
    String email = Text.normalizeEmail(ownerEmail);
    OffsetDateTime now = OffsetDateTime.now();
    // A minted owner's INVITE token, captured from the txn so we can email it after commit.
    String[] inviteToken = new String[1];

    ProvisionResult result =
        dsl.transactionResult(
            cfg -> {
              DSLContext tx = DSL.using(cfg);
              OrgRepository orgRepo = orgRepoFactory.create(tx);
              UserRepository userRepo = userRepoFactory.create(tx);

              if (orgRepo.existsBySlug(slug)) {
                throw new ConflictException("Org slug already exists: " + slug);
              }

              Optional<AppUser> existing = userRepo.findByEmail(email);
              boolean minted = existing.isEmpty();
              AppUser owner;
              if (minted) {
                AppUser toMint =
                    new AppUser(
                        null,
                        email,
                        PasswordHasher.hash("!" + UUID.randomUUID()), // unusable until invite
                        ActorType.USER,
                        true,
                        0,
                        null,
                        null);
                // Born verified (story 88): provisioning is an admin act, and activating the
                // invite link re-proves the inbox. Never 403-block a provisioned owner.
                toMint.setEmailVerifiedAt(now);
                owner = userRepo.insert(toMint);
              } else {
                owner = existing.get();
                if (owner.getActorType() != ActorType.USER) {
                  throw new ValidationException("owner_email must belong to a USER account");
                }
              }

              Org org = new Org();
              org.setName(normalizedName);
              org.setSlug(slug);
              org.setActive(true);
              Org saved = orgRepo.insert(org);
              userRepo.insertOrgRole(owner.getId(), saved.getId(), OrgRole.OWNER);

              if (minted) {
                audit.recordInTx(
                    tx,
                    actor,
                    env,
                    "USER_CREATE",
                    PlatformAuditEvent.Target.USER,
                    owner.getId(),
                    Map.of("email", email, "via", "org_provision"));
                // Mint the first-password invite in the same txn — a rolled-back provision leaves
                // no orphan token.
                inviteToken[0] =
                    credentialTokenService.mint(tx, owner.getId(), AppUserTokenPurpose.INVITE, now);
              }
              audit.recordInTx(
                  tx,
                  actor,
                  env,
                  "ORG_CREATE",
                  PlatformAuditEvent.Target.ORG,
                  saved.getId(),
                  Map.of(
                      "slug", slug, "owner_id", owner.getId().toString(), "owner_minted", minted));
              return new ProvisionResult(saved, owner, minted);
            });

    // Best-effort invite email, after commit (a mint owner has an unusable password until they set
    // one via this link). Delivery failure never fails the provision.
    if (result.ownerMinted() && inviteToken[0] != null) {
      authMailer.sendInvite(email, name, credentialTokenService.activateUrl(inviteToken[0]));
    }
    return result;
  }

  /**
   * Edit an org's name and business-policy knobs on the admin plane, audited (divergence #3: this
   * used to be reachable only by tunnelling through the OWNER route {@code PUT /api/orgs/{orgId}}
   * via the {@code isSystemAdmin()} bypass, which SUPPORT can't use and which left no platform
   * trail). A {@code null} policy value leaves that knob unchanged. Validation mirrors {@link
   * OrgService}.
   */
  public Org updateOrg(
      SecurityContext actor,
      Environment env,
      UUID orgId,
      String name,
      BigDecimal refundApprovalThreshold,
      Integer orderTtlMinutes) {
    return updateOrg(actor, env, orgId, name, refundApprovalThreshold, orderTtlMinutes, null);
  }

  public Org updateOrg(
      SecurityContext actor,
      Environment env,
      UUID orgId,
      String name,
      BigDecimal refundApprovalThreshold,
      Integer orderTtlMinutes,
      OrgService.StorefrontBranding branding) {
    return updateOrg(
        actor, env, orgId, name, refundApprovalThreshold, orderTtlMinutes, branding, null);
  }

  public Org updateOrg(
      SecurityContext actor,
      Environment env,
      UUID orgId,
      String name,
      BigDecimal refundApprovalThreshold,
      Integer orderTtlMinutes,
      OrgService.StorefrontBranding branding,
      OrgService.SeoMetadata seo) {
    return updateOrg(
        actor, env, orgId, name, refundApprovalThreshold, orderTtlMinutes, branding, seo, null);
  }

  public Org updateOrg(
      SecurityContext actor,
      Environment env,
      UUID orgId,
      String name,
      BigDecimal refundApprovalThreshold,
      Integer orderTtlMinutes,
      OrgService.StorefrontBranding branding,
      OrgService.SeoMetadata seo,
      OrgService.StoreConfig storeConfig) {
    OrgService.validateName(name);
    OrgService.validatePolicy(refundApprovalThreshold, orderTtlMinutes);
    OrgService.validateBranding(branding);
    OrgService.validateSeoMetadata(seo);
    OrgService.validateStoreConfig(storeConfig);
    OrgService.validateOgImageKeyOwnership(orgId, seo);

    return dsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          OrgRepository orgRepo = orgRepoFactory.create(tx);
          Org existing =
              orgRepo.findById(orgId).orElseThrow(() -> new NotFoundException("Org", orgId));
          existing.setName(name);
          if (refundApprovalThreshold != null) {
            existing.setRefundApprovalThreshold(refundApprovalThreshold);
          }
          if (orderTtlMinutes != null) {
            existing.setOrderTtlMinutes(orderTtlMinutes);
          }
          OrgService.applyBranding(existing, branding);
          OrgService.applySeoMetadata(existing, seo);
          OrgService.applyStoreConfig(existing, storeConfig);
          Org updated = orgRepo.update(existing);

          Map<String, Object> detail = new LinkedHashMap<>();
          detail.put("name", name);
          if (refundApprovalThreshold != null) {
            detail.put("refund_approval_threshold", refundApprovalThreshold);
          }
          if (orderTtlMinutes != null) {
            detail.put("order_ttl_minutes", orderTtlMinutes);
          }
          if (branding != null) {
            if (branding.themeColor() != null) detail.put("theme_color", branding.themeColor());
            if (branding.instapayHandle() != null) {
              detail.put("instapay_handle", branding.instapayHandle());
            }
            if (branding.paymentInstructions() != null) {
              detail.put("payment_instructions", branding.paymentInstructions());
            }
            if (branding.defaultLocale() != null) {
              detail.put("default_locale", branding.defaultLocale());
            }
          }
          if (seo != null) {
            if (seo.metaTitle() != null) detail.put("meta_title", seo.metaTitle());
            if (seo.metaDescription() != null)
              detail.put("meta_description", seo.metaDescription());
            if (seo.ogImageObjectKey() != null) {
              detail.put("og_image_object_key", seo.ogImageObjectKey());
            }
          }
          if (storeConfig != null) {
            if (storeConfig.taxRate() != null) detail.put("tax_rate", storeConfig.taxRate());
            if (storeConfig.shippingFee() != null) {
              detail.put("shipping_fee", storeConfig.shippingFee());
            }
          }
          audit.recordInTx(
              tx, actor, env, "ORG_UPDATE", PlatformAuditEvent.Target.ORG, orgId, detail);
          return updated;
        });
  }

  /**
   * Suspend an org: members lose access on their next request; the platform bypass is preserved.
   */
  public Org suspend(SecurityContext actor, Environment env, UUID orgId, String reason) {
    Org updated = toggle(actor, env, orgId, true, reason);
    orgStatus.invalidate(orgId); // next hot-path read load-through's the committed suspension
    return updated;
  }

  /** Reactivate a suspended org. */
  public Org reactivate(SecurityContext actor, Environment env, UUID orgId) {
    Org updated = toggle(actor, env, orgId, false, null);
    orgStatus.invalidate(orgId);
    return updated;
  }

  /**
   * {@code page * size} as an offset, computed in long and clamped to {@code Integer.MAX_VALUE} so
   * a huge page number can never overflow into a negative OFFSET (which Postgres rejects). A page
   * past the end simply returns no rows.
   */
  static int safeOffset(int page, int size) {
    long offset = (long) page * size;
    return offset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) offset;
  }

  private Org toggle(
      SecurityContext actor, Environment env, UUID orgId, boolean suspended, String reason) {
    return dsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          Org org = orgRepoFactory.create(tx).setSuspension(orgId, suspended, reason);
          audit.recordInTx(
              tx,
              actor,
              env,
              suspended ? "ORG_SUSPEND" : "ORG_REACTIVATE",
              PlatformAuditEvent.Target.ORG,
              orgId,
              reason == null ? Map.of() : Map.of("reason", reason));
          return org;
        });
  }
}
