package com.loai.inventory.api.support;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PLATFORM_AUDIT;
import static com.loai.inventory.repository.generated.Tables.SUPPORT_TICKET;
import static com.loai.inventory.repository.generated.Tables.SUPPORT_TICKET_ATTACHMENT;
import static com.loai.inventory.repository.generated.Tables.SUPPORT_TICKET_MESSAGE;
import static com.loai.inventory.repository.generated.Tables.USER_ORG_ROLE;
import static com.loai.inventory.repository.generated.Tables.USER_SYSTEM_ROLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.InvalidTicketTransitionException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.TicketCapException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.domain.model.DeskTicketRow;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SupportTicket;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.domain.model.TicketCategory;
import com.loai.inventory.domain.model.TicketCloseReason;
import com.loai.inventory.domain.model.TicketDeskCounts;
import com.loai.inventory.domain.model.TicketMessageKind;
import com.loai.inventory.domain.model.TicketStatus;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformAuditRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformStatsRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformTicketRepositoryFactoryImpl;
import com.loai.inventory.repository.SupportTicketRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.repository.generated.enums.OrgRole;
import com.loai.inventory.service.SupportTicketService;
import com.loai.inventory.service.SupportTicketService.AttachmentInput;
import com.loai.inventory.service.SupportTicketService.OpenCommand;
import com.loai.inventory.service.SupportTicketService.TicketView;
import com.loai.inventory.service.platform.PlatformAuditService;
import com.loai.inventory.service.platform.PlatformOverviewService;
import com.loai.inventory.service.platform.SupportDeskService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * Integration coverage for the support desk ({@code stories/support_tickets.md}, V97): the
 * merchant's service and the desk's service over the same rows, the state machine end to end, the
 * cap, the participants rule, own-vs-all, the attachment guards on both ends, the desk's ordering
 * and counts, and the audit rows the desk leaves on the tenant's timeline.
 */
