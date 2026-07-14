package com.loai.inventory.service.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A no-transmit {@link EmailSender} used when SMTP credentials are absent (dev/CI). It logs the
 * message and returns normally, so the delivery sweeper marks the delivery {@code SENT} — the app
 * boots and the pipeline runs end-to-end without a real mailbox. Never throws.
 *
 * <p>It logs the full HTML body, not just the subject, so a dev can read what would have been sent
 * — notably the portal login code, which lives in the body (the story's "visible in {@code
 * LoggingEmailSender}" criterion). Dev-only, so logging the content is intended, not a leak.
 */
public final class LoggingEmailSender implements EmailSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

  @Override
  public void send(EmailMessage message) {
    log.info(
        "[email:noop] would send to={} subject=\"{}\" (SMTP not configured)\n{}",
        message.to(),
        message.subject(),
        message.html());
  }
}
