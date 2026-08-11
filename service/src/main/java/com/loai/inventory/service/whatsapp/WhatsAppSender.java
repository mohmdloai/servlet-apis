package com.loai.inventory.service.whatsapp;

import com.loai.inventory.domain.model.OrgWhatsAppConfig;

/**
 * Hands a rendered template invocation to WhatsApp. The exact {@code EmailSender} shape, with one
 * addition: the sending identity is <b>per message</b>, because the owner decision is a
 * per-merchant WABA — there is no single platform sender to configure once at construction.
 *
 * <p>Implementations: {@link CloudApiWhatsAppSender} (Meta Cloud API) and {@link
 * LoggingWhatsAppSender} (dev/CI, no provider account needed — the same property that lets the
 * whole notification pipeline run without a mailbox).
 *
 * <p>Called only by the delivery sweeper, after the business txn has committed — never inside it.
 */
public interface WhatsAppSender {

  /**
   * Send one message as {@code config}'s merchant.
   *
   * @return the provider's message id ({@code wamid…}) when it returns one, else null — stored for
   *     reconciling a future delivery webhook against this row.
   * @throws WhatsAppException on any provider rejection or transport fault
   */
  String send(OrgWhatsAppConfig config, WhatsAppMessage message) throws WhatsAppException;
}
