package com.loai.inventory.api.job;

import com.loai.inventory.service.OrderExpiryService;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The recurring sweeper job body. JobRunr's {@code BackgroundJobServer} invokes {@link #run()} on
 * the schedule registered in {@code AppConfig} (default: every 30 seconds) and handles retries on
 * transient failure. Resolved at execution time via the custom {@code JobActivator} so this
 * instance keeps its injected {@link OrderExpiryService}.
 */
public final class OrderTtlSweeperJob {

  private static final Logger log = LoggerFactory.getLogger(OrderTtlSweeperJob.class);

  private final OrderExpiryService expiryService;
  private final int batchLimit;

  public OrderTtlSweeperJob(OrderExpiryService expiryService, int batchLimit) {
    this.expiryService = expiryService;
    this.batchLimit = batchLimit;
  }

  @Job(name = "order-ttl-sweeper")
  public void run() {
    OrderExpiryService.Summary summary = expiryService.sweep(batchLimit);
    log.info("Sweeper tick: {}", summary);
  }
}