@Testcontainers
class SupportTicketIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static SupportTicketService tickets;
  static SupportDeskService desk;
  static PlatformOverviewService overview;

  UUID org;
  UUID owner;
  UUID manager;
  UUID staffA;
  UUID staffB;
  UUID admin;
  UUID support;

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

    tickets =
        new SupportTicketService(
            dsl,
            new SupportTicketRepositoryFactoryImpl(),
            new UserRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            TestWiring.notificationService(dsl),
            TestWiring.storage());
    desk =
        new SupportDeskService(
            dsl,
            new PlatformTicketRepositoryFactoryImpl(),
            new SupportTicketRepositoryFactoryImpl(),
            tickets,
            new PlatformAuditService(dsl, new PlatformAuditRepositoryFactoryImpl()));
    overview =
        new PlatformOverviewService(
            dsl, new PlatformStatsRepositoryFactoryImpl(), false, List.of(), null, Instant.now());
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void seed() {
    dsl.deleteFrom(NOTIFICATION).execute();
    dsl.deleteFrom(PLATFORM_AUDIT).execute();
    // The desk reads across tenants, so every test starts from an empty desk.
    dsl.deleteFrom(SUPPORT_TICKET).execute();
    dsl.deleteFrom(USER_SYSTEM_ROLE).execute();
    org = createOrg("Mart Cairo");
    owner = member(org, OrgRole.OWNER, "Owner");
    manager = member(org, OrgRole.MANAGER, "Manager");
    staffA = member(org, OrgRole.STAFF, "Sara Hassan");
    staffB = member(org, OrgRole.STAFF, "Omar");
    admin = platformUser(SystemRole.ADMIN);
    support = platformUser(SystemRole.SUPPORT);
  }

  // Open

  @Test
  void open_numbersTheTicket_writesTheFirstMessage_andTellsEveryDeskUserInTheSameTxn() {
    String key = ObjectStorage.supportKeyPrefix(org) + UUID.randomUUID() + "-shot.png";
    TicketView v =
        tickets.open(
            org,
            staffA,
            cmd(
                "Printer stops after 3 receipts",
                "The Bluetooth printer prints three receipts and then stops.",
                true,
                List.of(new AttachmentInput(key, "image/png", "shot.png"))));

    SupportTicket t = v.ticket();
    assertEquals(TicketStatus.OPEN, t.getStatus());
    assertNotNull(t.getNumber());
    assertEquals(t.getOpenedAt(), t.getStatusSince());
    assertTrue(t.isBlocking());
    assertEquals(1, v.messages().size());
    assertEquals(TicketMessageKind.MESSAGE, v.messages().get(0).message().kind());
    assertEquals(staffA, v.messages().get(0).message().authorId());
    assertEquals(1, v.messages().get(0).attachments().size());
    assertNotNull(
        v.messages().get(0).attachments().get(0).url(), "a key under the prefix is presigned");
    assertEquals(1, v.attachmentCount());
    assertEquals("Sara Hassan", v.openedBy().displayName());

    // Both desk users, each a USER recipient with org_id = the ticket's org, each with an in-app
    // leg.
    var rows =
        dsl.selectFrom(NOTIFICATION).where(NOTIFICATION.TYPE.eq("SUPPORT_TICKET_OPENED")).fetch();
    assertEquals(2, rows.size());
    assertEquals(
        Set.of(admin, support),
        Set.copyOf(rows.map(r -> r.getRecipientUserId())),
        "every active ADMIN and SUPPORT user is told");
    for (var n : rows) {
      assertEquals(org, n.getOrgId());
      assertTrue(n.getTitle().contains("#" + t.getNumber()), n.getTitle());
      assertTrue(
          n.getTitle().startsWith("Can't sell"), "blocking leads the title: " + n.getTitle());
      long inApp =
          dsl.fetchCount(
              NOTIFICATION_DELIVERY,
              NOTIFICATION_DELIVERY
                  .NOTIFICATION_ID
                  .eq(n.getId())
                  .and(NOTIFICATION_DELIVERY.CHANNEL.eq("in_app")));
      assertEquals(1, inApp, "the in-app row is written now; the console reads it in slice 2");
    }
  }

  @Test
  void open_refusesTheEleventh_untilOneCloses() {
    for (int i = 0; i < SupportTicketService.OPEN_CAP; i++) {
      tickets.open(org, staffA, cmd("Ticket " + i, "body", false, List.of()));
    }
    assertThrows(
        TicketCapException.class,
        () -> tickets.open(org, staffA, cmd("One too many", "body", false, List.of())));

    UUID first = tickets.list(org, staffA, false, null, 0, 50).items().get(0).ticket().getId();
    tickets.close(org, staffA, false, first);
    TicketView v = tickets.open(org, staffA, cmd("Now it fits", "body", false, List.of()));
    assertEquals(TicketStatus.OPEN, v.ticket().getStatus());
  }

  // A message is the transition

  @Test
  void theThread_walksTheMachine_andWritesAStatusRowPerTransition() {
    UUID id = tickets.open(org, staffA, cmd("Printer", "stops", false, List.of())).ticket().getId();
    SecurityContext sc = platform(admin, SystemRole.SUPPORT);

    // desk reply → AWAITING_MERCHANT, first response stamped once
    var afterReply =
        desk.reply(sc, env(), id, "Try the acknowledgement setting.", List.of(), false);
    assertEquals(TicketStatus.AWAITING_MERCHANT, afterReply.view().ticket().getStatus());
    OffsetDateTime firstResponse = afterReply.view().ticket().getFirstResponseAt();
    assertNotNull(firstResponse);
    assertEquals(1, statusRows(id, TicketStatus.AWAITING_MERCHANT));

    // merchant message → OPEN (the ball returns), a STATUS row
    var afterMerchant = tickets.post(org, staffA, false, id, "Still stops.", List.of());
    assertEquals(TicketStatus.OPEN, afterMerchant.ticket().getStatus());
    assertEquals(1, statusRows(id, TicketStatus.OPEN));

    // desk reply with resolve → RESOLVED
    var afterResolve = desk.reply(sc, env(), id, "Fixed in the firmware.", List.of(), true);
    assertEquals(TicketStatus.RESOLVED, afterResolve.view().ticket().getStatus());
    assertEquals(
        firstResponse.toInstant(),
        afterResolve.view().ticket().getFirstResponseAt().toInstant(),
        "stamped once");
    assertNotNull(afterResolve.view().ticket().getResolvedAt());

    // a desk footnote on RESOLVED keeps it resolved and writes no STATUS row
    desk.reply(sc, env(), id, "By the way, the manual is online.", List.of(), false);
    assertEquals(TicketStatus.RESOLVED, tickets.get(org, staffA, false, id).ticket().getStatus());
    assertEquals(1, statusRows(id, TicketStatus.RESOLVED));

    // merchant message on RESOLVED → the reopen, resolution cleared
    var reopened = tickets.post(org, staffA, false, id, "Not fixed.", List.of());
    assertEquals(TicketStatus.OPEN, reopened.ticket().getStatus());
    assertNull(reopened.ticket().getResolvedAt());
    assertEquals(2, statusRows(id, TicketStatus.OPEN));
    assertEquals(
        1,
        dsl.fetchCount(
            NOTIFICATION,
            NOTIFICATION
                .TYPE
                .eq("SUPPORT_TICKET_UPDATED")
                .and(NOTIFICATION.BODY.isNotNull())
                .and(NOTIFICATION.RECIPIENT_USER_ID.eq(admin))
                .and(NOTIFICATION.TITLE.like("%reopened%"))),
        "the desk hears 'reopened', not 'replied'");

    // close from the merchant → CLOSED; everything refuses with TICKET_CLOSED and writes nothing
    var closed = tickets.close(org, staffA, false, id);
    assertEquals(TicketStatus.CLOSED, closed.ticket().getStatus());
    assertEquals(TicketCloseReason.MERCHANT, closed.ticket().getClosedReason());
    int rowsBefore =
        dsl.fetchCount(SUPPORT_TICKET_MESSAGE, SUPPORT_TICKET_MESSAGE.TICKET_ID.eq(id));
    InvalidTicketTransitionException e1 =
        assertThrows(
            InvalidTicketTransitionException.class,
            () -> tickets.post(org, staffA, false, id, "hello?", List.of()));
    assertEquals(InvalidTicketTransitionException.KIND_CLOSED, e1.getKind());
    InvalidTicketTransitionException e2 =
        assertThrows(
            InvalidTicketTransitionException.class,
            () -> desk.reply(sc, env(), id, "hello?", List.of(), false));
    assertEquals(InvalidTicketTransitionException.KIND_CLOSED, e2.getKind());
    assertThrows(InvalidTicketTransitionException.class, () -> desk.close(sc, env(), id));
    assertEquals(
        rowsBefore,
        dsl.fetchCount(SUPPORT_TICKET_MESSAGE, SUPPORT_TICKET_MESSAGE.TICKET_ID.eq(id)),
        "a refused write leaves no row");
  }

  @Test
  void resolveWithoutWords_fromOpen_thenRefusesTwice() {
    UUID id = tickets.open(org, staffA, cmd("Printer", "stops", false, List.of())).ticket().getId();
    SecurityContext sc = platform(admin, SystemRole.ADMIN);
    assertEquals(TicketStatus.RESOLVED, desk.resolve(sc, env(), id).view().ticket().getStatus());
    InvalidTicketTransitionException e =
        assertThrows(InvalidTicketTransitionException.class, () -> desk.resolve(sc, env(), id));
    assertEquals(InvalidTicketTransitionException.KIND_TRANSITION, e.getKind());
    assertEquals(
        1,
        dsl.fetchCount(
            NOTIFICATION,
            NOTIFICATION
                .TYPE
                .eq("SUPPORT_TICKET_RESOLVED")
                .and(NOTIFICATION.RECIPIENT_USER_ID.eq(staffA))));
  }

  // Who is told

  @Test
  void merchantParticipants_areToldTogether_andOwnersWhenNoneRemain() {
    UUID id = tickets.open(org, staffA, cmd("Printer", "stops", false, List.of())).ticket().getId();
    tickets.post(org, manager, true, id, "Adding context as the manager.", List.of());
    SecurityContext sc = platform(admin, SystemRole.ADMIN);
    desk.reply(sc, env(), id, "Thanks both.", List.of(), false);

    assertEquals(
        Set.of(staffA, manager),
        recipientsOf("SUPPORT_TICKET_REPLIED"),
        "everyone who wrote on the merchant side, and nobody else");

    // The opener leaves; the manager is deactivated too → the OWNERs hear the next reply.
    dsl.deleteFrom(NOTIFICATION).execute();
    dsl.update(APP_USER)
        .set(APP_USER.ACTIVE, false)
        .where(APP_USER.ID.in(staffA, manager))
        .execute();
    desk.reply(sc, env(), id, "Anyone there?", List.of(), false);
    assertEquals(Set.of(owner), recipientsOf("SUPPORT_TICKET_REPLIED"));
  }

  // Own vs. all

  @Test
  void staffSeesOnlyTheirOwn_managerSeesAll_foreignIsAnOpaque404() {
    UUID mine = tickets.open(org, staffA, cmd("Mine", "body", false, List.of())).ticket().getId();
    UUID theirs =
        tickets.open(org, staffB, cmd("Theirs", "body", false, List.of())).ticket().getId();

    assertThrows(NotFoundException.class, () -> tickets.get(org, staffA, false, theirs));
    assertThrows(
        NotFoundException.class,
        () -> tickets.post(org, staffA, false, theirs, "not mine", List.of()));
    assertEquals(mine, tickets.get(org, staffA, false, mine).ticket().getId());
    assertEquals(theirs, tickets.get(org, manager, true, theirs).ticket().getId());

    var forStaff = tickets.list(org, staffA, false, null, 0, 50);
    assertEquals(1, forStaff.total());
    assertEquals(mine, forStaff.items().get(0).ticket().getId());
    assertEquals(2, tickets.list(org, manager, true, null, 0, 50).total());
    assertEquals(2, tickets.list(org, manager, true, TicketStatus.OPEN, 0, 50).total());
    assertEquals(0, tickets.list(org, manager, true, TicketStatus.CLOSED, 0, 50).total());
  }

  // Attachments: guarded on both ends

  @Test
  void attachments_refuseAForeignPrefix_andNeverPresignAStoredKeyOutsideIt() {
    String foreign = ObjectStorage.supportKeyPrefix(UUID.randomUUID()) + "x.png";
    assertThrows(
        ValidationException.class,
        () ->
            tickets.open(
                org,
                staffA,
                cmd(
                    "Bad key",
                    "body",
                    false,
                    List.of(new AttachmentInput(foreign, "image/png", "x.png")))));
    assertThrows(
        ValidationException.class,
        () ->
            tickets.open(
                org,
                staffA,
                cmd(
                    "Bad type",
                    "body",
                    false,
                    List.of(
                        new AttachmentInput(
                            ObjectStorage.supportKeyPrefix(org) + "x.gif",
                            "image/gif",
                            "x.gif")))));
    assertThrows(
        ValidationException.class, () -> tickets.presignAttachment(org, "x.gif", "image/gif"));

    // A key that somehow reached the column outside the prefix is read back with no URL.
    TicketView v = tickets.open(org, staffA, cmd("Printer", "stops", false, List.of()));
    UUID messageId = v.messages().get(0).message().id();
    dsl.insertInto(SUPPORT_TICKET_ATTACHMENT)
        .set(SUPPORT_TICKET_ATTACHMENT.ID, UUID.randomUUID())
        .set(SUPPORT_TICKET_ATTACHMENT.ORG_ID, org)
        .set(SUPPORT_TICKET_ATTACHMENT.TICKET_ID, v.ticket().getId())
        .set(SUPPORT_TICKET_ATTACHMENT.MESSAGE_ID, messageId)
        .set(SUPPORT_TICKET_ATTACHMENT.OBJECT_KEY, foreign)
        .set(SUPPORT_TICKET_ATTACHMENT.CONTENT_TYPE, "image/png")
        .set(SUPPORT_TICKET_ATTACHMENT.FILE_NAME, "x.png")
        .set(SUPPORT_TICKET_ATTACHMENT.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
        .execute();
    var again = tickets.get(org, staffA, false, v.ticket().getId());
    assertEquals(1, again.messages().get(0).attachments().size());
    assertNull(again.messages().get(0).attachments().get(0).url());
    assertEquals(1, again.attachmentCount());

    var presign = tickets.presignAttachment(org, "shot.png", "image/png");
    assertTrue(presign.objectKey().startsWith(ObjectStorage.supportKeyPrefix(org)));
    assertTrue(presign.uploadUrl().contains(presign.objectKey().split("/")[0]));
  }

  // The desk

  @Test
  void deskInbox_ordersOpenByBlockingThenOldest_countsMatch_andAuditsEveryVerb() throws Exception {
    UUID old = tickets.open(org, staffA, cmd("Oldest", "body", false, List.of())).ticket().getId();
    Thread.sleep(5);
    UUID blocking =
        tickets.open(org, staffB, cmd("Can't sell", "body", true, List.of())).ticket().getId();
    Thread.sleep(5);
    UUID newest =
        tickets.open(org, manager, cmd("Newest", "body", false, List.of())).ticket().getId();
    UUID other = createOrg("Noor Pharmacy");
    UUID otherStaff = member(other, OrgRole.STAFF, "Dina");
    UUID foreign =
        tickets.open(other, otherStaff, cmd("Scanner", "body", false, List.of())).ticket().getId();

    List<DeskTicketRow> open = desk.list(TicketStatus.OPEN, null, 0, 50).rows();
    assertEquals(
        List.of(blocking, old, newest, foreign),
        open.stream().map(DeskTicketRow::id).toList(),
        "blocking first, then the longest-waiting, across tenants");
    assertEquals("Mart Cairo", open.get(0).org().name());
    assertEquals("Omar", open.get(0).openedByName());

    assertEquals(
        List.of(old, blocking, newest),
        desk.list(TicketStatus.OPEN, org, 0, 50).rows().stream()
            .map(DeskTicketRow::id)
            .toList()
            .stream()
            .sorted(
                java.util.Comparator.comparing(id -> List.of(old, blocking, newest).indexOf(id)))
            .toList());
    assertEquals(3, desk.list(TicketStatus.OPEN, org, 0, 50).total());
    assertEquals(
        0,
        desk.list(TicketStatus.OPEN, UUID.randomUUID(), 0, 50).total(),
        "unknown org = empty page");

    SecurityContext sc = platform(support, SystemRole.SUPPORT);
    desk.reply(sc, env(), old, "On it.", List.of(), false);
    desk.resolve(sc, env(), newest);
    desk.close(sc, env(), blocking);

    TicketDeskCounts all = desk.counts(null);
    assertEquals(1, all.open());
    assertEquals(1, all.awaitingMerchant());
    assertEquals(1, all.resolved());
    assertEquals(1, all.closed());
    assertEquals(0, all.blockingOpen());
    for (TicketStatus s : TicketStatus.values()) {
      assertEquals(
          desk.list(s, null, 0, 50).total(),
          switch (s) {
            case OPEN -> all.open();
            case AWAITING_MERCHANT -> all.awaitingMerchant();
            case RESOLVED -> all.resolved();
            case CLOSED -> all.closed();
          },
          "the tab badge and the list are the same number: " + s);
    }
    TicketDeskCounts mine = desk.counts(org);
    assertEquals(0, mine.open());
    assertEquals(1, mine.awaitingMerchant());
    assertEquals(
        all.open(),
        overview.overview().support().open(),
        "the overview tile reads the same predicate");

    // Every desk verb is on the tenant's timeline: an audit row with the ticket's org_id.
    var audits = dsl.selectFrom(PLATFORM_AUDIT).where(PLATFORM_AUDIT.ORG_ID.eq(org)).fetch();
    assertEquals(3, audits.size());
    assertEquals(
        Set.of(
            SupportDeskService.ACTION_REPLIED,
            SupportDeskService.ACTION_RESOLVED,
            SupportDeskService.ACTION_CLOSED),
        Set.copyOf(audits.map(r -> r.getAction())));
    assertTrue(
        audits.stream().allMatch(r -> SupportDeskService.TARGET_TICKET.equals(r.getTargetType())));
    assertTrue(audits.stream().allMatch(r -> support.equals(r.getActorId())));

    // The desk view carries the tenant and the opener's email; the merchant's never does.
    var view = desk.get(old);
    assertEquals("mart-cairo", view.org().slug().substring(0, 10));
    assertNotNull(view.view().openedBy().email());
    assertFalse(view.view().messages().isEmpty());
  }

  // helpers

  private static OpenCommand cmd(
      String subject, String body, boolean blocking, List<AttachmentInput> attachments) {
    return new OpenCommand(TicketCategory.DEVICES, subject, body, blocking, null, attachments);
  }

  private long statusRows(UUID ticketId, TicketStatus to) {
    return dsl.fetchCount(
        SUPPORT_TICKET_MESSAGE,
        SUPPORT_TICKET_MESSAGE
            .TICKET_ID
            .eq(ticketId)
            .and(SUPPORT_TICKET_MESSAGE.KIND.eq("STATUS"))
            .and(SUPPORT_TICKET_MESSAGE.STATUS_TO.eq(to.name())));
  }

  private Set<UUID> recipientsOf(String type) {
    return Set.copyOf(
        dsl.select(NOTIFICATION.RECIPIENT_USER_ID)
            .from(NOTIFICATION)
            .where(NOTIFICATION.TYPE.eq(type))
            .fetch(NOTIFICATION.RECIPIENT_USER_ID));
  }

  private static SecurityContext platform(UUID actor, SystemRole role) {
    return new SecurityContext(
        actor, com.loai.inventory.domain.model.ActorType.USER, Set.of(role), Map.of(), Set.of(), 0);
  }

  private static Environment env() {
    return new Environment(Instant.now(), "127.0.0.1", "it");
  }

  private UUID createOrg(String name) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, name)
        .set(ORG.SLUG, name.toLowerCase().replace(' ', '-') + "-" + id.toString().substring(0, 8))
        .set(ORG.DEFAULT_LOCALE, "en")
        .execute();
    return id;
  }

  private UUID member(UUID orgId, OrgRole role, String displayName) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, id + "@mart-cairo.test")
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, ActorType.USER)
        .set(APP_USER.ACTIVE, true)
        .set(APP_USER.DISPLAY_NAME, displayName)
        .execute();
    dsl.insertInto(USER_ORG_ROLE)
        .set(USER_ORG_ROLE.USER_ID, id)
        .set(USER_ORG_ROLE.ORG_ID, orgId)
        .set(USER_ORG_ROLE.ROLE, role)
        .execute();
    return id;
  }

  private UUID platformUser(SystemRole role) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, id + "@yabta3.test")
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, ActorType.USER)
        .set(APP_USER.ACTIVE, true)
        .execute();
    dsl.insertInto(USER_SYSTEM_ROLE)
        .set(USER_SYSTEM_ROLE.USER_ID, id)
        .set(
            USER_SYSTEM_ROLE.ROLE,
            com.loai.inventory.repository.generated.enums.SystemRole.lookupLiteral(role.name()))
        .execute();
    return id;
  }
}
