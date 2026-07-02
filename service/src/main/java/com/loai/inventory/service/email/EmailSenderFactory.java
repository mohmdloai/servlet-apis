package com.loai.inventory.service.email;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the application's {@link EmailSender} from {@code mail.properties} (committed, non-secret
 * connection config) plus the {@code SMTP_USERNAME} / {@code SMTP_PASSWORD} env vars (the Gmail App
 * Password — a secret, never committed). Mirrors {@code ObjectStorageFactory} / {@code
 * DataSourceFactory}.
 *
 * <p>When either credential is blank it returns a {@link LoggingEmailSender}, so the app boots and
 * the notification pipeline runs without a real mailbox (dev/CI). Real transmission needs both env
 * vars set.
 */
public final class EmailSenderFactory {

  private static final Logger log = LoggerFactory.getLogger(EmailSenderFactory.class);

  private EmailSenderFactory() {}

  public static EmailSender build() {
    String username = getenvOrNull("SMTP_USERNAME");
    String password = getenvOrNull("SMTP_PASSWORD");
    if (username == null || password == null) {
      log.warn(
          "SMTP_USERNAME/SMTP_PASSWORD not set — using LoggingEmailSender (no real email is sent)");
      return new LoggingEmailSender();
    }

    Properties props = loadMailProperties();
    // The mail.properties keys (mail.smtp.host/port/auth/starttls.enable) are exactly Jakarta
    // Mail's
    // Session keys, so the file feeds the Session directly.
    Session session =
        Session.getInstance(
            props,
            new Authenticator() {
              @Override
              protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
              }
            });

    String from = props.getProperty("mail.from");
    if (from == null || from.isBlank()) {
      from = username; // Gmail rewrites From to the authenticated account anyway.
    }
    log.info(
        "Initialising SMTP email sender → host={} from={}",
        props.getProperty("mail.smtp.host"),
        from);
    return new SmtpEmailSender(session, from);
  }

  private static Properties loadMailProperties() {
    Properties props = new Properties();
    try (InputStream is =
        EmailSenderFactory.class.getClassLoader().getResourceAsStream("mail.properties")) {
      if (is == null) {
        throw new RuntimeException("mail.properties not found on classpath");
      }
      props.load(is);
    } catch (Exception e) {
      throw new RuntimeException("Failed to load mail.properties", e);
    }
    return props;
  }

  private static String getenvOrNull(String key) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? null : v;
  }
}
