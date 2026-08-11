package com.loai.inventory.service.whatsapp;

import com.loai.inventory.common.crypto.SecretBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the application's {@link WhatsAppSender}. Mirrors {@code EmailSenderFactory}, including
 * its most useful property: with nothing configured the app still boots and the whole notification
 * pipeline still runs, against {@link LoggingWhatsAppSender}.
 *
 * <p>The switch is the <b>platform</b> key {@code WHATSAPP_TOKEN_KEY}, not any merchant credential
 * — per-merchant tokens live in {@code org_whatsapp_config} and arrive per message. Without that
 * key no token could have been stored (the connect endpoint refuses) and none could be decrypted,
 * so real transmission is impossible by construction and the logging sender is the honest choice.
 */
public final class WhatsAppSenderFactory {

  private static final Logger log = LoggerFactory.getLogger(WhatsAppSenderFactory.class);

  private WhatsAppSenderFactory() {}

  public static WhatsAppSender build(SecretBox secretBox) {
    if (secretBox == null || !secretBox.isConfigured()) {
      log.warn(
          "WHATSAPP_TOKEN_KEY not set — using LoggingWhatsAppSender (no real WhatsApp is sent)");
      return new LoggingWhatsAppSender();
    }
    log.info(
        "Initialising WhatsApp Cloud API sender (Graph {})", CloudApiWhatsAppSender.GRAPH_VERSION);
    return new CloudApiWhatsAppSender(secretBox);
  }
}
