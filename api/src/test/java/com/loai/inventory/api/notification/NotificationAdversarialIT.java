package com.loai.inventory.api.notification;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
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

  // Fake senders

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

  // 1. Recipient injection / mail-relay amplification (FIXED)

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

  // 2. Poison-delivery: an out-of-contract sender fault is bounded (FIXED)

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
    // The P5 in_app feed leg is also pending — drain it before the parent can finalize.
    service.dispatchPendingInApp(100);
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

  // 3. Concurrency: two overlapping ticks must not double-send

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
    // The winner is blocked in the barrier inside send(). The loser must finish its tick at once
    // rather than queue behind the round-trip. What excludes it changed with the D4 follow-up —
    // the winner no longer holds a lock during send, so the loser is turned away by the row's
    // SENDING *state* instead of by SKIP LOCKED — and the observable guarantee is identical, which
    // is why this test did not have to change.
    Thread.sleep(1500);
    assertTrue(
        !a.isAlive() || !b.isAlive(),
        "the second tick must skip the locked row, not queue behind the in-flight send");
    barrier.reset();
    a.join(10_000);
    b.join(10_000);

    assertEquals("SENT", deliveryStatus(delivery));
    assertEquals(1, attempts(delivery), "sent exactly once");
    assertEquals(1, sender.captured.size(), "provider hit exactly once — no double-send");
    // The P5 in_app feed leg is also pending — drain it before the parent can finalize.
    service.dispatchPendingInApp(100);
    assertEquals("DISPATCHED", notificationStatus(nid));
  }

  // D4 follow-up: the send no longer runs inside the delivery transaction

  /**
   * The point of the claimed state, asserted directly: while the provider call is in flight the row
   * is <b>SENDING and not locked</b>.
   *
   * <p>Before this, {@code send()} ran inside the transaction that had the row under {@code FOR
   * UPDATE}, so a hung peer pinned the row lock and a pooled DB connection for the whole round-trip
   * — bounded at ~25 s by the D4 timeouts, but still a slice of the pool per stuck message, and the
   * reason a second delivery node was never safe to run. {@code FOR UPDATE NOWAIT} from an
   * independent connection is the honest test: it throws if anyone holds the row, so it passing is
   * proof the transaction really was committed and released before the send began.
   */
  @Test
  void sendRunsOutsideTheTransaction_theRowIsClaimedButUnlocked() throws Exception {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "nadia@acme.test");
    CountDownLatch inSend = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    EmailSender blocking =
        m -> {
          inSend.countDown();
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    NotificationService service = service(blocking, 5);
    UUID nid = produce(service, org, customer, "SO-UNLOCKED");
    UUID delivery = emailDeliveryId(nid);

    Thread tick = new Thread(() -> service.dispatchPendingEmail(100), "tick");
    tick.start();
    assertTrue(inSend.await(10, TimeUnit.SECONDS), "the sender was reached");

    // The claim committed: visible to everyone, from a different connection.
    assertEquals("SENDING", deliveryStatus(delivery), "the row is claimed while the send is open");

    // And nothing holds it. NOWAIT throws instead of blocking if a lock is outstanding.
    assertDoesNotThrow(
        () ->
            dsl.transaction(
                cfg ->
                    DSL.using(cfg)
                        .selectFrom(NOTIFICATION_DELIVERY)
                        .where(NOTIFICATION_DELIVERY.ID.eq(delivery))
                        .forUpdate()
                        .noWait()
                        .fetch()),
        "no transaction may hold the delivery row while SMTP is in flight");

    release.countDown();
    tick.join(10_000);
    assertEquals("SENT", deliveryStatus(delivery));
    assertEquals(1, attempts(delivery), "one claim, one attempt");
  }

  /**
   * The other half of the lease. A worker that dies between claiming and settling leaves the row
   * SENDING, and the PENDING drain never looks at it — so without the reaper that is a permanent
   * silent loss, which is exactly the new failure mode this state introduces.
   */
  @Test
  void aStrandedClaimIsReturnedToTheQueue() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "nadia@acme.test");
    CapturingSender sender = new CapturingSender();
    NotificationService service = service(sender, 5);
    UUID nid = produce(service, org, customer, "SO-STRANDED");
    UUID delivery = emailDeliveryId(nid);

    // Simulate the crash: claimed, never settled, and the claim is now older than the lease.
    strandAsSending(delivery, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10));
    assertEquals("SENDING", deliveryStatus(delivery));

    // A lease that has not elapsed must NOT reap — an in-flight send is not a stranded one.
    service.reapStrandedEmail(3600, 100);
    assertEquals("SENDING", deliveryStatus(delivery), "a claim inside its lease is left alone");

    service.reapStrandedEmail(60, 100);
    assertEquals("PENDING", deliveryStatus(delivery), "past the lease it rejoins the queue");
    assertEquals(
        1, attempts(delivery), "the dead claim spent an attempt — else a poison row loops");

    // And it really is deliverable again.
    service.dispatchPendingEmail(100);
    assertEquals("SENT", deliveryStatus(delivery));
    assertEquals(1, sender.captured.size());
  }

  /** A row that strands until its budget is gone fails terminally rather than cycling forever. */
  @Test
  void aStrandedClaimOutOfAttemptsFailsTerminally() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "nadia@acme.test");
    NotificationService service = service(new CapturingSender(), 2);
    UUID nid = produce(service, org, customer, "SO-POISON");
    UUID delivery = emailDeliveryId(nid);

    strandAsSending(delivery, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10));
    service.reapStrandedEmail(60, 100); // attempts 0 -> 1, back to PENDING
    assertEquals("PENDING", deliveryStatus(delivery));

    strandAsSending(delivery, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10));
    service.reapStrandedEmail(60, 100); // attempts 1 -> 2 == the budget
    assertEquals("FAILED", deliveryStatus(delivery), "the retry budget bounds stranding too");
  }

  /** Force a delivery into the state a crashed worker would leave behind. */
  private void strandAsSending(UUID deliveryId, OffsetDateTime claimedAt) {
    dsl.update(NOTIFICATION_DELIVERY)
        .set(NOTIFICATION_DELIVERY.STATUS, "SENDING")
        .set(NOTIFICATION_DELIVERY.CLAIMED_AT, claimedAt)
        .where(NOTIFICATION_DELIVERY.ID.eq(deliveryId))
        .execute();
  }

  // 4. Expiry boundary + blank-email producer fault

  /**
   * D3: an unsendable customer email is a suppressed <em>channel</em>, not a failed business event.
   * This used to throw inside the caller's business transaction — which meant a customer row with a
   * blank email rolled back the order placement (or the PAID flip) that produced the notification,
   * not merely the email. The notification is still recorded, the in-app leg still lands, no email
   * delivery is queued, and it finalizes DISPATCHED — the same shape the opt-out path produces.
   */
  @Test
  void blankCustomerEmail_suppressesTheEmailLegAndCommits() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "   "); // whitespace-only
    CapturingSender sender = new CapturingSender();
    NotificationService service = service(sender, 5);

    UUID notificationId = produce(service, org, customer, "SO-NOEMAIL");

    assertNull(emailDeliveryId(notificationId), "no email delivery is queued");
    assertEquals(1, inAppDeliveryCount(notificationId), "the in-app leg still lands");

    // Draining changes nothing on the email side and the record ends terminal, not stuck PENDING.
    service.dispatchPendingEmail(100);
    service.dispatchPendingInApp(100);
    assertTrue(sender.captured.isEmpty(), "nothing was sent");
    assertEquals("DISPATCHED", notificationStatus(notificationId));
  }

  /**
   * A <em>literally</em> null email is unreachable through this table — {@code customer.email} is
   * {@code NOT NULL} (V2), so whitespace is the worst a row can carry, which is what the test above
   * uses. The service still treats null and blank identically, since the model field is nullable in
   * Java and other producers could hand one over; this pins that the DB is the reason we cannot
   * exercise it, rather than leaving a silent gap.
   */
  @Test
  void nullCustomerEmail_isNotStorable() {
    UUID org = createOrg("acme");
    assertThrows(RuntimeException.class, () -> createCustomer(org, null));
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

  // helpers

  private static NotificationService service(EmailSender sender, int maxAttempts) {
    return new NotificationService(
        dsl,
        new NotificationRepositoryFactoryImpl(),
        new UserRepositoryFactoryImpl(),
        new CustomerRepositoryFactoryImpl(),
        new com.loai.inventory.repository.NotificationPreferenceRepositoryFactoryImpl(),
        sender,
        new com.loai.inventory.service.MagicLinkService(
            dsl,
            new com.loai.inventory.repository.CustomerMagicTokenRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
            "http://localhost:8080",
            java.time.Duration.ofDays(30)),
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

  private int inAppDeliveryCount(UUID notificationId) {
    return dsl.fetchCount(
        NOTIFICATION_DELIVERY,
        NOTIFICATION_DELIVERY
            .NOTIFICATION_ID
            .eq(notificationId)
            .and(NOTIFICATION_DELIVERY.CHANNEL.eq("in_app")));
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
