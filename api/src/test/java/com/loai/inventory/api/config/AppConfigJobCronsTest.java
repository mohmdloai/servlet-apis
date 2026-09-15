package com.loai.inventory.api.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The recurring jobs the app registers are the ones the overview judges — one resolution, two
 * readers ({@code AppConfig.resolveJobCrons}). A job scheduled but missing here would show an
 * operator a silently empty panel; one here but never scheduled would read UNKNOWN forever.
 */
class AppConfigJobCronsTest {

  @Test
  void theAutoCloseJobIsRegisteredWithItsDefaultCron_besideTheOtherThree() {
    Map<String, String> crons = AppConfig.resolveJobCrons();
    assertTrue(crons.containsKey(AppConfig.JOB_ORDER_TTL_SWEEPER));
    assertTrue(crons.containsKey(AppConfig.JOB_NOTIFICATION_DELIVERY_SWEEPER));
    assertTrue(crons.containsKey(AppConfig.JOB_UNVERIFIED_ACCOUNT_PURGE));
    assertEquals("support-ticket-auto-close", AppConfig.JOB_SUPPORT_TICKET_AUTO_CLOSE);
    String cron = crons.get(AppConfig.JOB_SUPPORT_TICKET_AUTO_CLOSE);
    if (System.getenv("SUPPORT_AUTO_CLOSE_INTERVAL") == null) {
      assertEquals("0 15 * * * *", cron, "hourly, off the other sweepers' minute");
    } else {
      assertEquals(System.getenv("SUPPORT_AUTO_CLOSE_INTERVAL"), cron);
    }
  }

  @Test
  void theLedgerPosterIsRegisteredWithItsDefaultCron() {
    Map<String, String> crons = AppConfig.resolveJobCrons();
    assertEquals("ledger-poster", AppConfig.JOB_LEDGER_POSTER);
    String cron = crons.get(AppConfig.JOB_LEDGER_POSTER);
    if (System.getenv("LEDGER_POSTER_INTERVAL") == null) {
      assertEquals("30 */5 * * * *", cron, "every five minutes, off the other sweepers' second");
    } else {
      assertEquals(System.getenv("LEDGER_POSTER_INTERVAL"), cron);
    }
  }
}
