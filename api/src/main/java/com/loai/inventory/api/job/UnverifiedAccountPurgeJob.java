package com.loai.inventory.api.job;

import com.loai.inventory.service.auth.AccountService;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recurring JobRunr job that garbage-collects never-verified self-registered accounts (story 88) —
 * same design as {@link OrderTtlSweeperJob}. Register spam yields nothing durable: an account whose
 * verification link was never clicked (and whose token has expired — a live token shields it) is
 * deleted after the grace window, along with the empty org its owner never logged into.
 */
public final class UnverifiedAccountPurgeJob {

  private static final Logger log = LoggerFactory.getLogger(UnverifiedAccountPurgeJob.class);

  private final AccountService accountService;
  private final Duration graceWindow;
  private final int batchLimit;

  public UnverifiedAccountPurgeJob(
      AccountService accountService, Duration graceWindow, int batchLimit) {
    this.accountService = accountService;
    this.graceWindow = graceWindow;
    this.batchLimit = batchLimit;
  }

  @Job(name = "unverified-account-purge")
  public void run() {
    int purged =
        accountService.purgeUnverified(OffsetDateTime.now().minus(graceWindow), batchLimit);
    if (purged > 0) {
      log.info("Unverified-account purge tick: {} deleted", purged);
    }
  }
}
