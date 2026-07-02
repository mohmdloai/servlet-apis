package com.loai.inventory.service.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A no-transmit {@link EmailSender} used when SMTP credentials are absent (dev/CI). It logs the
 * message and returns normally, so the delivery sweeper marks the delivery {@code SENT} — the app
 * boots and the pipeline runs end-to-end without a real mailbox. Never throws.
 */
public final class LoggingEmailSender implements EmailSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

  @Override
  public void send(EmailMessage message) {
    log.info(
        "[email:noop] would send to={} subject=\"{}\" (SMTP credentials not configured)",
        message.to(),
        message.subject());
  }
}
