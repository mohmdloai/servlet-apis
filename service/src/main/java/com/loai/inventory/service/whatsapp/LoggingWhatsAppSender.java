package com.loai.inventory.service.whatsapp;

import com.loai.inventory.domain.model.OrgWhatsAppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev/CI fallback: logs what would have been sent and reports success, so the whole WhatsApp
 * pipeline — producer, subtype row, sweeper claim/send/settle — runs end to end with no Meta
 * account. Mirrors {@code LoggingEmailSender}.
 *
 * <p>Logs the template name and destination but <b>never the access token</b>, and truncates the
 * number: a dev log is still a log.
 */
public final class LoggingWhatsAppSender implements WhatsAppSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingWhatsAppSender.class);

  @Override
  public String send(OrgWhatsAppConfig config, WhatsAppMessage message) {
    log.info(
        "[whatsapp:dev] org={} template={}/{} to={} params={}",
        config.orgId(),
        message.templateName(),
        message.languageCode(),
        mask(message.toNumber()),
        message.bodyParams());
    return null; // no provider id in dev — the column stays null, which is honest
  }

  private static String mask(String number) {
    if (number == null || number.length() < 5) {
      return "***";
    }
    return number.substring(0, 3) + "***" + number.substring(number.length() - 2);
  }
}
