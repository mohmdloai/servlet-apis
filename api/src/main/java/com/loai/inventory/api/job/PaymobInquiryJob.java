package com.loai.inventory.api.job;

import com.loai.inventory.service.PaymobInquiryService;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The recurring Paymob inquiry poller ({@code stories/paymob_card_reliability.md}). JobRunr invokes
 * {@link #run()} on the schedule registered in {@code AppConfig} (default: every two minutes, gated
 * with the other sweepers by {@code ORDER_SWEEPER_BACKGROUND_ENABLED}); tests drive {@link
 * PaymobInquiryService#sweep} directly.
 */
public final class PaymobInquiryJob {

  private static final Logger log = LoggerFactory.getLogger(PaymobInquiryJob.class);

  private final PaymobInquiryService inquiryService;
  private final int batchLimit;

  public PaymobInquiryJob(PaymobInquiryService inquiryService, int batchLimit) {
    this.inquiryService = inquiryService;
    this.batchLimit = batchLimit;
  }

  @Job(name = "paymob-inquiry")
  public void run() {
    PaymobInquiryService.Summary summary = inquiryService.sweep(batchLimit);
    log.info("Paymob inquiry tick: {}", summary);
  }
}
