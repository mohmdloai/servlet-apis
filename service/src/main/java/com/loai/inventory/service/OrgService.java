package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
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

  public OrgService(
      DSLContext rootDsl,
      OrgRepositoryFactory orgRepoFactory,
      UserRepositoryFactory userRepoFactory) {
    this.rootDsl = rootDsl;
    this.orgRepoFactory = orgRepoFactory;
    this.userRepoFactory = userRepoFactory;
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
          org.setName(name);
          org.setSlug(slug);
          org.setActive(true);

          Org saved = orgRepo.insert(org);
          userRepo.insertOrgRole(ownerUserId, saved.getId(), OrgRole.OWNER);

          log.info("Created org id={} slug={} owner={}", saved.getId(), slug, ownerUserId);
          return saved;
        });
  }

  public Org update(UUID id, String name) {
    return update(id, name, null, null);
  }

  public Org update(UUID id, String name, java.math.BigDecimal refundApprovalThreshold) {
    return update(id, name, refundApprovalThreshold, null);
  }

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
      UUID id, String name, java.math.BigDecimal refundApprovalThreshold, Integer orderTtlMinutes) {
    validateName(name);
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

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          OrgRepository orgRepo = orgRepoFactory.create(txDsl);

          Org existing = orgRepo.findById(id).orElseThrow(() -> new NotFoundException("Org", id));
          existing.setName(name);
          if (refundApprovalThreshold != null) {
            existing.setRefundApprovalThreshold(refundApprovalThreshold);
          }
          if (orderTtlMinutes != null) {
            existing.setOrderTtlMinutes(orderTtlMinutes);
          }

          Org updated = orgRepo.update(existing);
          log.info("Updated org id={}", id);
          return updated;
        });
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

  private void validateName(String name) {
    if (name == null || name.isBlank()) {
      throw new ValidationException("name is required");
    }
    if (name.length() > 255) {
      throw new ValidationException("name must be <= 255 chars");
    }
  }

  private void validateSlug(String slug) {
    if (slug == null || slug.isBlank()) {
      throw new ValidationException("slug is required");
    }
    if (!SLUG_PATTERN.matcher(slug).matches()) {
      throw new ValidationException(
          "slug must be 3-64 chars, lowercase alphanumeric or hyphen, no leading/trailing hyphen");
    }
  }
}
