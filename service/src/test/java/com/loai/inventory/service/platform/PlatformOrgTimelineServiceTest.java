package com.loai.inventory.service.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.OrgTimelineEntry;
import com.loai.inventory.domain.model.OrgTimelineEntry.OrgTimelineSource;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.OrgTimelineRepository;
import com.loai.inventory.domain.repository.OrgTimelineRepositoryFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The paging arithmetic and the actor batch-load in isolation ({@code
 * stories/platform_org_timeline.md}). The merge itself is SQL and is covered by {@code
 * PlatformOrgTimelineIT} against a real database; what is worth isolating here is everything the
 * service does <em>around</em> it.
 */
class PlatformOrgTimelineServiceTest {

  private static final UUID ORG = UUID.randomUUID();
  private static final OffsetDateTime T0 =
      OffsetDateTime.of(2026, 7, 20, 10, 0, 0, 0, ZoneOffset.UTC);

  private OrgTimelineRepository repo;
  private OrgRepository orgRepo;
  private PlatformOrgTimelineService service;

  @BeforeEach
  void setUp() {
    repo = mock(OrgTimelineRepository.class);
    orgRepo = mock(OrgRepository.class);
    DSLContext dsl = mock(DSLContext.class);

    OrgTimelineRepositoryFactory timelineFactory = mock(OrgTimelineRepositoryFactory.class);
    when(timelineFactory.create(any())).thenReturn(repo);
    OrgRepositoryFactory orgFactory = mock(OrgRepositoryFactory.class);
    when(orgFactory.create(any())).thenReturn(orgRepo);

    Org org = new Org();
    org.setId(ORG);
    org.setCreatedAt(T0.minusYears(1));
    when(orgRepo.findById(ORG)).thenReturn(Optional.of(org));
    when(repo.actors(any())).thenReturn(Map.of());

    service = new PlatformOrgTimelineService(dsl, timelineFactory, orgFactory);
  }

  /** {@code page * size}, and the envelope echoes the clamped values rather than the asked-for. */
  @Test
  void offsetIsPageTimesSize() {
    when(repo.find(eq(ORG), anyInt(), anyInt())).thenReturn(List.of());

    service.list(ORG, 3, 20);

    verify(repo).find(ORG, 60, 20);
  }

  /** Size is clamped into {@code [1, MAX_PAGE_SIZE]}; a negative page is floored at 0. */
  @Test
  void sizeIsClamped_andNegativePageIsFloored() {
    when(repo.find(eq(ORG), anyInt(), anyInt())).thenReturn(List.of());

    assertEquals(PlatformOrgTimelineService.MAX_PAGE_SIZE, service.list(ORG, 0, 5000).size());
    assertEquals(1, service.list(ORG, 0, 0).size());
    assertEquals(0, service.list(ORG, -4, 20).page());
    verify(repo).find(ORG, 0, PlatformOrgTimelineService.MAX_PAGE_SIZE);
  }

  /**
   * A huge page number must not overflow into a negative OFFSET (which Postgres rejects) — the
   * {@code safeOffset} clamp the org list already uses.
   */
  @Test
  void hugePageDoesNotOverflowTheOffset() {
    when(repo.find(eq(ORG), anyInt(), anyInt())).thenReturn(List.of());

    service.list(ORG, Integer.MAX_VALUE, 100);

    verify(repo).find(ORG, Integer.MAX_VALUE, 100);
  }

  /**
   * <strong>One actor query per page, never one per row.</strong> Three entries sharing two actors
   * make exactly one call carrying the two distinct ids.
   */
  @Test
  void actorsAreBatchLoadedOnce_withDistinctIds() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(repo.find(eq(ORG), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                entry(a, "ORG_SUSPEND", T0),
                entry(b, "ORG_UPDATE", T0.minusMinutes(1)),
                entry(a, "ORG_REACTIVATE", T0.minusMinutes(2))));
    when(repo.actors(any()))
        .thenReturn(
            Map.of(
                a, new String[] {"a@x.test", "Aya"},
                b, new String[] {"b@x.test", null}));

    PlatformOrgTimelineService.TimelinePage page = service.list(ORG, 0, 20);

