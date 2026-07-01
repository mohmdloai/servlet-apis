package com.loai.inventory.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.OrgAdminHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.OrgHealth;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.service.platform.PlatformOrgService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Authz matrix for {@code /api/admin/orgs}: the read console is gated on {@code
 * requirePlatformRead} (ADMIN or SUPPORT), never on an org role. This is the guarantee that finally
 * gives SUPPORT a purpose and lets a platform operator see orgs they hold no role in.
 */
class OrgAdminHandlerAuthTest {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  private SecurityContext platform(SystemRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(role), Map.of(), Set.of(), 0);
  }

  private SecurityContext orgOwnerOnly() {
    // A tenant OWNER with no platform role: the strongest org role, still barred from the console.
    return new SecurityContext(
        UUID.randomUUID(),
        ActorType.USER,
        Set.of(),
        Map.of(ORG, Set.of(OrgRole.OWNER)),
        Set.of(),
        0);
  }

  private OrgAdminHandler handler(PlatformOrgService service) {
    return new OrgAdminHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  private PlatformOrgService.OrgPage emptyPage() {
    return new PlatformOrgService.OrgPage(List.of(), 0, 0, 20);
  }

  private PlatformOrgService.OrgWithHealth someOrg() {
    Org org = new Org(ORG, "Acme", "acme", true, null, OffsetDateTime.now(), OffsetDateTime.now());
    return new PlatformOrgService.OrgWithHealth(org, new OrgHealth(0, 0, 0, 0));
  }

  @Test
  void list_unauthenticated_is401() throws IOException {
    PlatformOrgService service = Mockito.mock(PlatformOrgService.class);
    Resp resp = new Resp();
    handler(service).handle("GET", reqWith(null), resp.mock, "");
    assertEquals(401, resp.status);
    verify(service, never()).list(anyInt(), anyInt(), any());
  }

  @Test
  void list_forbiddenForOrgOwnerWithoutPlatformRole() throws IOException {
    PlatformOrgService service = Mockito.mock(PlatformOrgService.class);
    Resp resp = new Resp();
    handler(service).handle("GET", reqWith(orgOwnerOnly()), resp.mock, "");
    assertEquals(403, resp.status);
    verify(service, never()).list(anyInt(), anyInt(), any());
  }

  @Test
  void list_allowedForSupport() throws IOException {
    PlatformOrgService service = Mockito.mock(PlatformOrgService.class);
    when(service.list(anyInt(), anyInt(), any())).thenReturn(emptyPage());
    Resp resp = new Resp();
    handler(service).handle("GET", reqWith(platform(SystemRole.SUPPORT)), resp.mock, "");
    assertEquals(200, resp.status);
    verify(service).list(0, PlatformOrgService.DEFAULT_PAGE_SIZE, null);
  }

  @Test
  void list_allowedForAdmin() throws IOException {
    PlatformOrgService service = Mockito.mock(PlatformOrgService.class);
    when(service.list(anyInt(), anyInt(), any())).thenReturn(emptyPage());
    Resp resp = new Resp();
    handler(service).handle("GET", reqWith(platform(SystemRole.ADMIN)), resp.mock, "");
    assertEquals(200, resp.status);
  }

  @Test
  void detail_allowedForSupport() throws IOException {
    PlatformOrgService service = Mockito.mock(PlatformOrgService.class);
    when(service.getWithHealth(ORG)).thenReturn(someOrg());
    Resp resp = new Resp();
    handler(service).handle("GET", reqWith(platform(SystemRole.SUPPORT)), resp.mock, "/" + ORG);
    assertEquals(200, resp.status);
    verify(service).getWithHealth(ORG);
  }

  @Test
  void detail_forbiddenForOrgOwnerWithoutPlatformRole() throws IOException {
    PlatformOrgService service = Mockito.mock(PlatformOrgService.class);
    Resp resp = new Resp();
    handler(service).handle("GET", reqWith(orgOwnerOnly()), resp.mock, "/" + ORG);
    assertEquals(403, resp.status);
    verify(service, never()).getWithHealth(any());
  }

  @Test
  void suspend_forbiddenForSupport_readCannotWrite() throws IOException {
    // SUPPORT gates the read console but must never mutate - suspension is ADMIN-only.
    PlatformOrgService service = Mockito.mock(PlatformOrgService.class);
    Resp resp = new Resp();
    handler(service)
        .handle("POST", reqWith(platform(SystemRole.SUPPORT)), resp.mock, "/" + ORG + "/suspend");
    assertEquals(403, resp.status);
    verify(service, never()).suspend(any(), any(), any(), any());
  }

  @Test
  void suspend_allowedForAdmin() throws IOException {
    PlatformOrgService service = Mockito.mock(PlatformOrgService.class);
    Org org = new Org(ORG, "Acme", "acme", false, null, OffsetDateTime.now(), OffsetDateTime.now());
    when(service.suspend(any(), any(), eq(ORG), any())).thenReturn(org);
    Resp resp = new Resp();
    handler(service)
        .handle("POST", reqWith(platform(SystemRole.ADMIN)), resp.mock, "/" + ORG + "/suspend");
    assertEquals(200, resp.status);
    verify(service).suspend(any(), any(), eq(ORG), any());
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

  private HttpServletRequest reqWith(SecurityContext ctx) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    return req;
  }
}
