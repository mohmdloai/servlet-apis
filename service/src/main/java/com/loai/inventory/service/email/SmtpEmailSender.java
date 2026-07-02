package com.loai.inventory.service.email;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link EmailSender} over SMTP via Jakarta Mail (Eclipse Angus). Built for Gmail SMTP (STARTTLS on
 * 587, App-Password auth) but provider-agnostic — the {@link Session} is configured from {@code
 * mail.properties} + env credentials by {@link EmailSenderFactory}. The {@code Session} is
 * thread-safe and reused; each send builds its own {@link MimeMessage}.
 */
public final class SmtpEmailSender implements EmailSender {

  private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

  private final Session session;
  private final String fromAddress;

  public SmtpEmailSender(Session session, String fromAddress) {
    this.session = session;
    this.fromAddress = fromAddress;
  }

  @Override
  public void send(EmailMessage message) throws EmailException {
    // Defence in depth: never let a comma list / injected header reach the wire, even if a bad
    // address slipped past input validation (legacy rows, other producers). One address only.
    if (!EmailAddresses.isSingleValid(message.to())) {
      throw new EmailException("refusing to send to a non-single/invalid address", null);
    }
    try {
      MimeMessage mime = new MimeMessage(session);
      mime.setFrom(new InternetAddress(fromAddress));
      mime.setRecipients(Message.RecipientType.TO, InternetAddress.parse(message.to()));
      mime.setSubject(message.subject(), "UTF-8");
      mime.setContent(message.html(), "text/html; charset=utf-8");
      Transport.send(mime);
      log.debug("SMTP send ok to={} subject={}", message.to(), message.subject());
    } catch (MessagingException e) {
      throw new EmailException("SMTP send failed to " + message.to(), e);
    }
  }
}
