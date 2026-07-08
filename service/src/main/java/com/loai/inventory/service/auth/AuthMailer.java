package com.loai.inventory.service.auth;

import com.loai.inventory.service.email.EmailAddresses;
import com.loai.inventory.service.email.EmailMessage;
import com.loai.inventory.service.email.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends the transactional auth emails (password-reset link, invite/activation link) directly
 * through the {@link EmailSender}, bypassing the customer-scoped, opt-out notification pipeline —
 * an auth mail targets an {@code app_user}, must never carry an unsubscribe link, and must not be
 * suppressible by preferences. Sending is best-effort and never throws: a forgot-password caller
 * must get the same opaque response whether or not delivery succeeded (no account enumeration).
 */
public class AuthMailer {

  private static final Logger log = LoggerFactory.getLogger(AuthMailer.class);

  private final EmailSender emailSender;

  public AuthMailer(EmailSender emailSender) {
    this.emailSender = emailSender;
  }

  public void sendPasswordReset(String to, String url) {
    send(
        to,
        "Reset your password",
        "<p>We received a request to reset your password.</p>"
            + "<p><a href=\""
            + url
            + "\">Reset your password</a></p>"
            + "<p>If you did not request this, you can ignore this email. The link expires"
            + " soon.</p>");
  }

  public void sendInvite(String to, String orgName, String url) {
    send(
        to,
        "You have been invited to " + orgName,
        "<p>An account has been created for you at <strong>"
            + escapeHtml(orgName)
            + "</strong>.</p>"
            + "<p><a href=\""
            + url
            + "\">Set your password to get started</a></p>"
            + "<p>The link expires soon.</p>");
  }

  private void send(String to, String subject, String html) {
    if (!EmailAddresses.isSingleValid(to)) {
      log.warn("Skipping auth email — recipient is not a single valid address");
      return;
    }
    try {
      emailSender.send(new EmailMessage(to, subject, html));
    } catch (RuntimeException e) {
      // EmailException is unchecked; best-effort — never surface a delivery failure to the caller
      // (enumeration / UX).
      log.warn("Failed to send auth email (subject={})", subject, e);
    }
  }

  private static String escapeHtml(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
