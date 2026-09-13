package com.loai.inventory.api.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.api.servlet.handler.NotificationAdminHandler;
import com.loai.inventory.api.servlet.handler.SupportTicketHandler;
import com.loai.inventory.api.servlet.handler.TicketDeskAdminHandler;
import com.loai.inventory.common.exception.InvalidTicketTransitionException;
import com.loai.inventory.common.exception.TicketCapException;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.DeskTicketRow;
import com.loai.inventory.domain.model.InAppFeedItem;
import com.loai.inventory.domain.model.Notification;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.OrgStatus;
import com.loai.inventory.domain.model.PlatformQueueOrg;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SupportTicket;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.domain.model.TicketCategory;
import com.loai.inventory.domain.model.TicketDeskCounts;
import com.loai.inventory.domain.model.TicketStatus;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.SupportTicketService;
import com.loai.inventory.service.SupportTicketService.Person;
import com.loai.inventory.service.SupportTicketService.TicketView;
import com.loai.inventory.service.platform.SupportDeskService;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The two handlers at the wire ({@code stories/support_tickets.md}): the role per route, the actor
 * and manager-authority boolean handed to the service, the {@code ?status=} 400, the three {@code
 * kind}s on the envelope, the desk gate admitting SUPPORT and refusing a tenant OWNER, and the desk
 * shapes carrying {@code org} and the opener's email where the merchant's never do.
 */
class SupportTicketHandlerTest {

  private static final String SECURITY_CONTEXT_ATTR = "securityContext";
  private static final UUID ORG = UUID.randomUUID();
  private static final UUID TICKET = UUID.randomUUID();
  private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

  private static TicketView aView(UUID openedBy) {
    SupportTicket t =
        SupportTicket.open(
            TICKET, ORG, openedBy, TicketCategory.DEVICES, "Printer stops", true, null, NOW);
    t.assignNumber(1042);
    return new TicketView(
        t, new Person(openedBy, "Sara", "sara@mart-cairo.test"), List.of(), 0, null);
  }

  // Org plane

  @Test
  void list_viewerGetsThePage_managerAuthorityReachesTheService() throws IOException {
    SupportTicketService service = Mockito.mock(SupportTicketService.class);
    SecurityContext sc = ctx(ORG, OrgRole.MANAGER);
    when(service.list(eq(ORG), eq(sc.actorId()), eq(true), eq(null), eq(0), eq(20)))
        .thenReturn(
            new SupportTicketService.TicketPage(
                List.of(
                    new SupportTicketService.TicketSummaryView(
                        aView(sc.actorId()).ticket(),
                        new Person(sc.actorId(), "M", "m@x"),
                        2,
                        "hi")),
                1));
    Resp resp = new Resp();
    orgHandler(service).handle("GET", reqWith(sc, null, Map.of()), resp.mock, ORG, "");
    assertEquals(200, resp.status);
    String body = resp.body();
    assertTrue(body.contains("\"total\":1"), body);
    assertTrue(body.contains("\"number\":1042"), body);
    assertTrue(body.contains("\"attachment_count\":2"), body);
    assertFalse(body.contains("\"email\""), "the merchant list never carries an email: " + body);
    assertFalse(body.contains("\"messages\""), "a summary carries no thread: " + body);
  }

  @Test
  void list_staffIsNotAManager_unknownStatusIs400() throws IOException {
    SupportTicketService service = Mockito.mock(SupportTicketService.class);
    SecurityContext staff = ctx(ORG, OrgRole.STAFF);
    when(service.list(
            eq(ORG), eq(staff.actorId()), eq(false), eq(TicketStatus.CLOSED), anyInt(), anyInt()))
        .thenReturn(new SupportTicketService.TicketPage(List.of(), 0));
    Resp resp = new Resp();
    orgHandler(service)
        .handle("GET", reqWith(staff, null, Map.of("status", "closed")), resp.mock, ORG, "");
    assertEquals(200, resp.status);
    verify(service)
        .list(eq(ORG), eq(staff.actorId()), eq(false), eq(TicketStatus.CLOSED), eq(0), eq(20));

    Resp bad = new Resp();
    orgHandler(service)
        .handle("GET", reqWith(staff, null, Map.of("status", "pending")), bad.mock, ORG, "");
    assertEquals(400, bad.status);
    assertTrue(bad.body().contains("awaiting_merchant"), bad.body());
  }

