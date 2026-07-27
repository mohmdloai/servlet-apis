package com.loai.inventory.service.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.PlatformSearchGroup;
import com.loai.inventory.domain.model.PlatformSearchResult;
import com.loai.inventory.domain.model.PlatformSearchType;
import com.loai.inventory.domain.repository.PlatformSearchRepository;
import com.loai.inventory.domain.repository.PlatformSearchRepositoryFactory;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * The classification and normalization rules of {@link PlatformSearchQuery} as a unit, plus the
 * group-assembly rules of {@link PlatformSearchService} — the parts an integration test can only
 * observe indirectly, through which rows come back.
 *
 * <p>Asserting on <em>which repository method was called with what</em> is the point: it is how a
 * reviewer's reading of the classification table gets checked mechanically. An IT can tell you that
 * an {@code @} query returned no orders; only this can tell you the order probe never ran.
 */
class PlatformSearchServiceTest {

  private PlatformSearchRepository repo;
  private PlatformSearchService service;

  @BeforeEach
  void setUp() {
    repo = mock(PlatformSearchRepository.class);
    PlatformSearchRepositoryFactory factory = mock(PlatformSearchRepositoryFactory.class);
    when(factory.create(any())).thenReturn(repo);
    when(repo.orgs(any(), anyInt())).thenReturn(PlatformSearchGroup.empty(PlatformSearchType.ORG));
    when(repo.users(any(), anyInt()))
        .thenReturn(PlatformSearchGroup.empty(PlatformSearchType.APP_USER));
    when(repo.customers(any(), anyInt()))
        .thenReturn(PlatformSearchGroup.empty(PlatformSearchType.CUSTOMER));
    when(repo.salesOrders(any(), anyInt()))
        .thenReturn(PlatformSearchGroup.empty(PlatformSearchType.SALES_ORDER));
    when(repo.paymentTransactions(any(), anyInt()))
        .thenReturn(PlatformSearchGroup.empty(PlatformSearchType.PAYMENT_TRANSACTION));
    service = new PlatformSearchService(mock(DSLContext.class), factory);
  }

  // ---------------------------------------------------------------- classification

  /** An {@code @} fires both email probes and nothing else. */
  @Test
  void emailShape_firesBothEmailProbesOnly() {
    service.search("Nadia@Example.COM");

    verify(repo).users(any(), anyInt());
    verify(repo).customers(any(), anyInt());
    verify(repo, never()).salesOrders(any(), anyInt());
    verify(repo, never()).paymentTransactions(any(), anyInt());
    verify(repo, never()).orgs(any(), anyInt());
  }

  /**
   * The {@code SO-} prefix fires the order probe alone. Both live shapes classify: the allocator's
   * {@code SO-2026-00042} and {@code perfdb}'s seeded {@code SO-000001}, which a strict {@code
   * ^SO-\d{4}-\d{5}$} would have rejected outright.
   */
  @ParameterizedTest
  @ValueSource(strings = {"SO-2026-00042", "SO-000001", "so-2026-00042", "  so-000001  ", "SO-1"})
  void orderNumberShape_firesTheOrderProbeOnly(String raw) {
    service.search(raw);

    verify(repo).salesOrders(any(), anyInt());
    verify(repo, never()).users(any(), anyInt());
    verify(repo, never()).customers(any(), anyInt());
    verify(repo, never()).orgs(any(), anyInt());
    verify(repo, never()).paymentTransactions(any(), anyInt());
  }

  /** Everything else is the org + reference bucket. A bank reference can look like anything. */
  @Test
  void otherShape_firesOrgAndProviderRefOnly() {
    service.search("INSTA-99213");

    verify(repo).orgs(any(), anyInt());
    verify(repo).paymentTransactions(any(), anyInt());
    verify(repo, never()).users(any(), anyInt());
    verify(repo, never()).customers(any(), anyInt());
    verify(repo, never()).salesOrders(any(), anyInt());
  }

