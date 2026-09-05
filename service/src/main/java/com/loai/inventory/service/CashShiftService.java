package com.loai.inventory.service;

import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ShiftRequiredException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.CashMovement;
import com.loai.inventory.domain.model.CashMovementKind;
import com.loai.inventory.domain.model.CashShift;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.repository.CashMovementRepositoryFactory;
import com.loai.inventory.domain.repository.CashShiftRepository;
import com.loai.inventory.domain.repository.CashShiftRepository.Totals;
import com.loai.inventory.domain.repository.CashShiftRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cash drawer's day ({@code stories/cash_shift.md}). Three human inputs — the float, the count,
 * a movement with its reason — and one derived figure, expected cash, computed from the ledger rows
 * the money paths stamped and never typed. Designed for the shop of one first: a shift opens itself
 * on the first counter event, closes once by whoever opened it with no second person, and only
 * gates selling when the org switched {@code shift_required} on.
 */
public final class CashShiftService implements CashShiftStamper {

  private static final Logger log = LoggerFactory.getLogger(CashShiftService.class);

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  private final DSLContext rootDsl;
  private final CashShiftRepositoryFactory shiftRepoFactory;
  private final CashMovementRepositoryFactory movementRepoFactory;
  private final OrgRepositoryFactory orgRepoFactory;
  private final UserRepositoryFactory userRepoFactory;

  public CashShiftService(
      DSLContext rootDsl,
      CashShiftRepositoryFactory shiftRepoFactory,
      CashMovementRepositoryFactory movementRepoFactory,
      OrgRepositoryFactory orgRepoFactory,
      UserRepositoryFactory userRepoFactory) {
    this.rootDsl = rootDsl;
    this.shiftRepoFactory = shiftRepoFactory;
    this.movementRepoFactory = movementRepoFactory;
    this.orgRepoFactory = orgRepoFactory;
    this.userRepoFactory = userRepoFactory;
  }

  /** A user as the slip and the list name them. */
  public record Person(UUID id, String name) {}

  /** A shift with its live (or frozen) figures and the people around it. */
  public record ShiftView(
      CashShift shift, Totals totals, BigDecimal expectedCash, Person openedBy, Person closedBy) {

    /** {@code counted − expected}; {@code null} while open. */
    public BigDecimal difference() {
      return shift.isOpen() ? null : shift.getCountedCash().subtract(expectedCash);
    }
  }

  public record MovementView(CashMovement movement, Person recordedBy) {}

  public record Detail(ShiftView view, List<MovementView> movements) {}

  public record ShiftPage(List<ShiftView> items, long total) {}

  // Arithmetic

  /**
   * {@code float + cash sales − change given − cash refunds + pay-ins − pay-outs}: the figure the
   * drawer should hold. Pure, so the split of change from refunds and the sign of each movement are
   * pinned without a database.
   */
  public static BigDecimal expectedCash(BigDecimal startingCash, Totals t) {
    return nz(startingCash)
        .add(nz(t.cashSales()))
        .subtract(nz(t.changeGiven()))
        .subtract(nz(t.cashRefunds()))
        .add(nz(t.payIn()))
        .subtract(nz(t.payOut()))
        .setScale(2, RoundingMode.HALF_EVEN);
  }

  // The stamp (CashShiftStamper)

  @Override
  public UUID shiftForCounterInTx(DSLContext txDsl, UUID orgId, UUID actorId, OffsetDateTime now) {
    CashShiftRepository repo = shiftRepoFactory.create(txDsl);
    Optional<CashShift> open = repo.findOpenForUpdate(orgId);
    if (open.isPresent()) {
      return open.get().getId();
    }
    Org org =
        orgRepoFactory
            .create(txDsl)
            .findById(orgId)
            .orElseThrow(() -> new NotFoundException("Org", orgId));
    if (org.isShiftRequired()) {
      throw new ShiftRequiredException();
    }
    if (actorId == null) {
      throw new ValidationException("actor identity is required to open a cash shift");
    }
    BigDecimal carried =
        repo.findLastClosed(orgId).map(CashShift::getCountedCash).orElse(BigDecimal.ZERO);
    CashShift shift = CashShift.open(UUID.randomUUID(), orgId, actorId, carried, true, null, now);
    repo.insert(shift);
    log.info("Auto-opened cash shift {} for org {} with float {}", shift.getId(), orgId, carried);
    return shift.getId();
  }

  // Commands

