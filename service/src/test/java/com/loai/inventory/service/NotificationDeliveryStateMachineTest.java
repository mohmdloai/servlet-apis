package com.loai.inventory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.domain.model.DeliveryStatus;
import com.loai.inventory.domain.model.NotificationChannel;
import com.loai.inventory.domain.model.NotificationDelivery;
import com.loai.inventory.domain.repository.NotificationRepository;
import com.loai.inventory.domain.repository.NotificationRepositoryFactory;
import com.loai.inventory.service.email.EmailException;
import com.loai.inventory.service.email.EmailMessage;
import com.loai.inventory.service.email.EmailSender;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.TransactionalCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The email delivery state machine as a <b>unit</b> test (D11) — the retry/attempt ladder and the
 * claim/settle transitions, with no Postgres and no Docker.
 *
 * <p>These paths were only ever covered by TestContainers ITs. That is the right place to prove the
 * SQL, but it made the *decisions* expensive to exercise: which outcome a given (attempts,
 * maxAttempts, provider result) triple produces is arithmetic, and arithmetic should not need a
 * database to pin. The transaction boundary is mocked by running the callable inline, which is
 * exactly what a committed transaction does from this code's point of view.
 */
class NotificationDeliveryStateMachineTest {

  private static final UUID DELIVERY = UUID.randomUUID();
  private static final UUID NOTIFICATION = UUID.randomUUID();

  private DSLContext dsl;
  private NotificationRepository repo;
  private NotificationRepositoryFactory repoFactory;

  @BeforeEach
  void setUp() {
    dsl = mock(DSLContext.class);
    repo = mock(NotificationRepository.class);
    repoFactory = mock(NotificationRepositoryFactory.class);
    when(repoFactory.create(any())).thenReturn(repo);
    // Run every transactional callable inline: from the service's perspective a committed
    // transaction and a direct call are indistinguishable, and the SQL is the ITs' job.
    Configuration cfg = mock(Configuration.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(dsl.transactionResult(any(TransactionalCallable.class)))
        .thenAnswer(inv -> ((TransactionalCallable<?>) inv.getArgument(0)).run(cfg));
  }

  private NotificationService service(EmailSender sender, int maxAttempts) {
    return new NotificationService(
        dsl, repoFactory, null, null, null, null, sender, null, maxAttempts);
  }

  private static NotificationDelivery pending(int attempts) {
    return delivery(DeliveryStatus.PENDING, attempts);
  }

  private static NotificationDelivery delivery(DeliveryStatus status, int attempts) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return new NotificationDelivery(
        DELIVERY,
        NOTIFICATION,
        NotificationChannel.EMAIL,
        status,
        attempts,
        null,
        null,
        null,
        null,
        now,
        now);
  }

  private void queueOnePending(int attempts) {
    when(repo.findPendingDeliveryIds(NotificationChannel.EMAIL, 100)).thenReturn(List.of(DELIVERY));
    when(repo.claimForSend(eq(DELIVERY), any())).thenReturn(Optional.of(pending(attempts)));
    when(repo.findEmailDeliveryContent(DELIVERY))
        .thenReturn(
            Optional.of(
                new NotificationRepository.EmailDeliveryContent(
                    "to@x.io", "subject", "<p>hi</p>")));
  }

  // The happy path

  @Test
  void aSuccessfulSendSettlesSent_andTheClaimNeverCountsItsOwnAttempt() {
    queueOnePending(0);
    NotificationService.DeliverySummary summary = service(m -> {}, 5).dispatchPendingEmail(100);

    assertEquals(1, summary.sent());
    verify(repo).claimForSend(eq(DELIVERY), any());
    verify(repo).markDeliverySent(eq(DELIVERY), any());
    verify(repo, never()).markDeliveryRetry(any(), any(), any());
    verify(repo, never()).markDeliveryFailed(any(), any(), any());
  }

  // The retry ladder — the arithmetic this test exists for

  @Test
  void aTransientFailureBelowTheBudgetIsARetry() {
    queueOnePending(0); // 0 + 1 = 1 < 5
    NotificationService.DeliverySummary summary =
        service(failing("smtp timeout"), 5).dispatchPendingEmail(100);

    assertEquals(1, summary.retried());
    assertEquals(0, summary.failed());
    verify(repo).markDeliveryRetry(eq(DELIVERY), eq("smtp timeout"), any());
    verify(repo, never()).markDeliverySent(any(), any());
  }

  @Test
  void theAttemptThatReachesTheBudgetIsTerminal() {
    queueOnePending(4); // 4 + 1 = 5 == the budget
    NotificationService.DeliverySummary summary =
        service(failing("still down"), 5).dispatchPendingEmail(100);

    assertEquals(1, summary.failed());
    assertEquals(0, summary.retried());
    verify(repo).markDeliveryFailed(eq(DELIVERY), eq("still down"), any());
  }

