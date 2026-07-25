package com.loai.inventory.service;

import com.loai.inventory.common.Pagination;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.Coupon;
import com.loai.inventory.domain.model.CouponType;
import com.loai.inventory.domain.repository.CouponRepository;
import com.loai.inventory.domain.repository.CouponRepositoryFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Honest coupon codes — roadmap item 9, {@code stories/honest_coupons.md}. A merchant-issued code
 * that reduces the <b>real</b> total, shown transparently at checkout. It fills the {@code
 * discount_total} field roadmap item 5 deliberately left at zero.
 *
 * <p>The honesty posture is a hard constraint, not a preference: there is no compare-at price, no
 * was–now, no "% OFF" badge on a listing and no countdown anywhere in this feature. A coupon is a
 * genuine reduction of the money actually charged, and it is only ever displayed on the checkout
 * and order surfaces.
 *
 * <p>Two entry points matter, and they must never disagree by a piastre:
 *
 * <ul>
 *   <li>{@link #preview} — the pre-checkout advisory (`POST …/coupons/validate`). No lock, no
 *       write.
 *   <li>{@link #resolveForOrder} — the authority, run <b>inside the placement transaction</b> under
 *       a {@code FOR UPDATE} on the coupon row, which serializes the last-slot race.
 * </ul>
 *
 * Both compute the discount through {@link Coupon#discountFor}, so the arithmetic exists once.
 */
public class CouponService {
  private static final Logger log = LoggerFactory.getLogger(CouponService.class);

  /** The column bound; the service never stores a longer code. */
  static final int MAX_CODE_CHARS = 40;

  private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

  /**
   * The one message every "this code cannot be used" outcome collapses to — unknown, inactive,
   * not-yet-started, expired and exhausted alike. Codes are semi-public (they get shared, printed,
   * screenshotted), so refusing to distinguish those cases keeps the endpoint from becoming a code
   * oracle that reports which strings exist and why they failed.
   */
  public static final String INVALID_MESSAGE = "This code isn't valid or has expired";

  /** The one deliberately specific refusal: the code IS valid, the basket is just too small. */
  static String belowMinimumMessage(BigDecimal minSubtotal) {
    return "This code applies to orders of "
        + minSubtotal.stripTrailingZeros().toPlainString()
        + " EGP or more";
  }

  private final DSLContext rootDsl;
  private final CouponRepositoryFactory repoFactory;

  public CouponService(DSLContext rootDsl, CouponRepositoryFactory repoFactory) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
  }

  /** A coupon plus its live redemption count — the admin list/detail row. */
  public record CouponView(Coupon coupon, long redemptionCount) {}

  /**
   * The outcome of a successful resolution: what to freeze on the order, and how much comes off.
   */
  public record Applied(UUID couponId, String code, BigDecimal discount) {}

  /** The create payload; every field but {@code code}/{@code type}/{@code value} is optional. */
  public record CouponInput(
      String code,
      CouponType type,
      BigDecimal value,
      BigDecimal minSubtotal,
      OffsetDateTime startsAt,
      OffsetDateTime expiresAt,
      Integer maxRedemptions) {}

  /** The PATCH payload: the three mutable knobs. A null field is leave-unchanged. */
  public record CouponPatch(Boolean active, OffsetDateTime expiresAt, Integer maxRedemptions) {}

  // --- checkout plane ---

  /**
   * The pre-checkout preview: what would this code take off a basket of {@code subtotal}? Advisory
   * by construction — it takes no lock and reserves nothing, so a slot can legitimately vanish
   * between this call and placement. That race resolves at placement (the uniform 400, no order
   * created), which is why the checkout page treats this answer as an estimate and the placed order
   * as truth.
   *
   * <p>Throws exactly the same {@link ValidationException}s as {@link #resolveForOrder}, so the
   * shopper never sees one message here and a different one a click later.
   */
  public Applied preview(UUID orgId, String rawCode, BigDecimal subtotal) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    CouponRepository repo = repoFactory.create(rootDsl);
    String code = normalizeCode(rawCode);
    Coupon coupon = repo.findByCode(orgId, code).orElseThrow(CouponService::invalid);
    return check(repo, orgId, coupon, subtotal, now);
  }

  /**
   * Resolve a code for a placement, <b>inside the caller's transaction</b>: normalize → load {@code
   * FOR UPDATE} → active + window + minimum + redemption cap → compute the discount. The row lock
   * is what makes the cap real: two concurrent placements of a cap-1 code serialize here, the
   * second sees the first's order in the count, and exactly one succeeds.
   *
   * <p>Returns null for a null/blank code — an order without a coupon is the overwhelmingly common
   * case and must not be an error.
   */
  public Applied resolveForOrder(
      DSLContext txDsl, UUID orgId, String rawCode, BigDecimal subtotal, OffsetDateTime now) {
    if (rawCode == null || rawCode.isBlank()) {
      return null;
    }
    CouponRepository repo = repoFactory.create(txDsl);
    String code = normalizeCode(rawCode);
    Coupon coupon = repo.findByCodeForUpdate(orgId, code).orElseThrow(CouponService::invalid);
    Applied applied = check(repo, orgId, coupon, subtotal, now);
    log.info(
        "Applied coupon {} (id={}) orgId={} subtotal={} discount={}",
        applied.code(),
        applied.couponId(),
        orgId,
        subtotal,
        applied.discount());
    return applied;
  }

  /**
   * The shared eligibility gate + arithmetic behind {@link #preview} and {@link #resolveForOrder}.
   *
   * <p>Order of checks is deliberate: the window/active/cap failures collapse to the uniform
   * message, but the below-minimum case is checked <b>before</b> the cap so a shopper with a
   * too-small basket gets the helpful "spend N to qualify" answer rather than a generic refusal
   * that happens to be caused by something else. A zero discount (a percent code rounding to
   * nothing on a tiny basket) is also refused: applying a code that takes nothing off would be
   * theatre.
   */
  private Applied check(
      CouponRepository repo, UUID orgId, Coupon coupon, BigDecimal subtotal, OffsetDateTime now) {
    if (!coupon.isLiveAt(now)) {
      throw invalid();
    }
    if (!coupon.meetsMinimum(subtotal)) {
      throw new ValidationException(belowMinimumMessage(coupon.getMinSubtotal()));
    }
    if (coupon.getMaxRedemptions() != null
        && repo.countLiveRedemptions(orgId, coupon.getId()) >= coupon.getMaxRedemptions()) {
      throw invalid();
    }
    BigDecimal discount = coupon.discountFor(subtotal);
    if (discount.signum() <= 0) {
      throw invalid();
    }
    return new Applied(coupon.getId(), coupon.getCode(), discount);
  }

  private static ValidationException invalid() {
    return new ValidationException(INVALID_MESSAGE);
  }

  // --- admin plane ---

  public List<CouponView> getAll(UUID orgId, int page, int size) {
    CouponRepository repo = repoFactory.create(rootDsl);
    List<Coupon> coupons = repo.findAll(orgId, Pagination.offset(page, size), size);
    if (coupons.isEmpty()) {
      return List.of();
    }
    Map<UUID, Long> counts =
        repo.liveRedemptionCounts(orgId, coupons.stream().map(Coupon::getId).toList());
    return coupons.stream()
        .map(c -> new CouponView(c, counts.getOrDefault(c.getId(), 0L)))
        .toList();
  }

  public long count(UUID orgId) {
    return repoFactory.create(rootDsl).count(orgId);
  }

  public CouponView getById(UUID orgId, UUID id) {
    CouponRepository repo = repoFactory.create(rootDsl);
    Coupon coupon = repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Coupon", id));
    return new CouponView(coupon, repo.countLiveRedemptions(orgId, id));
  }

  /**
   * Create a coupon. Percent ∈ (0, 100]; fixed > 0; a window must be sane ({@code starts <
   * expires}); a duplicate code is a 409. The code is stored normalized UPPER, which is what makes
   * the unique index meaningful and lets a shopper type it in any case.
   */
  public Coupon create(UUID orgId, CouponInput input) {
    String code = normalizeCode(input.code());
    CouponType type = requireType(input.type());
    BigDecimal value = validateValue(type, input.value());
    BigDecimal minSubtotal = validateMinSubtotal(input.minSubtotal());
    validateWindow(input.startsAt(), input.expiresAt());
    Integer maxRedemptions = validateMaxRedemptions(input.maxRedemptions());

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          CouponRepository repo = repoFactory.create(txDsl);
          if (repo.existsByCode(orgId, code)) {
            throw new ConflictException("Coupon code already used in this org: " + code);
          }
          Coupon coupon = new Coupon();
          coupon.setOrgId(orgId);
          coupon.setCode(code);
          coupon.setType(type);
          coupon.setValue(value);
          coupon.setMinSubtotal(minSubtotal);
          coupon.setStartsAt(input.startsAt());
          coupon.setExpiresAt(input.expiresAt());
          coupon.setMaxRedemptions(maxRedemptions);
          coupon.setActive(true);
          Coupon saved = repo.insert(coupon);
          log.info(
              "Created coupon id={} orgId={} code={} type={}", saved.getId(), orgId, code, type);
          return saved;
        });
  }

  /**
   * Patch the three mutable knobs — {@code active}, {@code expiresAt}, {@code maxRedemptions}.
   * Code, type and value are <b>immutable</b>: orders froze that arithmetic, so editing a live
   * code's value would silently rewrite what shoppers already agreed to. Make a new code instead.
   *
   * <p>Lowering {@code maxRedemptions} below the live count is allowed and is not a bug: it stops
   * further redemptions without touching the orders that already hold one (the cap is checked as
   * {@code count >= max}, so an over-subscribed coupon simply refuses new applies).
   */
  public Coupon patch(UUID orgId, UUID id, CouponPatch patch) {
    Integer maxRedemptions =
        patch.maxRedemptions() == null ? null : validateMaxRedemptions(patch.maxRedemptions());
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          CouponRepository repo = repoFactory.create(txDsl);
          Coupon coupon =
              repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Coupon", id));
          if (patch.active() != null) {
            coupon.setActive(patch.active());
          }
          if (patch.expiresAt() != null) {
            validateWindow(coupon.getStartsAt(), patch.expiresAt());
            coupon.setExpiresAt(patch.expiresAt());
          }
          if (maxRedemptions != null) {
            coupon.setMaxRedemptions(maxRedemptions);
          }
          Coupon updated = repo.updateMutable(coupon);
          log.info("Patched coupon id={} orgId={} active={}", id, orgId, updated.isActive());
          return updated;
        });
  }

  /**
   * Delete a coupon — only while it has never been redeemed. A referenced coupon is a 409 naming
   * the remedy: its orders quote it, and an order must always be able to say which code produced
   * its discount, so retiring a used code is a deactivation, never a delete.
   */
  public void delete(UUID orgId, UUID id) {
    rootDsl.transaction(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          CouponRepository repo = repoFactory.create(txDsl);
          repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Coupon", id));
          if (repo.hasAnyRedemption(orgId, id)) {
            throw new ConflictException(
                "This code has been used on an order — deactivate it instead of deleting it");
          }
          repo.deleteById(orgId, id);
          log.info("Deleted coupon id={} orgId={}", id, orgId);
        });
  }

  // --- validation ---

  /**
   * Normalize a code to its stored form: NFC + whitespace-collapsed via {@link Text} (the house
   * discipline for user text), then trimmed and upper-cased so "ramadan10", " Ramadan10 " and
   * "RAMADAN10" are one code rather than three.
   */
  static String normalizeCode(String raw) {
    String cleaned = Text.normalizeText(raw);
    if (cleaned == null) {
      throw new ValidationException("code is required");
    }
    String code = cleaned.replace(" ", "").toUpperCase(Locale.ROOT);
    if (code.isEmpty()) {
      throw new ValidationException("code is required");
    }
    if (code.length() > MAX_CODE_CHARS) {
      throw new ValidationException("code exceeds the " + MAX_CODE_CHARS + "-character limit");
    }
    return code;
  }

  private static CouponType requireType(CouponType type) {
    if (type == null) {
      throw new ValidationException("type must be PERCENT or FIXED");
    }
    return type;
  }

  private static BigDecimal validateValue(CouponType type, BigDecimal value) {
    if (value == null || value.signum() <= 0) {
      throw new ValidationException("value must be greater than 0");
    }
    if (type == CouponType.PERCENT && value.compareTo(ONE_HUNDRED) > 0) {
      throw new ValidationException("a percent value must not exceed 100");
    }
    return value;
  }

  private static BigDecimal validateMinSubtotal(BigDecimal minSubtotal) {
    if (minSubtotal == null) {
      return null;
    }
    if (minSubtotal.signum() < 0) {
      throw new ValidationException("min_subtotal must be >= 0");
    }
    return minSubtotal;
  }

  private static void validateWindow(OffsetDateTime startsAt, OffsetDateTime expiresAt) {
    if (startsAt != null && expiresAt != null && !startsAt.isBefore(expiresAt)) {
      throw new ValidationException("starts_at must be before expires_at");
    }
  }

  private static Integer validateMaxRedemptions(Integer maxRedemptions) {
    if (maxRedemptions == null) {
      return null;
    }
    if (maxRedemptions <= 0) {
      throw new ValidationException("max_redemptions must be greater than 0");
    }
    return maxRedemptions;
  }
}