  @Test
  void open_staffPosts_viewerCannot_capIsA409WithItsKind() throws IOException {
    SupportTicketService service = Mockito.mock(SupportTicketService.class);
    SecurityContext staff = ctx(ORG, OrgRole.STAFF);
    when(service.open(eq(ORG), eq(staff.actorId()), any())).thenReturn(aView(staff.actorId()));
    String json =
        "{\"category\":\"devices\",\"subject\":\"Printer stops\",\"body\":\"after 3\",\"blocking\":true}";
    Resp resp = new Resp();
    orgHandler(service).handle("POST", reqWith(staff, json, Map.of()), resp.mock, ORG, "");
    assertEquals(201, resp.status);
    assertTrue(resp.body().contains("\"status\":\"OPEN\""), resp.body());
    assertTrue(resp.body().contains("\"messages\":[]"), resp.body());

    Resp viewer = new Resp();
    orgHandler(service)
        .handle("POST", reqWith(ctx(ORG, OrgRole.VIEWER), json, Map.of()), viewer.mock, ORG, "");
    assertEquals(403, viewer.status);

    Resp badCat = new Resp();
    orgHandler(service)
        .handle(
            "POST",
            reqWith(staff, "{\"category\":\"bug\",\"subject\":\"x\",\"body\":\"y\"}", Map.of()),
            badCat.mock,
            ORG,
            "");
    assertEquals(400, badCat.status);

    when(service.open(eq(ORG), eq(staff.actorId()), any())).thenThrow(new TicketCapException(10));
    Resp cap = new Resp();
    orgHandler(service).handle("POST", reqWith(staff, json, Map.of()), cap.mock, ORG, "");
    assertEquals(409, cap.status);
    assertTrue(cap.body().contains("\"kind\":\"TICKET_CAP\""), cap.body());
  }

  @Test
  void messages_closedTicketIsA409WithTheClosedKind_andCloseIsStaffOwnOrManager()
      throws IOException {
    SupportTicketService service = Mockito.mock(SupportTicketService.class);
    SecurityContext staff = ctx(ORG, OrgRole.STAFF);
    when(service.post(eq(ORG), eq(staff.actorId()), eq(false), eq(TICKET), eq("hi"), any()))
        .thenThrow(InvalidTicketTransitionException.closed(1042L));
    Resp resp = new Resp();
    orgHandler(service)
        .handle(
            "POST",
            reqWith(staff, "{\"body\":\"hi\"}", Map.of()),
            resp.mock,
            ORG,
            "/" + TICKET + "/messages");
    assertEquals(409, resp.status);
    assertTrue(resp.body().contains("\"kind\":\"TICKET_CLOSED\""), resp.body());

    SecurityContext manager = ctx(ORG, OrgRole.MANAGER);
    when(service.close(eq(ORG), eq(manager.actorId()), eq(true), eq(TICKET)))
        .thenReturn(aView(staff.actorId()));
    Resp closed = new Resp();
    orgHandler(service)
        .handle(
            "POST", reqWith(manager, null, Map.of()), closed.mock, ORG, "/" + TICKET + "/close");
    assertEquals(200, closed.status);
    verify(service).close(ORG, manager.actorId(), true, TICKET);
  }

