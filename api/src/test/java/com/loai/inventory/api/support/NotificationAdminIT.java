package com.loai.inventory.api.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.repository.generated.enums.OrgRole;
import com.loai.inventory.service.NotificationService.UserFeedItem;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The operator's feed ({@code GET /api/admin/notifications}, {@code
 * stories/support_ticket_reach.md}) at the service seam: a user-scoped read across every org — two
 * desk users each see only their own rows, with the tenant named; {@code ?unread=true} totals;
 * read/dismiss are own-row only, a foreign row the same opaque 404 as the org feed's. The handler's
 * gates (a tenant OWNER → 403, SUPPORT admitted) are in {@link SupportTicketHandlerTest}.
 */
class NotificationAdminIT extends SupportReachItBase {

  @Test
  void eachDeskUserSeesOwnRowsAcrossOrgs_withTheTenantNamed_andNeverAnotherUsers() {
    UUID other = createOrg("Gizeh Grocers");
    UUID otherStaff = member(other, OrgRole.STAFF, "Omar");
    tickets.open(org, staff, cmd("Cannot invite a manager", "The invite link 404s."));
    tickets.open(other, otherStaff, cmd("Storefront shows old price", "Sugar 1kg."));

    List<UserFeedItem> adminFeed = notifications.getUserFeed(admin, false, 0, 10);
    List<UserFeedItem> supportFeed = notifications.getUserFeed(support, false, 0, 10);
    assertEquals(2, adminFeed.size(), "one row per ticket opened, both orgs");
    assertEquals(2, supportFeed.size());
    assertEquals(2, notifications.countUserFeed(admin, false));
    assertEquals(
        Set.of("Mart Cairo", "Gizeh Grocers"),
        adminFeed.stream().map(UserFeedItem::orgName).collect(Collectors.toSet()),
        "the console has no current org, so every row names its tenant");
    for (UserFeedItem row : adminFeed) {
      assertEquals(admin, row.item().notification().getRecipientUserId());
      assertEquals(row.item().notification().getOrgId(), row.orgId());
      assertEquals("SUPPORT_TICKET_OPENED", row.item().notification().getType());
    }
    Set<UUID> adminIds =
        adminFeed.stream().map(r -> r.item().notification().getId()).collect(Collectors.toSet());
    Set<UUID> supportIds =
        supportFeed.stream().map(r -> r.item().notification().getId()).collect(Collectors.toSet());
    assertTrue(
        adminIds.stream().noneMatch(supportIds::contains), "no row belongs to two operators");

    // Newest first: the second ticket's row leads.
    assertEquals("Gizeh Grocers", adminFeed.get(0).orgName());
  }

  @Test
  void unreadTotals_readAndDismissAreOwnRowOnly_aForeignRowIsA404() {
    tickets.open(org, staff, cmd("Cannot invite a manager", "The invite link 404s."));
    tickets.open(org, staff, cmd("Printer stops", "After three receipts."));
    assertEquals(2, notifications.countUserFeed(admin, true));

    UUID adminRow =
        notifications.getUserFeed(admin, true, 0, 1).get(0).item().notification().getId();
    notifications.markOwnRead(admin, adminRow);
    assertEquals(1, notifications.countUserFeed(admin, true), "?unread=true drops the read row");
    assertEquals(2, notifications.countUserFeed(admin, false), "the full feed keeps it");
    notifications.markOwnRead(admin, adminRow); // idempotent
    assertEquals(1, notifications.countUserFeed(admin, true));

    // SUPPORT cannot mark ADMIN's row — and cannot learn it exists.
    assertThrows(NotFoundException.class, () -> notifications.markOwnRead(support, adminRow));
    assertThrows(NotFoundException.class, () -> notifications.markOwnDismissed(support, adminRow));
    assertThrows(
        NotFoundException.class, () -> notifications.markOwnRead(admin, UUID.randomUUID()));

    notifications.markOwnDismissed(admin, adminRow);
    assertEquals(1, notifications.countUserFeed(admin, false), "a dismissed row leaves the feed");
    assertEquals(2, notifications.countUserFeed(support, false), "SUPPORT's rows are untouched");

    // The org-scoped feed the tenant app reads is the same rows, unchanged by the platform twin.
    assertEquals(1, notifications.countFeed(org, admin, false));
  }
}
