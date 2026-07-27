package com.loai.inventory.service.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.PlatformQueueKind;
import com.loai.inventory.domain.repository.PlatformQueueRepository;
import com.loai.inventory.domain.repository.PlatformQueueRepositoryFactory;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * The parts of {@link PlatformQueueService} an integration test cannot force: the paging clamps at
 * their boundaries, and the kind parsing that decides between a 400 and a working read.
 */
class PlatformQueueServiceTest {

  private PlatformQueueRepository repo;
  private PlatformQueueService service;

  @BeforeEach
  void setUp() {
    repo = mock(PlatformQueueRepository.class);
    PlatformQueueRepositoryFactory factory = mock(PlatformQueueRepositoryFactory.class);
    when(factory.create(any())).thenReturn(repo);
    when(repo.page(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
    when(repo.count(any(), any())).thenReturn(0L);
    service = new PlatformQueueService(mock(DSLContext.class), factory);
  }

  // kind parsing

  @ParameterizedTest
  @EnumSource(PlatformQueueKind.class)
  void parseKind_acceptsEveryWireValue(PlatformQueueKind kind) {
    assertSame(kind, PlatformQueueService.parseKind(kind.wire()));
    // Case and surrounding whitespace are not what makes a path segment wrong.
    assertSame(kind, PlatformQueueService.parseKind(" " + kind.wire().toUpperCase() + " "));
  }

  /**
   * Unknown, blank and null all land on the same cause-naming 400 — the bare {@code GET
   * /api/admin/queues} arrives here as the empty segment, and an operator who guessed a kind needs
   * the same list as one who guessed the route.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "pending-invoices", "failed_emails", "queues"})
  void parseKind_rejectsAnythingElse_namingAllFive(String raw) {
    ValidationException e =
        assertThrows(ValidationException.class, () -> PlatformQueueService.parseKind(raw));
    for (PlatformQueueKind kind : PlatformQueueKind.values()) {
      assertEquals(true, e.getMessage().contains(kind.wire()), "did not name " + kind.wire());
    }
  }

  @Test
  void parseKind_rejectsNull() {
    assertThrows(ValidationException.class, () -> PlatformQueueService.parseKind(null));
  }

  // paging clamps

  @Test
  void size_isClampedToOneAtTheBottom() {
    assertEquals(1, list(0, 0).size());
    assertEquals(1, list(0, -5).size());
    assertEquals(1, capturedLimit());
  }

  @Test
  void size_isClampedToMaxPageSizeAtTheTop() {
    assertEquals(PlatformQueueService.MAX_PAGE_SIZE, list(0, 5_000).size());
    assertEquals(PlatformQueueService.MAX_PAGE_SIZE, capturedLimit());
  }

  @Test
  void negativePage_readsAsTheFirstPage() {
    assertEquals(0, list(-3, 20).page());
    assertEquals(0, capturedOffset());
  }

  /**
   * The {@code safeOffset} overflow guard {@link PlatformOrgService} established: {@code page *
   * size} is computed in long and clamped, so a huge page number can never wrap into a negative
   * OFFSET (which Postgres rejects outright). A page past the end is simply empty.
   */
  @Test
  void hugePageNumber_clampsTheOffsetRatherThanOverflowing() {
    list(Integer.MAX_VALUE, PlatformQueueService.MAX_PAGE_SIZE);
    assertEquals(Integer.MAX_VALUE, capturedOffset());
  }

  @Test
  void offset_isPageTimesSize() {
    list(3, 25);
    assertEquals(75, capturedOffset());
  }

  /** The page echoes the clamped values, not the ones asked for — the client pages on what ran. */
  @Test
  void thePageEchoesTheClampedValues() {
    PlatformQueueService.QueuePage page = list(-1, 999);
    assertEquals(0, page.page());
    assertEquals(PlatformQueueService.MAX_PAGE_SIZE, page.size());
  }

  /** A tenant filter is passed straight through; {@code null} means every tenant. */
  @Test
  void orgIdIsPassedThroughUnchanged() {
    UUID orgId = UUID.randomUUID();
    service.list(PlatformQueueKind.OPEN_DISPUTES, orgId, 0, 20);
    org.mockito.Mockito.verify(repo)
        .page(eq(PlatformQueueKind.OPEN_DISPUTES), eq(orgId), anyInt(), anyInt());
    org.mockito.Mockito.verify(repo).count(eq(PlatformQueueKind.OPEN_DISPUTES), eq(orgId));
  }

  private PlatformQueueService.QueuePage list(int page, int size) {
    return service.list(PlatformQueueKind.PENDING_REFUNDS, null, page, size);
  }

  private int capturedOffset() {
    return capturePaging()[0];
  }

  private int capturedLimit() {
    return capturePaging()[1];
  }

  private int[] capturePaging() {
    ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
    org.mockito.Mockito.verify(repo, org.mockito.Mockito.atLeastOnce())
        .page(any(), any(), offset.capture(), limit.capture());
    List<Integer> offsets = offset.getAllValues();
    List<Integer> limits = limit.getAllValues();
    return new int[] {offsets.get(offsets.size() - 1), limits.get(limits.size() - 1)};
  }
}