  @Test
  void presign_isStaff_andPassesTheTypeThrough() throws IOException {
    SupportTicketService service = Mockito.mock(SupportTicketService.class);
    SecurityContext staff = ctx(ORG, OrgRole.STAFF);
    when(service.presignAttachment(ORG, "shot.png", "image/png"))
        .thenReturn(
            new SupportTicketService.Presign("https://put", ORG + "/support/k-shot.png", 900));
    Resp resp = new Resp();
    orgHandler(service)
        .handle(
            "POST",
            reqWith(staff, "{\"filename\":\"shot.png\",\"content_type\":\"image/png\"}", Map.of()),
            resp.mock,
            ORG,
            "/attachments/presign");
    assertEquals(200, resp.status);
    assertTrue(resp.body().contains("\"upload_url\":\"https://put\""), resp.body());
    assertTrue(resp.body().contains("\"expires_in_seconds\":900"), resp.body());
  }

  // The desk

  @Test
  void desk_admitsSupportAndAdmin_refusesATenantOwner() throws IOException {
    SupportDeskService service = Mockito.mock(SupportDeskService.class);
    when(service.counts(null)).thenReturn(new TicketDeskCounts(4, 7, 12, 340, 2));
    for (SystemRole role : SystemRole.values()) {
      Resp resp = new Resp();
      deskHandler(service)
          .handle("GET", reqWith(platform(role), null, Map.of()), resp.mock, "/counts");
      assertEquals(200, resp.status, role.name());
      assertTrue(resp.body().contains("\"blocking_open\":2"), resp.body());
    }
    Resp owner = new Resp();
    deskHandler(service)
        .handle("GET", reqWith(ctx(ORG, OrgRole.OWNER), null, Map.of()), owner.mock, "/counts");
    assertEquals(403, owner.status);
    verify(service, never()).list(any(), any(), anyInt(), anyInt());
  }

  @Test
  void desk_listCarriesTheTenant_unknownStatusIs400_badOrgIdIs400() throws IOException {
    SupportDeskService service = Mockito.mock(SupportDeskService.class);
    DeskTicketRow row =
        new DeskTicketRow(
            TICKET,
            1042,
            TicketStatus.OPEN,
            TicketCategory.DEVICES,
            true,
            "Printer stops",
            null,
            new PlatformQueueOrg(ORG, "Mart Cairo", "mart-cairo", OrgStatus.ACTIVE),
            UUID.randomUUID(),
            "Sara Hassan",
            NOW,
            NOW,
            NOW,
            2,
            "The printer…");
    when(service.list(eq(TicketStatus.OPEN), eq(null), eq(0), eq(20)))
        .thenReturn(new SupportDeskService.DeskPage(List.of(row), 1, 0, 20));
    Resp resp = new Resp();
    deskHandler(service)
        .handle(
            "GET",
            reqWith(platform(SystemRole.SUPPORT), null, Map.of("status", "open")),
            resp.mock,
            "");
    assertEquals(200, resp.status);
    assertTrue(
        resp.body()
            .contains(
                "\"org\":{\"id\":\""
                    + ORG
                    + "\",\"name\":\"Mart Cairo\",\"slug\":\"mart-cairo\",\"status\":\"active\"}"),
        resp.body());
    assertTrue(resp.body().contains("\"opened_by\":{\"id\":"), resp.body());
    assertFalse(resp.body().contains("\"email\""), "a desk row carries no email: " + resp.body());

    Resp bad = new Resp();
    deskHandler(service)
        .handle(
            "GET",
            reqWith(platform(SystemRole.ADMIN), null, Map.of("status", "nope")),
            bad.mock,
            "");
    assertEquals(400, bad.status);
    Resp badOrg = new Resp();
    deskHandler(service)
        .handle(
            "GET",
            reqWith(platform(SystemRole.ADMIN), null, Map.of("org_id", "x")),
            badOrg.mock,
            "");
    assertEquals(400, badOrg.status);
  }

