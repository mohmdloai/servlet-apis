package com.loai.inventory.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.loai.inventory.domain.model.PlatformQueueCounts;
import com.loai.inventory.domain.model.PlatformTenantCounts;
import com.loai.inventory.domain.model.TicketDeskCounts;
import com.loai.inventory.service.platform.PlatformOverviewService.Build;
import com.loai.inventory.service.platform.PlatformOverviewService.Jobs;
import com.loai.inventory.service.platform.PlatformOverviewService.Overview;
import com.loai.inventory.service.platform.PlatformOverviewService.RecurringJobHealth;
import java.time.Instant;
import java.util.List;

/**
 * Wire shape of {@code GET /api/admin/overview}. Jackson is SNAKE_CASE with nulls omitted, which
 * carries the honesty contract onto the wire for free:
 *
 * <ul>
 *   <li>A degraded section is {@code null}, so the key is simply <em>absent</em> — a client cannot
 *       mistake it for {@code 0}, and every section is nullable in this type so the compiler
 *       agrees.
 *   <li>{@code degraded} is null (hence omitted) when everything resolved, so a client branches on
 *       presence rather than on an empty array.
 *   <li>{@code build.commit} is null (hence omitted) when {@code BUILD_COMMIT} is unset — the UI
 *       renders "dev" rather than a blank or a guess. There is no {@code version} field by design.
 * </ul>
 */
public record AdminOverviewResponse(
    Instant asOf,
    Tenants tenants,
    Queues queues,
    Support support,
    JobsBlock jobs,
    BuildBlock build,
    List<String> degraded) {

  /**
   * {@code provisionedLast7d} is named explicitly: Jackson's SNAKE_CASE strategy does not break
   * before a digit, so it would emit {@code provisioned_last7d} where the contract says {@code
   * provisioned_last_7d}. The frontend reads this key.
   */
  public record Tenants(
      long total,
      long active,
      long suspended,
      long pending,
      @JsonProperty("provisioned_last_7d") long provisionedLast7d) {}

  public record Queues(
      long failedEmails,
      long pendingRefunds,
      long openDisputes,
      long orphanTransactions,
      long expiredPendingOrders) {}

  /** The support desk: tickets waiting on the desk, and how many of those say "I can't sell". */
  public record Support(long open, long blockingOpen) {}

  public record JobsBlock(boolean enabled, long servers, List<RecurringJob> recurring) {}

  /**
   * {@code lastSuccessAt == null} means <strong>no success on record</strong>, not "never ran" —
   * JobRunr deletes SUCCEEDED rows past its retention window. The UI must word it that way.
   */
  public record RecurringJob(
      String id,
      Instant lastSuccessAt,
      Instant lastFailureAt,
      long consecutiveFailures,
      Instant nextScheduledAt,
      String state) {}

  public record BuildBlock(String commit, Instant startedAt) {}

  public static AdminOverviewResponse from(Overview overview) {
    return new AdminOverviewResponse(
        overview.asOf(),
        tenants(overview.tenants()),
        queues(overview.queues()),
        support(overview.support()),
        jobs(overview.jobs()),
        build(overview.build()),
        overview.degraded() == null || overview.degraded().isEmpty() ? null : overview.degraded());
  }

  private static Support support(TicketDeskCounts c) {
    return c == null ? null : new Support(c.open(), c.blockingOpen());
  }

  private static Tenants tenants(PlatformTenantCounts t) {
    return t == null
        ? null
        : new Tenants(t.total(), t.active(), t.suspended(), t.pending(), t.provisionedLast7d());
  }

  private static Queues queues(PlatformQueueCounts q) {
    return q == null
        ? null
        : new Queues(
            q.failedEmails(),
            q.pendingRefunds(),
            q.openDisputes(),
            q.orphanTransactions(),
            q.expiredPendingOrders());
  }

  private static JobsBlock jobs(Jobs j) {
    if (j == null) {
      return null;
    }
    List<RecurringJob> recurring = j.recurring().stream().map(AdminOverviewResponse::job).toList();
    return new JobsBlock(j.enabled(), j.servers(), recurring);
  }

  private static RecurringJob job(RecurringJobHealth h) {
    return new RecurringJob(
        h.id(),
        h.lastSuccessAt(),
        h.lastFailureAt(),
        h.consecutiveFailures(),
        h.nextScheduledAt(),
        h.state().name());
  }

  private static BuildBlock build(Build b) {
    return b == null ? null : new BuildBlock(b.commit(), b.startedAt());
  }
}
