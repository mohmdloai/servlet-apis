package com.loai.inventory.api.job;

import com.loai.inventory.service.NotificationService;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recurring JobRunr job that drains PENDING in-app notification deliveries — the same design as
 * {@link OrderTtlSweeperJob}. The producer only writes PENDING rows inside its business txn (no
 * enqueue), so deliveries survive a crash and are always picked up here; this avoids introducing an
 * after-commit enqueue hook the codebase does not otherwise have.
 */
public final class NotificationDeliverySweeperJob {

  private static final Logger log = LoggerFactory.getLogger(NotificationDeliverySweeperJob.class);

  private final NotificationService notificationService;
  private final int batchLimit;

  public NotificationDeliverySweeperJob(NotificationService notificationService, int batchLimit) {
    this.notificationService = notificationService;
    this.batchLimit = batchLimit;
  }

  @Job(name = "notification-delivery-sweeper")
  public void run() {
    NotificationService.DeliverySummary inApp =
        notificationService.dispatchPendingInApp(batchLimit);
    NotificationService.DeliverySummary email =
        notificationService.dispatchPendingEmail(batchLimit);
    if (inApp.picked() > 0) {
      log.info("Notification delivery tick (in_app): {}", inApp);
    }
    if (email.picked() > 0) {
      log.info("Notification delivery tick (email): {}", email);
    }
  }
}
