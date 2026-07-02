package com.loai.inventory.service.email;

/**
 * Hands a rendered message to the mail provider. Implementations: {@link SmtpEmailSender} (Gmail
 * SMTP in prod) and {@link LoggingEmailSender} (dev/CI fallback when SMTP credentials are absent).
 * Called only by the delivery sweeper, after the business txn has committed — never inside it.
 */
public interface EmailSender {
  /** Send one message, or throw {@link EmailException} if the provider rejects/errors. */
  void send(EmailMessage message) throws EmailException;
}