  /**
   * <strong>The known consequence, pinned rather than discovered.</strong> {@code @} is tested
   * before the {@code SO-} prefix and before the catch-all, so a {@code provider_ref} that happens
   * to contain an {@code @} classifies as an email query and never fires the reference probe. Bank
   * references are overwhelmingly alphanumeric, so this is the cheaper mistake — but it is a real
   * one, and the day it bites, this test is where the decision is recorded.
   */
  @Test
  void providerRefContainingAnAt_classifiesAsEmail_andNeverProbesTheReference() {
    service.search("REF@BANK/2026-11");

    verify(repo).users(any(), anyInt());
    verify(repo).customers(any(), anyInt());
    verify(repo, never()).paymentTransactions(any(), anyInt());
  }

  /** An {@code SO-} prefix inside an email is still an email — the {@code @} test comes first. */
  @Test
  void soPrefixedEmail_isAnEmailQuery() {
    service.search("so-desk@merchant.test");

    verify(repo).users(any(), anyInt());
    verify(repo, never()).salesOrders(any(), anyInt());
  }

  // ---------------------------------------------------------------- normalization

  /** {@code Text.normalizeEmail}: the same lowercasing/trimming the write path applied. */
  @Test
  void email_isNormalizedTheWayTheColumnWasWritten() {
    service.search("  Nadia@Example.COM ");

    assertEquals("nadia@example.com", capturedEmailFor(PlatformSearchType.APP_USER));
    assertEquals("nadia@example.com", capturedEmailFor(PlatformSearchType.CUSTOMER));
  }

  /** {@code Text.normalizeNumeric} then upper — the number is always minted upper. */
  @Test
  void orderNumber_isUpperCased() {
    service.search("so-2026-00042");
    assertEquals("SO-2026-00042", capturedOrderNumber());
  }

  /**
   * <strong>The Arabic-Indic digit fold, as behaviour.</strong> This is an Egypt-first product and
   * references get pasted out of Arabic-locale bank SMS. {@code Text.normalizeNumeric} folds {@code
   * U+0660–0669} and Persian {@code U+06F0–06F9} to ASCII, which is exactly what {@code
   * PaymentTransactionService} did on the way in.
   */
  @Test
  void arabicIndicDigitsInAProviderRef_foldToAscii() {
    service.search("INSTA-٠١٢٣٤");
    assertEquals("INSTA-01234", capturedProviderRef());
  }

  @Test
  void persianDigitsInAnOrderNumber_foldToAsciiAndClassify() {
    service.search("SO-۲۰۲۶-۰۰۰۴۲");

    verify(repo).salesOrders(any(), anyInt());
    assertEquals("SO-2026-00042", capturedOrderNumber());
  }

  /**
   * Arabic-Indic digits fold <em>before</em> the prefix test, so a reference typed entirely in
   * Arabic-locale digits still classifies as an order number rather than falling to the catch-all.
   */
  @Test
  void arabicIndicOrderNumber_classifiesOnTheFoldedForm() {
    service.search("so-٠٠٠٠٠١");

    verify(repo).salesOrders(any(), anyInt());
    assertEquals("SO-000001", capturedOrderNumber());
  }

  /** The org term is passed through raw — {@code fold_search} runs on both sides in SQL. */
  @Test
  void orgTerm_isPassedThroughForTheDatabaseToFold() {
    service.search("  أحمد   Store  ");
    // normalizeText collapses the internal whitespace run; the fold itself is the DB's job.
    assertEquals("أحمد Store", capturedOrgTerm());
  }

