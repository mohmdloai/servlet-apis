package com.loai.inventory.service.auth;

import com.loai.inventory.service.email.EmailAddresses;
import com.loai.inventory.service.email.EmailException;
import com.loai.inventory.service.email.EmailMessage;
import com.loai.inventory.service.email.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends the transactional auth emails (password-reset link, invite/activation link) directly
 * through the {@link EmailSender}, bypassing the customer-scoped, opt-out notification pipeline —
 * an auth mail targets an {@code app_user}, must never carry an unsubscribe link, and must not be
 * suppressible by preferences.
 *
 * <p><b>Sending is best-effort by default</b>: a forgot-password caller must get the same opaque
 * response whether or not delivery succeeded (no account enumeration), so {@link
 * #sendPasswordReset}, {@link #sendInvite} and {@link #sendVerifyEmail} log a failure and return.
 * {@link #sendVerifyEmailOrThrow} is the one exception, for the one caller that is authenticated
 * and owes the operator the truth — see its Javadoc. Both go through the same template and the same
 * send site, so the two paths cannot drift into sending different mail.
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

  /** The registration verification link (story 88) — proves the inbox and signs the user in. */
  public void sendVerifyEmail(String to, String url) {
    send(to, VERIFY_SUBJECT, verifyEmailHtml(url));
  }

  /**
   * The same verification mail as {@link #sendVerifyEmail} — same subject, same body, same {@link
   * EmailSender} — except that a delivery failure <b>propagates</b> instead of being logged away.
   *
   * <p><b>One template, two callers</b>, which is the only honest way to have both behaviours. The
   * swallowing variant exists because {@code POST /api/auth/resend-verification} is anonymous: a
   * 5xx on a real address is an account-enumeration oracle, so every caller must get the same
   * opaque answer. That reason does not exist for {@code POST
   * /api/admin/users/{id}/resend-verification}, whose caller is an authenticated ADMIN already
   * looking at the address in the console — there is nothing left to protect, and swallowing there
   * would report a rescue link as delivered when it never left the building. The token is minted
   * and committed before this call, so the failure is real and the operator's retry is the remedy.
   *
   * @throws com.loai.inventory.service.email.EmailException if the recipient is unusable or the
   *     provider rejects/errors
   */
  public void sendVerifyEmailOrThrow(String to, String url) {
    sendOrThrow(to, VERIFY_SUBJECT, verifyEmailHtml(url));
  }

  private static final String VERIFY_SUBJECT = "Confirm your email address";

  private static String verifyEmailHtml(String url) {
    return "<p>Thanks for signing up! Confirm your email address to activate your account.</p>"
        + "<p><a href=\""
        + url
        + "\">Confirm my email</a></p>"
        + "<p>If you did not create this account, you can ignore this email. The link expires"
        + " in 48 hours.</p>";
  }

  private void send(String to, String subject, String html) {
    try {
      sendOrThrow(to, subject, html);
    } catch (RuntimeException e) {
      // EmailException is unchecked; best-effort — never surface a delivery failure to the caller
      // (enumeration / UX).
      log.warn("Failed to send auth email (subject={})", subject, e);
    }
  }

  /** The one send site. {@link #send} is this plus a swallow. */
  private void sendOrThrow(String to, String subject, String html) {
    if (!EmailAddresses.isSingleValid(to)) {
      throw new EmailException("recipient is not a single valid address");
    }
    emailSender.send(new EmailMessage(to, subject, html));
  }

  private static String escapeHtml(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