  /** Open a shift with a counted float. 409 when one is already open. */
  public ShiftView open(UUID orgId, BigDecimal startingCash, String note, UUID actorId) {
    requireActor(actorId);
    requireMoney(startingCash, "starting_cash");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          CashShiftRepository repo = shiftRepoFactory.create(tx);
          if (repo.findOpenForUpdate(orgId).isPresent()) {
            throw new ConflictException("a cash shift is already open");
          }
          CashShift shift =
              CashShift.open(UUID.randomUUID(), orgId, actorId, startingCash, false, note, now);
          repo.insert(shift);
          log.info(
              "Opened cash shift {} for org {} by {} float {}",
              shift.getId(),
              orgId,
              actorId,
              startingCash);
          return view(tx, shift);
        });
  }

  /** Fix the float while open — the one edit an auto-opened shift invites. */
  public ShiftView setStartingCash(
      UUID orgId, UUID shiftId, BigDecimal startingCash, UUID actorId, boolean managerAuthority) {
    requireActor(actorId);
    requireMoney(startingCash, "starting_cash");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          CashShiftRepository repo = shiftRepoFactory.create(tx);
          CashShift shift = openShiftForUpdate(repo, orgId, shiftId);
          requireOwnOrManager(shift, actorId, managerAuthority, "change the float of");
          shift.setStartingCash(startingCash, now);
          repo.update(shift);
          return view(tx, shift);
        });
  }

  /** Cash in or out of the drawer outside a sale, with the reason that makes it not a leak. */
  public MovementView addMovement(
      UUID orgId,
      UUID shiftId,
      CashMovementKind kind,
      BigDecimal amount,
      String reason,
      UUID actorId,
      boolean managerAuthority) {
    requireActor(actorId);
    if (kind == null) {
      throw new ValidationException("kind must be PAY_IN or PAY_OUT");
    }
    if (amount == null || amount.signum() <= 0) {
      throw new ValidationException("amount must be > 0");
    }
    String r = reason == null ? "" : reason.trim();
    if (r.isEmpty() || r.length() > 200) {
      throw new ValidationException("reason is required (1–200 characters)");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          CashShift shift = openShiftForUpdate(shiftRepoFactory.create(tx), orgId, shiftId);
          requireOwnOrManager(shift, actorId, managerAuthority, "move cash on");
          CashMovement m =
              CashMovement.create(UUID.randomUUID(), orgId, shiftId, kind, amount, r, actorId, now);
          movementRepoFactory.create(tx).insert(m);
          log.info("Cash {} {} on shift {} by {}: {}", kind, amount, shiftId, actorId, r);
          return new MovementView(m, person(tx, actorId));
        });
  }

  /** Count the drawer: expected is derived here and frozen with the count. Closes once. */
  public ShiftView close(
      UUID orgId,
      UUID shiftId,
      BigDecimal countedCash,
      String note,
      UUID actorId,
      boolean managerAuthority) {
    requireActor(actorId);
    requireMoney(countedCash, "counted_cash");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          CashShiftRepository repo = shiftRepoFactory.create(tx);
          CashShift shift = openShiftForUpdate(repo, orgId, shiftId);
          requireOwnOrManager(shift, actorId, managerAuthority, "close");
          Totals totals = repo.totals(orgId, shiftId);
          BigDecimal expected = expectedCash(shift.getStartingCash(), totals);
          shift.close(actorId, countedCash, expected, note, now);
          repo.update(shift);
          log.info(
              "Closed cash shift {} for org {} by {}: expected {} counted {}",
              shiftId,
              orgId,
              actorId,
              expected,
              countedCash);
          return new ShiftView(
              shift, totals, expected, person(tx, shift.getOpenedBy()), person(tx, actorId));
        });
  }

  // Reads

  public Optional<ShiftView> current(UUID orgId) {
    return shiftRepoFactory.create(rootDsl).findOpen(orgId).map(s -> view(rootDsl, s));
  }

  public Detail get(UUID orgId, UUID shiftId) {
    CashShift shift =
        shiftRepoFactory
            .create(rootDsl)
            .findById(orgId, shiftId)
            .orElseThrow(() -> new NotFoundException("CashShift", shiftId));
    List<MovementView> movements =
        movementRepoFactory.create(rootDsl).findByShift(orgId, shiftId).stream()
            .map(m -> new MovementView(m, person(rootDsl, m.getRecordedBy())))
            .toList();
    return new Detail(view(rootDsl, shift), movements);
  }

  public ShiftPage list(UUID orgId, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    CashShiftRepository repo = shiftRepoFactory.create(rootDsl);
    List<ShiftView> items =
        repo.list(orgId, p * s, s).stream().map(sh -> view(rootDsl, sh)).toList();
    return new ShiftPage(items, repo.count(orgId));
  }

  // Helpers

  private ShiftView view(DSLContext dsl, CashShift shift) {
    Totals totals = shiftRepoFactory.create(dsl).totals(shift.getOrgId(), shift.getId());
    BigDecimal expected =
        shift.isOpen() ? expectedCash(shift.getStartingCash(), totals) : shift.getExpectedCash();
    return new ShiftView(
        shift,
        totals,
        expected,
        person(dsl, shift.getOpenedBy()),
        shift.getClosedBy() == null ? null : person(dsl, shift.getClosedBy()));
  }

  private Person person(DSLContext dsl, UUID userId) {
    if (userId == null) {
      return null;
    }
    return userRepoFactory
        .create(dsl)
        .findById(userId)
        .map(u -> new Person(userId, displayName(u)))
        .orElse(new Person(userId, null));
  }

  private static String displayName(AppUser u) {
    String n = u.getDisplayName();
    return n == null || n.isBlank() ? u.getEmail() : n;
  }

  private static CashShift openShiftForUpdate(CashShiftRepository repo, UUID orgId, UUID shiftId) {
    CashShift shift =
        repo.findById(orgId, shiftId)
            .orElseThrow(() -> new NotFoundException("CashShift", shiftId));
    if (!shift.isOpen()) {
      throw new ConflictException("cash shift " + shiftId + " is closed");
    }
    // Lock the open row so a concurrent close / movement / stamp serialises on it.
    return repo.findOpenForUpdate(orgId)
        .filter(s -> s.getId().equals(shiftId))
        .orElseThrow(() -> new ConflictException("cash shift " + shiftId + " is closed"));
  }

  private static void requireOwnOrManager(
      CashShift shift, UUID actorId, boolean managerAuthority, String verb) {
    if (!managerAuthority && !shift.getOpenedBy().equals(actorId)) {
      throw new AuthorizationException(
          "this shift was opened by someone else; a manager can " + verb + " it");
    }
  }

  private static void requireActor(UUID actorId) {
    if (actorId == null) {
      throw new ValidationException("actor identity is required");
    }
  }

  private static void requireMoney(BigDecimal amount, String field) {
    if (amount == null || amount.signum() < 0) {
      throw new ValidationException(field + " must be >= 0");
    }
  }

  private static BigDecimal nz(BigDecimal b) {
    return b == null ? BigDecimal.ZERO : b;
  }
}