  @Test
  void desk_replyPassesResolve_andTheViewCarriesTheOpenersEmail() throws IOException {
    SupportDeskService service = Mockito.mock(SupportDeskService.class);
    SecurityContext sc = platform(SystemRole.SUPPORT);
    SupportDeskService.DeskView view =
        new SupportDeskService.DeskView(
            aView(UUID.randomUUID()),
            new PlatformQueueOrg(ORG, "Mart Cairo", "mart-cairo", OrgStatus.ACTIVE));
    when(service.reply(eq(sc), any(), eq(TICKET), eq("Fixed."), any(), eq(true))).thenReturn(view);
    Resp resp = new Resp();
    deskHandler(service)
        .handle(
            "POST",
            reqWith(sc, "{\"body\":\"Fixed.\",\"resolve\":true}", Map.of()),
            resp.mock,
            "/" + TICKET + "/messages");
    assertEquals(201, resp.status);
    assertTrue(resp.body().contains("\"email\":\"sara@mart-cairo.test\""), resp.body());
    assertTrue(resp.body().contains("\"slug\":\"mart-cairo\""), resp.body());

    when(service.close(eq(sc), any(), eq(TICKET)))
        .thenThrow(InvalidTicketTransitionException.closed(1042L));
    Resp closed = new Resp();
    deskHandler(service)
        .handle("POST", reqWith(sc, null, Map.of()), closed.mock, "/" + TICKET + "/close");
    assertEquals(409, closed.status);
    assertTrue(closed.body().contains("\"kind\":\"TICKET_CLOSED\""), closed.body());

    Resp wrongVerb = new Resp();
    deskHandler(service)
        .handle("GET", reqWith(sc, null, Map.of()), wrongVerb.mock, "/" + TICKET + "/close");
    assertEquals(405, wrongVerb.status);
  }

  // plumbing

  // The suspended door + the operator's feed (stories/support_ticket_reach.md)

  @AfterEach
  void resetGate() {
    AuthzHelper.configureOrgStatusGate(null);
  }

  @Test
  void door_aSuspendedOrgsStaffStillReachesSupport_theServiceNeverHearsOfTheStatus()
      throws IOException {
    AuthzHelper.configureOrgStatusGate(orgId -> false); // every org suspended
    SupportTicketService service = Mockito.mock(SupportTicketService.class);
    SecurityContext staff = ctx(ORG, OrgRole.STAFF);
    when(service.list(eq(ORG), eq(staff.actorId()), eq(false), eq(null), eq(0), eq(20)))
        .thenReturn(new SupportTicketService.TicketPage(List.of(), 0));
    Resp resp = new Resp();
    orgHandler(service).handle("GET", reqWith(staff, null, Map.of()), resp.mock, ORG, "");
    assertEquals(200, resp.status, resp.body());

    when(service.open(eq(ORG), eq(staff.actorId()), any())).thenReturn(aView(staff.actorId()));
    Resp opened = new Resp();
    orgHandler(service)
        .handle(
            "POST",
            reqWith(
                staff,
                "{\"category\":\"account\",\"subject\":\"Suspended\",\"body\":\"why?\"}",
                Map.of()),
            opened.mock,
            ORG,
            "");
    assertEquals(201, opened.status, opened.body());

    // Membership still gates the door: an outsider is the same generic 403, no kind.
    Resp outsider = new Resp();
    orgHandler(service)
        .handle(
            "GET",
            reqWith(ctx(UUID.randomUUID(), OrgRole.OWNER), null, Map.of()),
            outsider.mock,
            ORG,
            "");
    assertEquals(403, outsider.status);
    assertFalse(outsider.body().contains("\"kind\""), outsider.body());
  }

