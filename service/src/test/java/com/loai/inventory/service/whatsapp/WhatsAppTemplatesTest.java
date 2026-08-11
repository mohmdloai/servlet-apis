package com.loai.inventory.service.whatsapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.model.NotificationType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The template invocations and the Cloud API payload they turn into. */
class WhatsAppTemplatesTest {

  @Test
  void orderShipped_namesTheApprovedTemplateAndTheOrder() {
    WhatsAppTemplates.Spec spec =
        WhatsAppTemplates.specFor(
            NotificationType.ORDER_SHIPPED, Map.of("order_number", "SO-2026-00041"), "en");

    assertEquals("order_shipped", spec.name());
    assertEquals("en", spec.language());
    assertEquals(List.of("SO-2026-00041"), spec.params());
  }

  @Test
  void theLanguageIsPartOfTheInvocation_becauseMetaApprovesPerLanguage() {
    Map<String, Object> payload = Map.of("order_number", "SO-1");
    assertEquals(
        "ar", WhatsAppTemplates.specFor(NotificationType.ORDER_SHIPPED, payload, "ar").language());
    assertEquals(
        "en", WhatsAppTemplates.specFor(NotificationType.ORDER_SHIPPED, payload, "en").language());
    // Same template NAME in both: one artefact, approved twice.
    assertEquals(
        WhatsAppTemplates.specFor(NotificationType.ORDER_SHIPPED, payload, "ar").name(),
        WhatsAppTemplates.specFor(NotificationType.ORDER_SHIPPED, payload, "en").name());
  }

  @Test
  void paymentNeedsAttention_carriesBothMoneyValuesWithTheirCurrency() {
    WhatsAppTemplates.Spec spec =
        WhatsAppTemplates.specFor(
            NotificationType.PAYMENT_NEEDS_ATTENTION,
            Map.of(
                "order_number", "SO-1",
                "amount", "400.00",
                "outstanding", "600.00",
                "currency", "EGP"),
            "en");

    // Three parameters, in template order — a mismatch with the approved body is a terminal 4xx.
    assertEquals(List.of("SO-1", "400.00 EGP", "600.00 EGP"), spec.params());
  }

  @Test
  void marketingShapedTypesHaveNoTemplate_soTheyGetNoWhatsAppLeg() {
    // Meta would classify these as marketing, and this epic does not send paid marketing.
    assertNull(WhatsAppTemplates.specFor(NotificationType.REVIEW_REQUESTED, Map.of(), "en"));
    assertNull(WhatsAppTemplates.specFor(NotificationType.COMMENT_REPLIED, Map.of(), "en"));
  }

  @ParameterizedTest
  @EnumSource(NotificationType.class)
  void everyTypeIsAnExplicitDecision_eitherATemplateOrADeliberateNull(NotificationType type) {
    // The switch is exhaustive, so this cannot silently miss a future type: adding one without
    // deciding here is a compile error, and deciding "no template" is a visible null.
    WhatsAppTemplates.Spec spec =
        WhatsAppTemplates.specFor(type, Map.of("order_number", "SO-1"), "en");
    if (spec != null) {
      assertNotNull(spec.name());
      assertTrue(spec.params().stream().noneMatch(p -> p == null), type + " passes a null param");
    }
  }

  @Test
  void theCloudApiPayloadIsAWellFormedTemplateMessage() {
    String body =
        CloudApiWhatsAppSender.body(
            new WhatsAppMessage("+201012345678", "order_shipped", "ar", List.of("SO-1")));

    assertTrue(body.contains("\"messaging_product\":\"whatsapp\""), body);
    assertTrue(body.contains("\"type\":\"template\""), body);
    assertTrue(body.contains("\"name\":\"order_shipped\""), body);
    assertTrue(body.contains("\"code\":\"ar\""), body);
    assertTrue(body.contains("\"to\":\"+201012345678\""), body);
    assertTrue(body.contains("\"text\":\"SO-1\""), body);
  }

  @Test
  void aParameterlessTemplateOmitsTheComponentsBlockEntirely() {
    // Meta rejects an empty parameters array on a body component; omitting the block is correct.
    String body = CloudApiWhatsAppSender.body(new WhatsAppMessage("+201", "t", "en", List.of()));
    assertTrue(!body.contains("components"), body);
  }
}
