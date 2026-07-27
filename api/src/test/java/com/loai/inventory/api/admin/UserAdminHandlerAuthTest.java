package com.loai.inventory.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.UserAdminHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.platform.PlatformAuditService;
import com.loai.inventory.service.platform.UserAdminService;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Authz matrix for {@code /api/admin/users}: reads (list, detail) are ADMIN-or-SUPPORT; every
 * mutation and every session operation is ADMIN-only. SUPPORT can look but never touch.
 */
class UserAdminHandlerAuthTest {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID USER = UUID.randomUUID();
  private static final UUID ORG = UUID.randomUUID();
  private static final UUID FAMILY = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  private SecurityContext platform(SystemRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(role), Map.of(), Set.of(), 0);
  }

  private SecurityContext orgOwnerOnly() {
    return new SecurityContext(
        UUID.randomUUID(),
        ActorType.USER,
        Set.of(),
        Map.of(ORG, Set.of(OrgRole.OWNER)),
        Set.of(),
        0);
  }

  private AppUser aUser() {
    return new AppUser(
        USER, "u@x.io", "h", ActorType.USER, true, 0, OffsetDateTime.now(), OffsetDateTime.now());
  }

  private Mocks fresh() {
    return new Mocks();
  }

  private static final class Mocks {
    final UserAdminService userAdmin = Mockito.mock(UserAdminService.class);
    final AuthService auth = Mockito.mock(AuthService.class);
    final PlatformAuditService audit = Mockito.mock(PlatformAuditService.class);

    UserAdminHandler handler() {
      return new UserAdminHandler(
          userAdmin, auth, audit, com.loai.inventory.api.config.ObjectMapperProvider.build());
    }
  }

  // ───────────────────────── reads: ADMIN or SUPPORT ─────────────────────────

  @Test
  void list_unauthenticated_is401() throws IOException {
    Mocks m = fresh();
    Resp resp = new Resp();
    m.handler().handle("GET", reqWith(null, null), resp.mock, "");
    assertEquals(401, resp.status);
    verify(m.userAdmin, never()).list(anyInt(), anyInt(), any());
  }

  @Test
  void list_allowedForSupport() throws IOException {
    Mocks m = fresh();
    when(m.userAdmin.list(anyInt(), anyInt(), any()))
        .thenReturn(new UserAdminService.UserPage(List.of(), 0, 0, 20));
    Resp resp = new Resp();
    m.handler().handle("GET", reqWith(platform(SystemRole.SUPPORT), null), resp.mock, "");
    assertEquals(200, resp.status);
  }

  @Test
  void detail_allowedForSupport() throws IOException {
    Mocks m = fresh();
    when(m.userAdmin.get(USER))
        .thenReturn(new UserAdminService.UserDetail(aUser(), Set.of(), List.of(), 0));
    Resp resp = new Resp();
    m.handler().handle("GET", reqWith(platform(SystemRole.SUPPORT), null), resp.mock, "/" + USER);
    assertEquals(200, resp.status);
  }

  // ───────────────────────── mutations: ADMIN only ─────────────────────────

  @Test
  void create_forbiddenForSupport() throws IOException {
    Mocks m = fresh();
    Resp resp = new Resp();
    m.handler()
        .handle(
            "POST", reqWith(platform(SystemRole.SUPPORT), "{\"email\":\"a@x.io\"}"), resp.mock, "");
    assertEquals(403, resp.status);
    verify(m.userAdmin, never()).createUser(any(), any(), any(), any(), any());
  }

  @Test
  void create_allowedForAdmin() throws IOException {
    Mocks m = fresh();
    when(m.userAdmin.createUser(any(), any(), any(), any(), any())).thenReturn(aUser());
    Resp resp = new Resp();
    m.handler()
        .handle(
            "POST",
            reqWith(platform(SystemRole.ADMIN), "{\"email\":\"a@x.io\",\"password\":\"pw\"}"),
            resp.mock,
            "");
    assertEquals(201, resp.status);
    verify(m.userAdmin).createUser(any(), any(), eq("a@x.io"), eq("pw"), eq(ActorType.USER));
  }

  @Test
  void patch_forbiddenForSupport() throws IOException {
    Mocks m = fresh();
    Resp resp = new Resp();
    m.handler()
        .handle(
            "PATCH",
            reqWith(platform(SystemRole.SUPPORT), "{\"active\":false}"),
            resp.mock,
            "/" + USER);
    assertEquals(403, resp.status);
    verify(m.userAdmin, never()).setActive(any(), any(), any(), Mockito.anyBoolean());
  }

  @Test
  void grantSystemRole_allowedForAdmin() throws IOException {
    Mocks m = fresh();
    Resp resp = new Resp();
    m.handler()
        .handle(
            "POST",
            reqWith(platform(SystemRole.ADMIN), "{\"role\":\"SUPPORT\"}"),
            resp.mock,
            "/" + USER + "/system-roles");
    assertEquals(204, resp.status);
    verify(m.userAdmin).grantSystemRole(any(), any(), eq(USER), eq(SystemRole.SUPPORT));
  }

  @Test
  void revokeSystemRole_allowedForAdmin() throws IOException {
    Mocks m = fresh();
    Resp resp = new Resp();
    m.handler()
        .handle(
            "DELETE",
            reqWith(platform(SystemRole.ADMIN), null),
            resp.mock,
            "/" + USER + "/system-roles/SUPPORT");
    assertEquals(204, resp.status);
    verify(m.userAdmin).revokeSystemRole(any(), any(), eq(USER), eq(SystemRole.SUPPORT));
  }

  @Test
  void grantOrgRole_forbiddenForSupport() throws IOException {
    Mocks m = fresh();
    Resp resp = new Resp();
    m.handler()
        .handle(
            "POST",
            reqWith(
                platform(SystemRole.SUPPORT), "{\"org_id\":\"" + ORG + "\",\"role\":\"STAFF\"}"),
            resp.mock,
            "/" + USER + "/org-roles");
    assertEquals(403, resp.status);
    verify(m.userAdmin, never()).grantOrgRole(any(), any(), any(), any(), any());
  }

  @Test
  void resetPassword_forbiddenForOrgOwner() throws IOException {
    Mocks m = fresh();
    Resp resp = new Resp();
    m.handler()
        .handle(
            "POST",
            reqWith(orgOwnerOnly(), "{\"password\":\"x\"}"),
            resp.mock,
            "/" + USER + "/reset-password");
    assertEquals(403, resp.status);
    verify(m.userAdmin, never()).resetPassword(any(), any(), any(), any());
  }

  // ───────────────────────── session ops: ADMIN only ─────────────────────────

  @Test
  void sessions_list_forbiddenForSupport() throws IOException {
    Mocks m = fresh();
    Resp resp = new Resp();
    m.handler()
        .handle(
            "GET",
            reqWith(platform(SystemRole.SUPPORT), null),
            resp.mock,
            "/" + USER + "/sessions");
    assertEquals(403, resp.status);
    verify(m.auth, never()).listSessions(any());
  }

  @Test
  void sessions_list_allowedForAdmin() throws IOException {
    Mocks m = fresh();
    when(m.auth.listSessions(USER)).thenReturn(List.of());
    Resp resp = new Resp();
    m.handler()
        .handle(
            "GET", reqWith(platform(SystemRole.ADMIN), null), resp.mock, "/" + USER + "/sessions");
    assertEquals(200, resp.status);
    verify(m.auth).listSessions(USER);
  }

  @Test
  void sessions_revoke_allowedForAdmin_auditsAndRevokes() throws IOException {
    Mocks m = fresh();
    Resp resp = new Resp();
    m.handler()
        .handle(
            "DELETE",
            reqWith(platform(SystemRole.ADMIN), null),
            resp.mock,
            "/" + USER + "/sessions/" + FAMILY);
    assertEquals(204, resp.status);
    verify(m.auth).revokeSession(USER, FAMILY);
    verify(m.audit).record(any(), any(), isNull(), eq("SESSION_REVOKE"), any(), eq(FAMILY), any());
  }

  @Test
  void logoutAll_allowedForAdmin_auditsAndRevokes() throws IOException {
    Mocks m = fresh();
    Resp resp = new Resp();
    m.handler()
        .handle(
            "POST",
            reqWith(platform(SystemRole.ADMIN), null),
            resp.mock,
            "/" + USER + "/logout-all");
    assertEquals(204, resp.status);
    verify(m.auth).logoutAll(USER);
    verify(m.audit).record(any(), any(), isNull(), eq("FORCE_LOGOUT_ALL"), any(), eq(USER), any());
  }

  @Test
  void logoutAll_forbiddenForSupport() throws IOException {
    Mocks m = fresh();
    Resp resp = new Resp();
    m.handler()
        .handle(
            "POST",
            reqWith(platform(SystemRole.SUPPORT), null),
            resp.mock,
            "/" + USER + "/logout-all");
    assertEquals(403, resp.status);
    verify(m.auth, never()).logoutAll(any());
  }

  // ─────────────── harness ───────────────

  private static final class Resp {
    final HttpServletResponse mock;
    final ByteArrayOutputStream body = new ByteArrayOutputStream();
    int status = 200;

    Resp() throws IOException {
      mock = Mockito.mock(HttpServletResponse.class);
      Mockito.doAnswer(
              inv -> {
                status = inv.getArgument(0);
                return null;
              })
          .when(mock)
          .setStatus(Mockito.anyInt());
      when(mock.getOutputStream())
          .thenReturn(
              new ServletOutputStream() {
                @Override
                public void write(int b) {
                  body.write(b);
                }

                @Override
                public boolean isReady() {
                  return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {}
              });
    }
  }

  private HttpServletRequest reqWith(SecurityContext ctx, String jsonBody) throws IOException {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
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
}
