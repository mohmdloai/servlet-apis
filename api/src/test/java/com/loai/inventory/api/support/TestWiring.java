package com.loai.inventory.api.support;

import com.loai.inventory.repository.CustomerMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationPreferenceRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.email.EmailSender;
import com.loai.inventory.service.email.LoggingEmailSender;
import java.time.Duration;
import org.jooq.DSLContext;

/**
 * Shared IT wiring for the notification collaborators that {@code PaymentService} (and {@code
 * SalesOrderService}) require since the ORDER_PAID slice — real services against the real
 * repositories, a {@link LoggingEmailSender} unless the test captures sends itself.
 */
public final class TestWiring {

  private TestWiring() {}

  public static MagicLinkService magicLinkService(DSLContext dsl) {
    return new MagicLinkService(
        dsl,
        new CustomerMagicTokenRepositoryFactoryImpl(),
        "http://localhost:8080",
        Duration.ofDays(30));
  }

  public static NotificationService notificationService(DSLContext dsl) {
    return notificationService(dsl, new LoggingEmailSender());
  }

  public static NotificationService notificationService(DSLContext dsl, EmailSender sender) {
    return new NotificationService(
        dsl,
        new NotificationRepositoryFactoryImpl(),
        new UserRepositoryFactoryImpl(),
        new CustomerRepositoryFactoryImpl(),
        new NotificationPreferenceRepositoryFactoryImpl(),
        sender,
        magicLinkService(dsl),
        NotificationService.DEFAULT_EMAIL_MAX_ATTEMPTS);
  }
}
