package com.loai.inventory.api.impersonation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.ImpersonationTier;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit coverage for the two impersonation touch-points in the api layer: the read-only write gate
 * in {@link AuthzHelper#requireOrgAccess} and the Layer-2 actor mapping in {@link
 * SecurityContext#toActorContext}.
 */
class ImpersonationAuthzUnitTest {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID DRIVER = UUID.randomUUID();

  /** Overlay context with a STAFF role in ORG, so the only thing gating writes is read-only. */
  private SecurityContext overlay(boolean readOnly) {
    return new SecurityContext(
        UUID.randomUUID(),
        ActorType.USER,
        Set.of(),
        Map.of(ORG, Set.of(OrgRole.STAFF)),
        Set.of(),
        0,
        DRIVER,
        ImpersonationTier.PLATFORM,
        null,
        readOnly);
  }

  private HttpServletRequest reqWith(SecurityContext ctx) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute("securityContext")).thenReturn(ctx);
    return req;
  }

  @Test
  void readOnlyOverlay_allowsViewerReads() {
    SecurityContext ctx = overlay(true);
    assertSame(ctx, AuthzHelper.requireOrgAccess(reqWith(ctx), ORG, OrgRole.VIEWER));
  }

  @Test
  void readOnlyOverlay_blocksStaffWrites() {
    assertThrows(
        AuthorizationException.class,
        () -> AuthzHelper.requireOrgAccess(reqWith(overlay(true)), ORG, OrgRole.STAFF));
  }

  @Test
  void readOnlyOverlay_blocksManagerWrites() {
    assertThrows(
        AuthorizationException.class,
        () -> AuthzHelper.requireOrgAccess(reqWith(overlay(true)), ORG, OrgRole.MANAGER));
  }

  @Test
  void fullWriteOverlay_allowsStaffWrites() {
    SecurityContext ctx = overlay(false);
    assertSame(ctx, AuthzHelper.requireOrgAccess(reqWith(ctx), ORG, OrgRole.STAFF));
  }

  @Test
  void toActorContext_stampsImpersonatorOnOverlay() {
    UUID target = UUID.randomUUID();
    SecurityContext overlay =
        new SecurityContext(
            target,
            ActorType.USER,
            Set.of(),
            Map.of(),
            Set.of(),
            0,
            DRIVER,
            ImpersonationTier.PLATFORM,
            null,
            false);
    ActorContext ac = overlay.toActorContext();
    assertEquals(target.toString(), ac.actorId());
    assertEquals(DRIVER, ac.impersonatorId());
  }

  @Test
  void toActorContext_normalSessionHasNoImpersonator() {
    SecurityContext normal =
        new SecurityContext(UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(), Set.of(), 0);
    assertNull(normal.toActorContext().impersonatorId());
  }
}