  // ---------------------------------------------------------------- input contract

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "\t\n"})
  void blankQuery_is400(String raw) {
    ValidationException e = assertThrows(ValidationException.class, () -> service.search(raw));
    assertTrue(e.getMessage().contains("q"));
  }

  @Test
  void missingQuery_is400() {
    assertThrows(ValidationException.class, () -> service.search(null));
  }

  /** Not an empty result set — the caller made a mistake, and the 400 names the minimum. */
  @Test
  void singleCharQuery_is400NamingTheMinimum() {
    ValidationException e = assertThrows(ValidationException.class, () -> service.search("a"));
    assertTrue(
        e.getMessage().contains(String.valueOf(PlatformSearchQuery.MIN_LENGTH)),
        "400 did not name the minimum: " + e.getMessage());
  }

  /** Two characters is the floor, and it is a floor on the <em>normalized</em> length. */
  @Test
  void twoCharsIsAccepted_andWhitespaceDoesNotCountTowardsIt() {
    service.search("ab");
    assertThrows(ValidationException.class, () -> service.search(" a "));
  }

  // ---------------------------------------------------------------- assembly

  /**
   * Empty groups are dropped, never serialized as {@code total: 0}. A client branches on presence —
   * the same reflex as the overview's {@code degraded[]}.
   */
  @Test
  void emptyGroupsAreOmitted() {
    when(repo.customers(any(), anyInt()))
        .thenReturn(
            new PlatformSearchGroup(
                PlatformSearchType.CUSTOMER,
                1,
                List.of(
                    new PlatformSearchResult(
                        PlatformSearchType.CUSTOMER,
                        UUID.randomUUID(),
                        "nadia@example.com",
                        null,
                        null))));

    PlatformSearchService.SearchResults results = service.search("nadia@example.com");

    assertEquals(1, results.groups().size(), "the empty app_user group was not dropped");
    assertEquals(PlatformSearchType.CUSTOMER, results.groups().get(0).type());
  }

  /** The echoed query is the normalized one, so a client can confirm what actually ran. */
  @Test
  void theEchoedQueryIsTheNormalizedOne() {
    assertEquals("nadia@example.com", service.search(" nadia@example.com ").query());
  }

  /** The cap the repository is asked for is the declared one, on every probe. */
  @Test
  void everyProbeIsCappedAtFive() {
    service.search("nadia@example.com");
    ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
    verify(repo).users(any(), limit.capture());
    assertEquals(PlatformSearchQuery.GROUP_CAP, limit.getValue());
    assertEquals(5, PlatformSearchQuery.GROUP_CAP, "the story's cap is 5");
  }

  /**
   * The classification table covers every type in the union. A sixth type added to {@link
   * PlatformSearchType} without a row in the table would be silently unreachable — no compile
   * error, no failing IT, just a group that never appears.
   */
  @Test
  void everyTypeIsReachableFromSomeShape() {
    assertEquals(
        List.of(PlatformSearchType.values()),
        PlatformSearchQuery.coveredTypes(),
        "a search type has no row in the classification table");
  }

  /**
   * A term that normalizes away entirely is dropped rather than run as an equality against null.
   */
  @Test
  void aTermThatNormalizesToNothing_isNotProbed() {
    // "@" alone is 1 char and rejected earlier; "@​" keeps 2 chars pre-normalization but the
    // zero-width space is stripped, so this exercises the length gate on the cleaned value.
    assertThrows(ValidationException.class, () -> service.search("@​"));
    assertNull(PlatformSearchQuery.of("ab").termFor(PlatformSearchType.SALES_ORDER));
  }

  // ---------------------------------------------------------------- plumbing

  private String capturedEmailFor(PlatformSearchType type) {
    ArgumentCaptor<String> term = ArgumentCaptor.forClass(String.class);
    if (type == PlatformSearchType.APP_USER) {
      verify(repo).users(term.capture(), anyInt());
    } else {
      verify(repo).customers(term.capture(), anyInt());
    }
    return term.getValue();
  }

  private String capturedOrderNumber() {
    ArgumentCaptor<String> term = ArgumentCaptor.forClass(String.class);
    verify(repo).salesOrders(term.capture(), anyInt());
    return term.getValue();
  }

  private String capturedProviderRef() {
    ArgumentCaptor<String> term = ArgumentCaptor.forClass(String.class);
    verify(repo).paymentTransactions(term.capture(), anyInt());
    return term.getValue();
  }

  private String capturedOrgTerm() {
    ArgumentCaptor<String> term = ArgumentCaptor.forClass(String.class);
    verify(repo).orgs(term.capture(), anyInt());
    return term.getValue();
  }
}