    verify(repo, times(1)).actors(any());
    verify(repo).actors(java.util.Set.of(a, b));
    assertEquals("a@x.test", page.entries().get(0).actorEmail());
    assertEquals("Aya", page.entries().get(0).actorDisplayName());
    assertEquals("b@x.test", page.entries().get(1).actorEmail());
    assertNull(page.entries().get(1).actorDisplayName(), "a null display name is not invented");
  }

  /**
   * An actor id that resolves to nothing keeps its id and stays nameless. <strong>Never invent an
   * actor</strong> — a plausible-looking wrong name is worse than a gap, because attribution is the
   * entire value of this surface.
   */
  @Test
  void unresolvedActorIsLeftUnresolved_notSubstituted() {
    UUID ghost = UUID.randomUUID();
    when(repo.find(eq(ORG), anyInt(), anyInt()))
        .thenReturn(List.of(entry(ghost, "ORG_UPDATE", T0)));
    when(repo.actors(any())).thenReturn(Map.of());

    OrgTimelineEntry out = service.list(ORG, 0, 20).entries().get(0);

    assertEquals(ghost, out.actorId());
    assertNull(out.actorEmail());
    assertNull(out.actorDisplayName());
  }

  /** An unknown org is a 404, not an empty page — a path segment names a thing. */
  @Test
  void unknownOrg_throwsNotFound() {
    UUID missing = UUID.randomUUID();
    when(orgRepo.findById(missing)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.list(missing, 0, 20));
  }

  /** The org lookup happens before any timeline query, so a 404 costs one cheap read. */
  @Test
  void unknownOrg_neverQueriesTheTimeline() {
    UUID missing = UUID.randomUUID();
    when(orgRepo.findById(missing)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.list(missing, 0, 20));

    verify(repo, times(0)).find(any(), anyInt(), anyInt());
  }

  /**
   * The tenant's birth rides the envelope, not the stream — self-serve registration writes no audit
   * row, so a synthesized first entry would need an actor that does not exist.
   */
  @Test
  void orgCreatedAtIsOnTheEnvelope_andNotAnEntry() {
    when(repo.find(eq(ORG), anyInt(), anyInt()))
        .thenReturn(List.of(entry(UUID.randomUUID(), "ORG_UPDATE", T0)));

    PlatformOrgTimelineService.TimelinePage page = service.list(ORG, 0, 20);

    assertEquals(T0.minusYears(1), page.orgCreatedAt());
    assertEquals(1, page.entries().size());
    assertTrue(
        page.entries().stream().noneMatch(e -> "ORG_REGISTERED".equals(e.action())),
        "the anchor was synthesized into the stream");
  }

  /** An open-text verb survives the service untouched — there is no vocabulary check here. */
  @Test
  void unknownAction_survivesToTheViewModel() {
    when(repo.find(eq(ORG), anyInt(), anyInt()))
        .thenReturn(List.of(entry(UUID.randomUUID(), "ORG_QUARANTINE_V9", T0)));

    assertEquals("ORG_QUARANTINE_V9", service.list(ORG, 0, 20).entries().get(0).action());
  }

  /** The client never re-sorts, so the service must not either: order is preserved as given. */
  @Test
  void orderIsPreservedExactlyAsTheRepositoryReturnedIt() {
    UUID a = UUID.randomUUID();
    List<OrgTimelineEntry> given =
        List.of(
            entry(a, "THIRD", T0),
            entry(a, "SECOND", T0.minusMinutes(5)),
            entry(a, "FIRST", T0.minusMinutes(9)));
    when(repo.find(eq(ORG), anyInt(), anyInt())).thenReturn(given);

    List<String> actions =
        service.list(ORG, 0, 20).entries().stream().map(OrgTimelineEntry::action).toList();

    assertEquals(List.of("THIRD", "SECOND", "FIRST"), actions);
  }

  private static OrgTimelineEntry entry(UUID actor, String action, OffsetDateTime at) {
    return new OrgTimelineEntry(
        UUID.randomUUID(), at, OrgTimelineSource.AUDIT, action, actor, null, null, null);
  }
}