  /** The boundary is `>=`, so a row already at the budget cannot go round again. */
  @Test
  void pastTheBudgetIsStillTerminal_neverAnotherRetry() {
    queueOnePending(9);
    service(failing("nope"), 5).dispatchPendingEmail(100);

    verify(repo).markDeliveryFailed(any(), any(), any());
    verify(repo, never()).markDeliveryRetry(any(), any(), any());
  }

  /**
   * An out-of-contract sender fault must advance the counter exactly like a declared one. A sender
   * that throws an NPE and left {@code attempts} untouched would be re-sent forever.
   */
  @Test
  void anUndeclaredRuntimeFaultCountsAsAnAttempt() {
    queueOnePending(0);
    service(
            m -> {
              throw new IllegalStateException("boom");
            },
            5)
        .dispatchPendingEmail(100);

    verify(repo).markDeliveryRetry(eq(DELIVERY), eq("boom"), any());
  }

  @Test
  void aLongProviderMessageIsTruncatedBeforeItReachesTheColumn() {
    queueOnePending(0);
    service(failing("x".repeat(900)), 5).dispatchPendingEmail(100);

    verify(repo).markDeliveryRetry(eq(DELIVERY), eq("x".repeat(500)), any());
  }

  // Claiming

  @Test
  void aRowAnotherTickAlreadyClaimedIsSkipped_andNeverSent() {
    when(repo.findPendingDeliveryIds(NotificationChannel.EMAIL, 100)).thenReturn(List.of(DELIVERY));
    when(repo.claimForSend(eq(DELIVERY), any())).thenReturn(Optional.empty());
    EmailSender sender = mock(EmailSender.class);

    NotificationService.DeliverySummary summary = service(sender, 5).dispatchPendingEmail(100);

    assertEquals(1, summary.skipped());
    verify(sender, never()).send(any());
  }

  /**
   * A missing subtype row is a producer bug: terminal at claim time, never handed to a provider.
   */
  @Test
  void aClaimWithNoContentFailsTerminallyWithoutSending() {
    when(repo.findPendingDeliveryIds(NotificationChannel.EMAIL, 100)).thenReturn(List.of(DELIVERY));
    when(repo.claimForSend(eq(DELIVERY), any())).thenReturn(Optional.of(pending(0)));
    when(repo.findEmailDeliveryContent(DELIVERY)).thenReturn(Optional.empty());
    EmailSender sender = mock(EmailSender.class);

    NotificationService.DeliverySummary summary = service(sender, 5).dispatchPendingEmail(100);

    assertEquals(1, summary.failed());
    verify(sender, never()).send(any());
    verify(repo).markDeliveryFailed(eq(DELIVERY), eq("missing email subtype row"), any());
  }

  // The reaper

  @Test
  void aStrandedClaimBelowTheBudgetGoesBackToTheQueue() {
    when(repo.findStrandedSendingIds(any(), eq(100))).thenReturn(List.of(DELIVERY));
    when(repo.findDeliveryById(DELIVERY))
        .thenReturn(Optional.of(delivery(DeliveryStatus.SENDING, 0)));

    NotificationService.DeliverySummary summary = service(m -> {}, 5).reapStrandedEmail(120, 100);

    assertEquals(1, summary.retried());
    verify(repo).markDeliveryRetry(eq(DELIVERY), any(), any());
  }

  @Test
  void aStrandedClaimAtTheBudgetFailsTerminally() {
    when(repo.findStrandedSendingIds(any(), eq(100))).thenReturn(List.of(DELIVERY));
    when(repo.findDeliveryById(DELIVERY))
        .thenReturn(Optional.of(delivery(DeliveryStatus.SENDING, 4)));

    NotificationService.DeliverySummary summary = service(m -> {}, 5).reapStrandedEmail(120, 100);

    assertEquals(1, summary.failed());
    verify(repo).markDeliveryFailed(eq(DELIVERY), any(), any());
  }

  /** A row that settled between the reaper's read and its write is left entirely alone. */
  @Test
  void aRowThatSettledUnderTheReaperIsUntouched() {
    when(repo.findStrandedSendingIds(any(), eq(100))).thenReturn(List.of(DELIVERY));
    when(repo.findDeliveryById(DELIVERY)).thenReturn(Optional.of(delivery(DeliveryStatus.SENT, 1)));

    NotificationService.DeliverySummary summary = service(m -> {}, 5).reapStrandedEmail(120, 100);

    assertEquals(1, summary.skipped());
    verify(repo, never()).markDeliveryRetry(any(), any(), any());
    verify(repo, never()).markDeliveryFailed(any(), any(), any());
  }

  private static EmailSender failing(String message) {
    return new EmailSender() {
      @Override
      public void send(EmailMessage m) {
        throw new EmailException(message);
      }
    };
  }
}
