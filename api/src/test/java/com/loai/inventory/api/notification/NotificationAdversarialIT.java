package com.loai.inventory.api.notification;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.model.Notification;
import com.loai.inventory.domain.model.NotificationRecipient;
import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.email.EmailAddresses;
import com.loai.inventory.service.email.EmailException;
import com.loai.inventory.service.email.EmailMessage;
import com.loai.inventory.service.email.EmailSender;
import com.loai.inventory.service.email.SmtpEmailSender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.mail.Session;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Adversarial empirical probes of the Phase-2 email channel. Each test drives the real service +
 * repository against a live Postgres and asserts observed behaviour, so the findings are
 * reproducible rather than argued. These encode the FIXED behaviour (recipient guard, bounded
 * retry, truthful metrics); fake {@link EmailSender}s stand in for SMTP.
 */
@Testcontainers
class NotificationAdversarialIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;

  @BeforeAll
  static void startInfra() {
    Flyway.configure()
        .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
        .schemas("inventorydb")
        .locations("classpath:db/migration")
        .load()
        .migrate();

    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(PG.getJdbcUrl());
    cfg.setUsername(PG.getUsername());
    cfg.setPassword(PG.getPassword());
    cfg.setMaximumPoolSize(8);
    cfg.setConnectionInitSql("SET search_path TO inventorydb");
    dataSource = new HikariDataSource(cfg);
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void freshSchema() {
    dsl.execute(
        "TRUNCATE notification_delivery_email, notification_delivery_in_app, notification_delivery,"
            + " notification, customer, org RESTART IDENTITY CASCADE");
  }

  // ── Fake senders ────────────────────────────────────────────────────────────

  /** Captures every message; never throws. */
  static final class CapturingSender implements EmailSender {
    final List<EmailMessage> captured = new CopyOnWriteArrayList<>();

    @Override
    public void send(EmailMessage m) {
      captured.add(m);
    }
  }

  /** Throws a non-{@link EmailException} RuntimeException — an out-of-contract sender fault. */
  static final class NpeSender implements EmailSender {
    int calls = 0;

    @Override
    public void send(EmailMessage m) {
      calls++;
      throw new IllegalStateException("kaboom (not an EmailException)");
    }
  }

  /** Blocks at a shared barrier before capturing, to force two ticks to overlap on the same row. */
  static final class BarrierSender implements EmailSender {
    final List<EmailMessage> captured = new CopyOnWriteArrayList<>();
    final CyclicBarrier barrier;

    BarrierSender(CyclicBarrier barrier) {
      this.barrier = barrier;
    }

    @Override
    public void send(EmailMessage m) {
      try {
        barrier.await();
      } catch (Exception ignored) {
        // barrier broken → just proceed; the DB row lock is the real guard under test
      }
      captured.add(m);
    }
  }

  // ── 1. Recipient injection / mail-relay amplification (FIXED) ────────────────

  /**
   * The address guard rejects anything that is not a single valid bare address, closing the
   * mail-relay/amplification vector: {@code InternetAddress.parse} would otherwise expand a comma
   * list into multiple recipients on the org's SMTP identity.
   */
  @Test
  void addressGuard_rejectsCommaListsAndInjection_acceptsSingle() {
    assertFalse(EmailAddresses.isSingleValid("victim@acme.test, attacker@evil.test"), "comma list");
    assertFalse(EmailAddresses.isSingleValid("a@x.test\r\nBcc: evil@x.test"), "CRLF injection");
    assertFalse(EmailAddresses.isSingleValid("Nadia <a@x.test>"), "display-name form");
    assertFalse(EmailAddresses.isSingleValid("not-an-email"), "malformed");
    assertFalse(EmailAddresses.isSingleValid("  "), "blank");
    assertTrue(EmailAddresses.isSingleValid("nadia@acme.test"), "single valid address");
  }

  /** The real SMTP sender refuses a multi-recipient {@code to} before touching the network. */
  @Test
  void smtpSender_refusesMultiRecipientBeforeSending() {
    SmtpEmailSender smtp =
        new SmtpEmailSender(Session.getInstance(new Properties()), "shop@acme.test");
    EmailException ex =
        assertThrows(
            EmailException.class,
            () ->
                smtp.send(
                    new EmailMessage(
                        "victim@acme.test, attacker@evil.test", "New order", "<p>hi</p>")));
    assertTrue(ex.getMessage().contains("non-single/invalid"), ex.getMessage());
  }

  // ── 2. Poison-delivery: an out-of-contract sender fault is bounded (FIXED) ────

  /**
   * A sender that throws a non-{@link EmailException} RuntimeException must still advance the
   * attempt counter, so it reaches terminal FAILED within the budget instead of being re-sent every
   * tick forever. With a budget of 1 it FAILs on the first tick and the provider is not hit again.
   */
  @Test
  void nonEmailExceptionSender_isBoundedAndReachesTerminalFailed() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "nadia@acme.test");
    NpeSender sender = new NpeSender();
    NotificationService service = service(sender, 1); // budget of 1

    UUID nid = produce(service, org, customer, "SO-POISON");
    UUID delivery = emailDeliveryId(nid);

    NotificationService.DeliverySummary t1 = service.dispatchPendingEmail(100);
    assertEquals(1, t1.failed(), "counted as a real (terminal) failure");
    assertEquals(0, t1.retried());
    assertEquals("FAILED", deliveryStatus(delivery), "bounded — terminal within budget");
    assertEquals(1, attempts(delivery), "the attempt was recorded");
    assertEquals("DISPATCHED", notificationStatus(nid));

    NotificationService.DeliverySummary t2 = service.dispatchPendingEmail(100);
    assertEquals(0, t2.picked(), "nothing left PENDING — no forever-retry");
    assertEquals(1, sender.calls, "provider was NOT hit again after terminal FAILED");
  }

  /** Control: a well-behaved EmailException reaches terminal FAILED with attempts bumped. */
  @Test
  void emailExceptionSender_isTerminalWithinBudget() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "nadia@acme.test");
    NotificationService service =
        service(
            m -> {
              throw new EmailException("provider said no", null);
            },
            1);

    UUID nid = produce(service, org, customer, "SO-CLEANFAIL");
    UUID delivery = emailDeliveryId(nid);
    service.dispatchPendingEmail(100);

    assertEquals("FAILED", deliveryStatus(delivery));
    assertEquals(1, attempts(delivery));
  }

  /**
   * Truthful metrics: a transient fault under budget is reported as {@code retried} (still PENDING,
   * to be re-attempted), NOT {@code failed}. Only the terminal tick reports {@code failed}. This is
   * what stops a re-attempted delivery inflating the failure count on every sweep.
   */
  @Test
  void transientFault_reportsRetriedNotFailed_untilBudgetExhausted() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "nadia@acme.test");
    NotificationService service =
        service(
            m -> {
              throw new EmailException("temporary glitch", null);
            },
            2); // budget of 2

    UUID nid = produce(service, org, customer, "SO-TRANSIENT");
    UUID delivery = emailDeliveryId(nid);

    NotificationService.DeliverySummary t1 = service.dispatchPendingEmail(100);
    assertEquals(1, t1.retried(), "under budget → retried");
    assertEquals(0, t1.failed(), "not yet a failure");
    assertEquals("PENDING", deliveryStatus(delivery));

    NotificationService.DeliverySummary t2 = service.dispatchPendingEmail(100);
    assertEquals(0, t2.retried());
    assertEquals(1, t2.failed(), "budget exhausted → now a real failure");
    assertEquals("FAILED", deliveryStatus(delivery));
  }

  // ── 3. Concurrency: two overlapping ticks must not double-send ───────────────

  /**
   * Two threads each run a full sweeper tick with a barrier-synchronised sender so both are
   * guaranteed to be mid-dispatch on the same PENDING row. Asserts the {@code FOR UPDATE} lock +
   * status recheck delivers exactly once.
   */
  @Test
  void concurrentTicks_sendExactlyOnce() throws Exception {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "nadia@acme.test");
    // Barrier of 2: both sender threads must arrive before either captures. If the lock serialised
    // them (correct), only ONE thread's txn ever calls send() — so the barrier would deadlock. Give
    // it a timeout inside send() by wrapping await in the BarrierSender (broken barrier → proceed).
    CyclicBarrier barrier = new CyclicBarrier(2);
    BarrierSender sender = new BarrierSender(barrier);
    NotificationService service = service(sender, 5);

    UUID nid = produce(service, org, customer, "SO-RACE");
    UUID delivery = emailDeliveryId(nid);

    Runnable tick = () -> service.dispatchPendingEmail(100);
    Thread a = new Thread(tick, "tick-A");
    Thread b = new Thread(tick, "tick-B");
    a.start();
    b.start();
    // The loser blocks on the row lock; the winner blocks on the barrier. Break the barrier after a
    // moment so the winner proceeds; the loser then wakes to a SENT row and skips.
    Thread.sleep(1500);
    barrier.reset();
    a.join(10_000);
    b.join(10_000);

    assertEquals("SENT", deliveryStatus(delivery));
    assertEquals(1, attempts(delivery), "sent exactly once");
    assertEquals(1, sender.captured.size(), "provider hit exactly once — no double-send");
    assertEquals("DISPATCHED", notificationStatus(nid));
  }

  // ── 4. Expiry boundary + blank-email producer fault ──────────────────────────

  /** A blank customer email makes the producer path throw inside the (business) txn. */
  @Test
  void blankCustomerEmail_throwsAtProduce() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "   "); // whitespace-only
    NotificationService service = service(new CapturingSender(), 5);

    assertThrows(IllegalStateException.class, () -> produce(service, org, customer, "SO-NOEMAIL"));
  }

  /** Two orders for one customer: each email is independently addressed — no cross-wiring. */
  @Test
  void twoOrders_produceTwoIndependentDeliveries() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "nadia@acme.test");
    CapturingSender sender = new CapturingSender();
    NotificationService service = service(sender, 5);

    UUID n1 = produce(service, org, customer, "SO-A");
    UUID n2 = produce(service, org, customer, "SO-B");
    assertNotEquals(n1, n2);

    service.dispatchPendingEmail(100);
    assertEquals(2, sender.captured.size());
    List<String> subjects = sender.captured.stream().map(EmailMessage::subject).sorted().toList();
    assertEquals(List.of("New order SO-A", "New order SO-B"), subjects);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private static NotificationService service(EmailSender sender, int maxAttempts) {
    return new NotificationService(
        dsl,
        new NotificationRepositoryFactoryImpl(),
        new UserRepositoryFactoryImpl(),
        new CustomerRepositoryFactoryImpl(),
        sender,
        maxAttempts);
  }

  private UUID produce(NotificationService service, UUID org, UUID customer, String orderNumber) {
    Notification n =
        service.notify(
            dsl,
            org,
            NotificationRecipient.customer(customer),
            NotificationType.ORDER_PLACED,
            Map.of("order_number", orderNumber),
            "sales_order",
            UUID.randomUUID(),
            "http://localhost:8080/api/public/orders/tok-" + orderNumber);
    return n.getId();
  }

  private UUID createOrg(String slug) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug + "-" + id)
        .execute();
    return id;
  }

  private UUID createCustomer(UUID org, String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, org)
        .set(CUSTOMER.NAME, "Nadia")
        .set(CUSTOMER.EMAIL, email)
        .execute();
    return id;
  }

  private UUID emailDeliveryId(UUID notificationId) {
    return dsl.select(NOTIFICATION_DELIVERY.ID)
        .from(NOTIFICATION_DELIVERY)
        .where(NOTIFICATION_DELIVERY.NOTIFICATION_ID.eq(notificationId))
        .and(NOTIFICATION_DELIVERY.CHANNEL.eq("email"))
        .fetchOne(NOTIFICATION_DELIVERY.ID);
  }

  private String deliveryStatus(UUID deliveryId) {
    return dsl.select(NOTIFICATION_DELIVERY.STATUS)
        .from(NOTIFICATION_DELIVERY)
        .where(NOTIFICATION_DELIVERY.ID.eq(deliveryId))
        .fetchOne(NOTIFICATION_DELIVERY.STATUS);
  }

  private String notificationStatus(UUID id) {
    return dsl.select(NOTIFICATION.STATUS)
        .from(NOTIFICATION)
        .where(NOTIFICATION.ID.eq(id))
        .fetchOne(NOTIFICATION.STATUS);
  }

  private int attempts(UUID deliveryId) {
    return dsl.select(NOTIFICATION_DELIVERY.ATTEMPTS)
        .from(NOTIFICATION_DELIVERY)
        .where(NOTIFICATION_DELIVERY.ID.eq(deliveryId))
        .fetchOne(NOTIFICATION_DELIVERY.ATTEMPTS);
  }
}
