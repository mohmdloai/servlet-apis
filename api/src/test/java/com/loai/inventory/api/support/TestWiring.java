package com.loai.inventory.api.support;

import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.repository.CustomerMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationPreferenceRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.email.EmailGate;
import com.loai.inventory.service.email.EmailSender;
import com.loai.inventory.service.email.LoggingEmailSender;
import com.loai.inventory.service.email.MxResolver;
import java.time.Duration;
import java.util.Set;
import org.jooq.DSLContext;

/**
 * Shared IT wiring for the notification collaborators that {@code PaymentService} (and {@code
 * SalesOrderService}) require since the ORDER_PAID slice — real services against the real
 * repositories, a {@link LoggingEmailSender} unless the test captures sends itself.
 */
public final class TestWiring {

  private TestWiring() {}

  /**
   * One shared {@link ObjectStorage} for the whole test JVM. Presigning is an offline signature
   * computation — no connection, no bucket, nothing to fail — so the ITs that merely have to
   * <em>construct</em> a service which takes one do not each need their own instance wired through
   * {@code @BeforeAll}/{@code @AfterAll}. Tests that actually assert on a presigned URL can use
   * this too; the signature is real.
   */
  private static final ObjectStorage SHARED_STORAGE = ObjectStorageFactory.build();

  public static ObjectStorage storage() {
    return SHARED_STORAGE;
  }

  /** A pass-everything {@link EmailGate} (story 87) for ITs that aren't about email quality. */
  public static EmailGate permissiveEmailGate() {
    return new EmailGate(Set.of(), domain -> MxResolver.MxResult.UNKNOWN, false);
  }

  public static MagicLinkService magicLinkService(DSLContext dsl) {
    return new MagicLinkService(
        dsl,
        new CustomerMagicTokenRepositoryFactoryImpl(),
        new OrgRepositoryFactoryImpl(),
        new CustomerRepositoryFactoryImpl(),
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
        new OrgRepositoryFactoryImpl(),
        sender,
        magicLinkService(dsl),
        NotificationService.DEFAULT_EMAIL_MAX_ATTEMPTS);
  }
}