  @Test
  void feed_supportReadsAndMarksOwnRows_withTheTenantOnEachRow_aTenantOwnerIs403()
      throws IOException {
    NotificationService service = Mockito.mock(NotificationService.class);
    SecurityContext support = platform(SystemRole.SUPPORT);
    Notification n = new Notification();
    n.setId(UUID.randomUUID());
    n.setOrgId(ORG);
    n.setType("SUPPORT_TICKET_OPENED");
    n.setTitle("#1042 Printer stops");
    n.setSourceType("support_ticket");
    n.setSourceId(TICKET);
    n.setCreatedAt(NOW);
    when(service.getUserFeed(eq(support.actorId()), eq(true), eq(0), eq(10)))
        .thenReturn(
            List.of(
                new NotificationService.UserFeedItem(
                    new InAppFeedItem(n, null, null, "/admin/tickets/" + TICKET),
                    ORG,
                    "Mart Cairo")));
    when(service.countUserFeed(eq(support.actorId()), eq(true))).thenReturn(1L);
    NotificationAdminHandler handler =
        new NotificationAdminHandler(
            service, com.loai.inventory.api.config.ObjectMapperProvider.build());

    Resp resp = new Resp();
    handler.handle("GET", reqWith(support, null, Map.of("unread", "true")), resp.mock, "");
    assertEquals(200, resp.status, resp.body());
    String body = resp.body();
    assertTrue(body.contains("\"total\":1"), body);
    assertTrue(body.contains("\"org\":{\"id\":\"" + ORG + "\",\"name\":\"Mart Cairo\"}"), body);
    assertTrue(body.contains("\"source_id\":\"" + TICKET + "\""), body);

    UUID row = n.getId();
    Resp read = new Resp();
    handler.handle("POST", reqWith(support, null, Map.of()), read.mock, "/" + row + "/read");
    assertEquals(204, read.status);
    verify(service).markOwnRead(support.actorId(), row);
    Resp dismissed = new Resp();
    handler.handle(
        "POST", reqWith(support, null, Map.of()), dismissed.mock, "/" + row + "/dismiss");
    assertEquals(204, dismissed.status);
    verify(service).markOwnDismissed(support.actorId(), row);

    // A tenant OWNER has no platform role: 403 on the read and on the write alike.
    Resp owner = new Resp();
    handler.handle("GET", reqWith(ctx(ORG, OrgRole.OWNER), null, Map.of()), owner.mock, "");
    assertEquals(403, owner.status);
    Resp ownerWrite = new Resp();
    handler.handle(
        "POST",
        reqWith(ctx(ORG, OrgRole.OWNER), null, Map.of()),
        ownerWrite.mock,
        "/" + row + "/read");
    assertEquals(403, ownerWrite.status);
    verify(service, Mockito.times(1)).markOwnRead(any(), eq(row)); // SUPPORT's call only
  }

  private static SupportTicketHandler orgHandler(SupportTicketService service) {
    return new SupportTicketHandler(
        service, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  private static TicketDeskAdminHandler deskHandler(SupportDeskService service) {
    return new TicketDeskAdminHandler(
        service, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  private static SecurityContext ctx(UUID orgId, OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(orgId, Set.of(role)), Set.of(), 0);
  }

  private static SecurityContext platform(SystemRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(role), Map.of(), Set.of(), 0);
  }

  private static HttpServletRequest reqWith(
      SecurityContext sc, String jsonBody, Map<String, String> params) throws IOException {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(sc);
    when(req.getParameter(any())).thenAnswer(inv -> params.get(inv.<String>getArgument(0)));
    byte[] bytes = (jsonBody == null ? "" : jsonBody).getBytes(StandardCharsets.UTF_8);
    when(req.getInputStream())
        .thenReturn(
            new ServletInputStream() {
              final ByteArrayInputStream in = new ByteArrayInputStream(bytes);

              @Override
              public int read() {
                return in.read();
              }

              @Override
              public boolean isFinished() {
                return in.available() == 0;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener listener) {}
            });
    return req;
  }

  private static final class Resp {
    final HttpServletResponse mock;
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int status = 200;

    Resp() throws IOException {
      mock = Mockito.mock(HttpServletResponse.class);
      Mockito.doAnswer(
              inv -> {
                status = inv.getArgument(0);
                return null;
              })
          .when(mock)
          .setStatus(anyInt());
      when(mock.getOutputStream())
          .thenReturn(
              new ServletOutputStream() {
                @Override
                public void write(int b) {
                  bytes.write(b);
                }

                @Override
                public boolean isReady() {
                  return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {}
              });
    }

    String body() {
      return bytes.toString(StandardCharsets.UTF_8);
    }
  }
}
