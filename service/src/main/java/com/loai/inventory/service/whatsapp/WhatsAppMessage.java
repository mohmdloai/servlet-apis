package com.loai.inventory.service.whatsapp;

import java.util.List;

/**
 * One outbound WhatsApp message, as a <b>template invocation</b> rather than a body of text.
 *
 * <p>That shape is not a design preference — it is Meta's contract. Outside the 24-hour
 * customer-service window a business may only send a <em>pre-approved template</em>, identified by
 * name and language, with ordered positional parameters substituted into its body. So the rendered
 * sentence that goes in the email and the portal feed cannot be reused here; the same event has to
 * be expressed twice, once as prose and once as parameters. {@code WhatsAppTemplates} owns the
 * second form.
 *
 * @param toNumber E.164, from {@code customer.phone_e164} (V79)
 * @param templateName the approved template's name, e.g. {@code order_shipped}
 * @param languageCode the approved template's language — {@code ar} and {@code en} are two
 *     separately-approved artefacts of the same template name
 * @param bodyParams positional body parameters, in template order
 */
public record WhatsAppMessage(
    String toNumber, String templateName, String languageCode, List<String> bodyParams) {}
