package com.loai.inventory.api.job;

import com.loai.inventory.service.LedgerService;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The recurring ledger poster (stories/general_ledger.md): catches every org's journal up to its
 * source rows, one transaction per org. Reads already do this on demand; the job keeps the ledger
 * current between reads so the health screen and any external consumer see a journal no older than
 * one tick, and it is the path that backfills a fresh deployment's history without anyone opening
 * the page. Same gate as the other sweepers ({@code ORDER_SWEEPER_BACKGROUND_ENABLED}).
 */
public final class LedgerPosterJob {

  private static final Logger log = LoggerFactory.getLogger(LedgerPosterJob.class);

  private final LedgerService ledger;
  private final int orgPageSize;

  public LedgerPosterJob(LedgerService ledger, int orgPageSize) {
    this.ledger = ledger;
    this.orgPageSize = orgPageSize;
  }

  @Job(name = "ledger-poster")
  public void run() {
    int posted = ledger.sweepAll(orgPageSize);
    if (posted > 0) {
      log.info("Ledger poster tick: {} entries posted", posted);
    }
  }
}
