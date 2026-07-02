package com.loai.inventory.service.email;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/**
 * One source of truth for "is this exactly one address we may send to". Guards against the mail
 * relay/amplification vector: {@code InternetAddress.parse} splits a comma list into multiple
 * recipients, so an unvalidated {@code to} lets one order fan the org's SMTP identity out to
 * arbitrary mailboxes. Also rejects CR/LF and control characters (SMTP header injection).
 */
public final class EmailAddresses {

  private EmailAddresses() {}

  /**
   * True iff {@code raw} is a single, syntactically valid, bare email address — no comma list, no
   * {@code "Name <addr>"} display form, no control characters.
   */
  public static boolean isSingleValid(String raw) {
    if (raw == null || raw.isBlank()) {
      return false;
    }
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == '\r' || c == '\n' || c < 0x20) {
        return false; // header-injection / control chars
      }
    }
    try {
      InternetAddress[] parsed = InternetAddress.parse(raw, /* strict= */ true);
      if (parsed.length != 1) {
        return false; // a comma list expands to >1 recipient — reject
      }
      parsed[0].validate();
      // Reject "Display Name <addr>" forms — a stored customer email must be a bare address.
      return parsed[0].getPersonal() == null && parsed[0].getAddress().equalsIgnoreCase(raw.trim());
    } catch (AddressException e) {
      return false;
    }
  }
}
