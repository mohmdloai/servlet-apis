package com.loai.inventory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.ledger.AccountStatement;
import com.loai.inventory.domain.model.ledger.JournalPage;
import com.loai.inventory.domain.model.ledger.LedgerChart;
import com.loai.inventory.domain.model.ledger.LedgerHealth;
import com.loai.inventory.domain.model.ledger.PostingSummary;
import com.loai.inventory.domain.model.ledger.TrialBalanceRow;
import com.loai.inventory.domain.repository.LedgerRepository;
import com.loai.inventory.domain.repository.LedgerRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.TransactionalCallable;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.Test;

/**
 * {@link LedgerService} without a database: the parameter rules behind every 400 in
 * stories/general_ledger.md §Errors, the health verdict, and the sweep's one-org-at-a-time
 * resilience.
 */
class LedgerServiceTest {

  // Parameters

  @Test
  void window_defaultsToThirtyDaysEndingNow() {
    LedgerService.Window w = LedgerService.window(null, null);
    assertEquals(30, Duration.between(w.from(), w.to()).toDays());
    assertTrue(Duration.between(w.to(), OffsetDateTime.now(ZoneOffset.UTC)).abs().toSeconds() < 5);
  }

  @Test
  void window_rejectsInvertedEmptyOverlongAndUnparseable() {
    assertThrows(
        ValidationException.class,
        () -> LedgerService.window("2026-03-02T00:00:00Z", "2026-03-01T00:00:00Z"));
    assertThrows(
        ValidationException.class,
        () -> LedgerService.window("2026-03-01T00:00:00Z", "2026-03-01T00:00:00Z"));
    assertThrows(
        ValidationException.class,
        () -> LedgerService.window("2025-01-01T00:00:00Z", "2026-03-01T00:00:00Z"));
    assertThrows(ValidationException.class, () -> LedgerService.window("yesterday", null));
    assertThrows(ValidationException.class, () -> LedgerService.window(null, "2026-03-01"));
  }

  @Test
  void page_defaultsAndBounds() {
    LedgerService.Page p = LedgerService.page(null, null);
    assertEquals(0, p.page());
    assertEquals(LedgerService.DEFAULT_PAGE_SIZE, p.size());
    assertEquals(100, LedgerService.page("2", "50").offset());
    assertThrows(ValidationException.class, () -> LedgerService.page("-1", null));
    assertThrows(ValidationException.class, () -> LedgerService.page(null, "0"));
    assertThrows(ValidationException.class, () -> LedgerService.page(null, "201"));
    assertThrows(ValidationException.class, () -> LedgerService.page("x", null));
  }

  @Test
  void account_blankIsAbsent_unknownIs400_knownIsTrimmed() {
    assertNull(LedgerService.account(null));
    assertNull(LedgerService.account("  "));
    assertEquals(LedgerChart.CASH, LedgerService.account(" 1000 "));
    assertThrows(ValidationException.class, () -> LedgerService.account("9999"));
  }

  // Health verdict

  @Test
  void health_isOkOnlyWhenNothingIsUnpostedOrUnbalancedAndEveryCheckAgrees() {
    LedgerHealth.Check agree =
        new LedgerHealth.Check(
            "accounts_receivable", new BigDecimal("10.00"), new BigDecimal("10.00"));
    LedgerHealth.Check disagree =
        new LedgerHealth.Check("customer_deposits", new BigDecimal("1.00"), new BigDecimal("2.00"));
    assertTrue(
        new LedgerHealth(5, 0, Map.of("INVOICE/ISSUED", 0L), Map.of(), 3, List.of(agree), null)
            .ok());
    assertFalse(new LedgerHealth(5, 1, Map.of(), Map.of(), 0, List.of(agree), null).ok());
    assertFalse(
        new LedgerHealth(5, 0, Map.of("STOCK/MOVED", 2L), Map.of(), 0, List.of(agree), null).ok());
    assertFalse(new LedgerHealth(5, 0, Map.of(), Map.of(), 0, List.of(agree, disagree), null).ok());
  }

  // Sweep

  /** A poster that records the orgs it saw and blows up on one of them. */
  private static final class FakeLedger implements LedgerRepository {
    final List<UUID> posted = new ArrayList<>();
    final UUID poison;

    FakeLedger(UUID poison) {
      this.poison = poison;
    }

    @Override
    public void ensureChart(UUID orgId) {}

    @Override
    public PostingSummary postMissing(UUID orgId) {
      if (orgId.equals(poison)) {
        throw new IllegalStateException("unbalanced entry");
      }
      posted.add(orgId);
      return new PostingSummary(Map.of("INVOICE/ISSUED", 2));
    }

    @Override
    public int reset(UUID orgId) {
      return 0;
    }

    @Override
    public List<TrialBalanceRow> trialBalance(UUID orgId, OffsetDateTime from, OffsetDateTime to) {
      return List.of();
    }

    @Override
    public JournalPage journal(
        UUID orgId, OffsetDateTime from, OffsetDateTime to, String code, int offset, int limit) {
      return new JournalPage(List.of(), 0);
    }

    @Override
    public Optional<AccountStatement> statement(
        UUID orgId, String code, OffsetDateTime from, OffsetDateTime to, int offset, int limit) {
      return Optional.empty();
    }

    @Override
    public LedgerHealth health(UUID orgId) {
      return new LedgerHealth(0, 0, Map.of(), Map.of(), 0, List.of(), null);
    }
  }

  @SuppressWarnings("unchecked")
  private static DSLContext passThroughTransactions() {
    DSLContext dsl = mock(DSLContext.class);
    // A real (connection-less) configuration: DSL.using(cfg) inside the callable needs a clock.
    Configuration cfg = new DefaultConfiguration().set(SQLDialect.POSTGRES);
    when(dsl.transactionResult(any(TransactionalCallable.class)))
        .thenAnswer(inv -> ((TransactionalCallable<Object>) inv.getArgument(0)).run(cfg));
    return dsl;
  }

  @Test
  void sweepAll_walksEveryOrgPage_andOneFailingOrgDoesNotStopTheOthers() {
    UUID a = UUID.randomUUID();
    UUID poison = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    List<Org> orgs = List.of(org(a), org(poison), org(c));
    OrgRepository orgRepo = mock(OrgRepository.class);
    // page size 2: [a, poison] then [c]
    when(orgRepo.findAll(0, 2)).thenReturn(orgs.subList(0, 2));
    when(orgRepo.findAll(2, 2)).thenReturn(orgs.subList(2, 3));
    OrgRepositoryFactory orgFactory = ctx -> orgRepo;
    FakeLedger ledger = new FakeLedger(poison);
    LedgerRepositoryFactory ledgerFactory = ctx -> ledger;

    LedgerService service = new LedgerService(passThroughTransactions(), ledgerFactory, orgFactory);
    int posted = service.sweepAll(2);

    assertEquals(List.of(a, c), ledger.posted, "the poisoned org is skipped, the rest are posted");
    assertEquals(4, posted, "two entries per healthy org");
  }

  private static Org org(UUID id) {
    Org o = mock(Org.class);
    when(o.getId()).thenReturn(id);
    return o;
  }
}
