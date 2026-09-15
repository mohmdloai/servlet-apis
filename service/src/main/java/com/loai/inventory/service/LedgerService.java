package com.loai.inventory.service;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ledger.AccountStatement;
import com.loai.inventory.domain.model.ledger.JournalPage;
import com.loai.inventory.domain.model.ledger.LedgerChart;
import com.loai.inventory.domain.model.ledger.LedgerHealth;
import com.loai.inventory.domain.model.ledger.PostingSummary;
import com.loai.inventory.domain.model.ledger.TrialBalanceRow;
import com.loai.inventory.domain.repository.LedgerRepository;
import com.loai.inventory.domain.repository.LedgerRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The general ledger's service (stories/general_ledger.md): parameter rules for the reads, the
 * catch-up that runs before every read, the rebuild, and the sweep the recurring job drives.
 *
 * <p><b>Catch-up on read.</b> Every read first posts whatever the org's source rows imply that is
 * not posted yet ({@link LedgerRepository#postMissing}) in its own short transaction, then reads.
 * The ledger is therefore current the moment anyone looks at it, without a hook in any money write
 * site — and the poster being idempotent, a read that races the job or another read simply finds
 * nothing left to post. The cost is one anti-join per event kind over the org's rows, measured on
 * perfdb (tools/seed/results/general_ledger_196.txt).
 */
public final class LedgerService {

  private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

  /** Same rules as {@code ReportService}: a window is at most a year and a page at most 200. */
  static final long MAX_WINDOW_DAYS = 366;

  static final int DEFAULT_WINDOW_DAYS = 30;
  static final int DEFAULT_PAGE_SIZE = 50;
  static final int MAX_PAGE_SIZE = 200;

  private final DSLContext rootDsl;
  private final LedgerRepositoryFactory ledgerRepoFactory;
  private final OrgRepositoryFactory orgRepoFactory;

  public LedgerService(
      DSLContext rootDsl,
      LedgerRepositoryFactory ledgerRepoFactory,
      OrgRepositoryFactory orgRepoFactory) {
    this.rootDsl = rootDsl;
    this.ledgerRepoFactory = ledgerRepoFactory;
    this.orgRepoFactory = orgRepoFactory;
  }

  public record Window(OffsetDateTime from, OffsetDateTime to) {}

  public record Page(int page, int size) {
    int offset() {
      return page * size;
    }
  }

  // Posting

  /** Post everything the org's rows imply that is not posted yet. Idempotent. */
  public PostingSummary catchUp(UUID orgId) {
    PostingSummary s =
        rootDsl.transactionResult(
            cfg -> ledgerRepoFactory.create(DSL.using(cfg)).postMissing(orgId));
    if (s.total() > 0) {
      log.info("Ledger catch-up for org {}: {} entries {}", orgId, s.total(), s.inserted());
    }
    return s;
  }

  public record Rebuilt(int removed, PostingSummary posted) {}

  /**
   * Re-derive the org's journal: with {@code reset} the existing entries are removed first (they
   * are derived data — a reset is a cache flush that also re-numbers), otherwise this is the
   * catch-up under its explicit name.
   */
  public Rebuilt rebuild(UUID orgId, boolean reset) {
    return rootDsl.transactionResult(
        cfg -> {
          LedgerRepository repo = ledgerRepoFactory.create(DSL.using(cfg));
          int removed = reset ? repo.reset(orgId) : 0;
          PostingSummary posted = repo.postMissing(orgId);
          log.info(
              "Ledger rebuild for org {} (reset={}): removed {} posted {}",
              orgId,
              reset,
              removed,
              posted.total());
          return new Rebuilt(removed, posted);
        });
  }

  /**
   * The recurring job's tick: catch every org up, one transaction each, so a failure on one org (a
   * defect the trigger catches) never blocks the others. Returns entries posted.
   */
  public int sweepAll(int orgPageSize) {
    int posted = 0;
    int offset = 0;
    while (true) {
      List<UUID> ids =
          orgRepoFactory.create(rootDsl).findAll(offset, orgPageSize).stream()
              .map(o -> o.getId())
              .toList();
      if (ids.isEmpty()) {
        return posted;
      }
      for (UUID orgId : ids) {
        try {
          posted += catchUp(orgId).total();
        } catch (RuntimeException e) {
          log.error("Ledger sweep failed for org {}", orgId, e);
        }
      }
      if (ids.size() < orgPageSize) {
        return posted;
      }
      offset += orgPageSize;
    }
  }

  // Reads (each catches up first)

  public record TrialBalance(OffsetDateTime from, OffsetDateTime to, List<TrialBalanceRow> rows) {}

  public TrialBalance trialBalance(UUID orgId, String fromParam, String toParam) {
    Window w = window(fromParam, toParam);
    catchUp(orgId);
    return new TrialBalance(
        w.from(), w.to(), ledgerRepoFactory.create(rootDsl).trialBalance(orgId, w.from(), w.to()));
  }

  public record Journal(
      OffsetDateTime from, OffsetDateTime to, String account, Page page, JournalPage result) {}

  public Journal journal(
      UUID orgId,
      String fromParam,
      String toParam,
      String accountParam,
      String pageParam,
      String sizeParam) {
    Window w = window(fromParam, toParam);
    String account = account(accountParam);
    Page p = page(pageParam, sizeParam);
    catchUp(orgId);
    return new Journal(
        w.from(),
        w.to(),
        account,
        p,
        ledgerRepoFactory
            .create(rootDsl)
            .journal(orgId, w.from(), w.to(), account, p.offset(), p.size()));
  }

  public record Statement(
      OffsetDateTime from, OffsetDateTime to, Page page, AccountStatement result) {}

  public Statement statement(
      UUID orgId,
      String code,
      String fromParam,
      String toParam,
      String pageParam,
      String sizeParam) {
    Window w = window(fromParam, toParam);
    Page p = page(pageParam, sizeParam);
    if (LedgerChart.byCode(code) == null) {
      throw new NotFoundException("Ledger account " + code + " not found");
    }
    catchUp(orgId);
    AccountStatement s =
        ledgerRepoFactory
            .create(rootDsl)
            .statement(orgId, code, w.from(), w.to(), p.offset(), p.size())
            .orElseThrow(() -> new NotFoundException("Ledger account " + code + " not found"));
    return new Statement(w.from(), w.to(), p, s);
  }

  public LedgerHealth health(UUID orgId) {
    catchUp(orgId);
    return ledgerRepoFactory.create(rootDsl).health(orgId);
  }

  // Parameters

  static Window window(String fromParam, String toParam) {
    OffsetDateTime to =
        toParam == null || toParam.isBlank()
            ? OffsetDateTime.now(ZoneOffset.UTC)
            : parseTs("to", toParam);
    OffsetDateTime from =
        fromParam == null || fromParam.isBlank()
            ? to.minusDays(DEFAULT_WINDOW_DAYS)
            : parseTs("from", fromParam);
    if (!from.isBefore(to)) {
      throw new ValidationException("'from' must be strictly before 'to'");
    }
    if (Duration.between(from, to).toDays() > MAX_WINDOW_DAYS) {
      throw new ValidationException("window must not exceed " + MAX_WINDOW_DAYS + " days");
    }
    return new Window(from, to);
  }

  private static OffsetDateTime parseTs(String name, String value) {
    try {
      return OffsetDateTime.parse(value);
    } catch (DateTimeParseException e) {
      throw new ValidationException("'" + name + "' must be an ISO-8601 date-time");
    }
  }

  static String account(String param) {
    if (param == null || param.isBlank()) {
      return null;
    }
    String code = param.trim();
    if (LedgerChart.byCode(code) == null) {
      throw new ValidationException("'account' is not a ledger account code: " + code);
    }
    return code;
  }

  static Page page(String pageParam, String sizeParam) {
    int page = parseInt("page", pageParam, 0);
    int size = parseInt("size", sizeParam, DEFAULT_PAGE_SIZE);
    if (page < 0) {
      throw new ValidationException("'page' must be >= 0");
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ValidationException("'size' must be between 1 and " + MAX_PAGE_SIZE);
    }
    return new Page(page, size);
  }

  private static int parseInt(String name, String value, int dflt) {
    if (value == null || value.isBlank()) {
      return dflt;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new ValidationException("'" + name + "' must be an integer");
    }
  }
}
