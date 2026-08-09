package com.loai.inventory.service.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.mail.Session;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D4 — Jakarta Mail waits forever by default. These pin the three bounded timeouts in {@code
 * mail.properties} <em>and</em> that a {@link Session} built the way {@link EmailSenderFactory}
 * builds it actually carries them, so a hung SMTP peer fails fast into the existing RETRIED/FAILED
 * accounting instead of pinning the sender.
 */
class SmtpTimeoutConfigTest {

  @Test
  @DisplayName("the session the factory builds carries connect/read/write timeouts")
  void timeoutsReachTheSession() {
    Properties props = EmailSenderFactory.loadMailProperties();
    Session session = Session.getInstance(props);

    assertEquals("5000", session.getProperty("mail.smtp.connectiontimeout"), "connect timeout");
    assertEquals("10000", session.getProperty("mail.smtp.timeout"), "read timeout");
    assertEquals("10000", session.getProperty("mail.smtp.writetimeout"), "write timeout");
  }

  @Test
  @DisplayName("every timeout is a positive number of milliseconds")
  void timeoutsAreBounded() {
    Properties props = EmailSenderFactory.loadMailProperties();
    for (String key :
        new String[] {
          "mail.smtp.connectiontimeout", "mail.smtp.timeout", "mail.smtp.writetimeout"
        }) {
      String raw = props.getProperty(key);
      assertTrue(
          raw != null && !raw.isBlank(), key + " must be set — the default is 'wait forever'");
      assertTrue(Integer.parseInt(raw.trim()) > 0, key + " must be a positive millisecond value");
    }
  }
}
