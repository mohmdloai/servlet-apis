package com.loai.inventory.api.servlet.handler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The counter-discount gate's role resolution ({@code stories/counter_discount.md}). The service
 * decides on a boolean; this is the one place that boolean is computed from a session, and the
 * precedent beside it ({@code isOwnerOrAdmin}) tests {@code contains(OWNER)} alone — so the trap
 * this pins is an OWNER at the till being refused a MANAGER bar.
 */
class SalesOrderHandlerAuthorityTest {

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID OTHER_ORG = UUID.randomUUID();

  private static SecurityContext session(Set<SystemRole> system, Map<UUID, Set<OrgRole>> roles) {
    return new SecurityContext(UUID.randomUUID(), ActorType.USER, system, roles, Set.of(), 0);
  }

  private static SecurityContext member(OrgRole... roles) {
    return session(Set.of(), Map.of(ORG, Set.of(roles)));
  }

  @Test
  void staffAndViewer_doNotClearTheBar() {
    assertFalse(SalesOrderHandler.isManagerOrAdmin(member(OrgRole.STAFF), ORG));
    assertFalse(SalesOrderHandler.isManagerOrAdmin(member(OrgRole.VIEWER), ORG));
    assertFalse(SalesOrderHandler.isManagerOrAdmin(member(OrgRole.VIEWER, OrgRole.STAFF), ORG));
  }

  @Test
  void manager_clearsTheBar() {
    assertTrue(SalesOrderHandler.isManagerOrAdmin(member(OrgRole.MANAGER), ORG));
    assertTrue(SalesOrderHandler.isManagerOrAdmin(member(OrgRole.STAFF, OrgRole.MANAGER), ORG));
  }

  /** The trap: OWNER outranks MANAGER and must pass, even though the set holds no MANAGER. */
  @Test
  void owner_clearsTheBar_withoutHoldingManager() {
    assertTrue(SalesOrderHandler.isManagerOrAdmin(member(OrgRole.OWNER), ORG));
  }

  @Test
  void platformAdmin_clearsTheBar_withNoOrgRoleAtAll() {
    assertTrue(
        SalesOrderHandler.isManagerOrAdmin(session(Set.of(SystemRole.ADMIN), Map.of()), ORG));
  }

  @Test
  void support_isNotAdmin() {
    assertFalse(
        SalesOrderHandler.isManagerOrAdmin(session(Set.of(SystemRole.SUPPORT), Map.of()), ORG));
  }

  @Test
  void managerElsewhere_isNotManagerHere() {
    SecurityContext elsewhere = session(Set.of(), Map.of(OTHER_ORG, Set.of(OrgRole.OWNER)));
    assertFalse(SalesOrderHandler.isManagerOrAdmin(elsewhere, ORG));
    assertFalse(SalesOrderHandler.isManagerOrAdmin(session(Set.of(), null), ORG));
  }
}
