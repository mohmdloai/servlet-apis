package com.loai.inventory.api.job;

import com.loai.inventory.service.NotificationService;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recurring JobRunr job that drains PENDING notification deliveries — the same design as {@link
 * OrderTtlSweeperJob}. The producer only writes PENDING rows inside its business txn (no enqueue),
 * so deliveries survive a crash and are always picked up here; this avoids introducing an
 * after-commit enqueue hook the codebase does not otherwise have.
 *
 * <p>The tick also <b>reaps stranded email claims</b> before draining. Since the SMTP send moved
 * outside the delivery transaction (D4 follow-up), a worker that dies mid-send leaves its row
 * SENDING — a state the PENDING drain never looks at, so without this pass it would be a permanent
 * silent loss. Reaping first means a stranded row rejoins the queue and can go out on the same
 * tick.
 */
public final class NotificationDeliverySweeperJob {

  private static final Logger log = LoggerFactory.getLogger(NotificationDeliverySweeperJob.class);

  /**
   * How long an email claim may be outstanding before a worker is presumed dead.
   *
   * <p>It must comfortably exceed the worst-case send, or a live send is reaped underneath itself
   * and the message goes out twice. The SMTP timeouts bound that at ~25 s (5 s connect + 10 s read
   * + 10 s write, D4), so 120 s is roughly a 5x margin — generous, because the cost of being late
   * is a delayed retry while the cost of being early is a duplicate email.
   */
  static final long EMAIL_CLAIM_LEASE_SECONDS = 120;

  private final NotificationService notificationService;
  private final int batchLimit;

  public NotificationDeliverySweeperJob(NotificationService notificationService, int batchLimit) {
    this.notificationService = notificationService;
    this.batchLimit = batchLimit;
  }

  @Job(name = "notification-delivery-sweeper")
  public void run() {
    // Reap first: a stranded claim returns to PENDING and can then go out on this same tick.
    NotificationService.DeliverySummary reaped =
        notificationService.reapStrandedEmail(EMAIL_CLAIM_LEASE_SECONDS, batchLimit);
    NotificationService.DeliverySummary inApp =
        notificationService.dispatchPendingInApp(batchLimit);
    NotificationService.DeliverySummary email =
        notificationService.dispatchPendingEmail(batchLimit);
    if (reaped.picked() > 0) {
      log.warn("Notification delivery tick reaped stranded email claims: {}", reaped);
    }
    if (inApp.picked() > 0) {
      log.info("Notification delivery tick (in_app): {}", inApp);
    }
    if (email.picked() > 0) {
      log.info("Notification delivery tick (email): {}", email);
    }
  }
}
