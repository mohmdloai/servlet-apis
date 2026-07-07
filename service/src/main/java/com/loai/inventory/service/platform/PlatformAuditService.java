package com.loai.inventory.service.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.PlatformAuditEntry;
import com.loai.inventory.domain.model.PlatformAuditEvent;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.repository.PlatformAuditRepository;
import com.loai.inventory.domain.repository.PlatformAuditRepositoryFactory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Writes the platform-tier audit ledger (see {@code docs/platform-admin-plan.md}). Every mutating
 * platform action routes through {@link #record} so the accountability trail is uniform.
 *
 * <p>Two entry points: {@link #record} audits on the root context (for actions that touch no DB
 * transaction, e.g. session ops on Redis), while {@link #recordInTx} joins the caller's transaction
 * so the audit row commits or rolls back atomically with the action it records.
 */
public class PlatformAuditService {

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  private final DSLContext rootDsl;
  private final PlatformAuditRepositoryFactory auditRepoFactory;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public PlatformAuditService(DSLContext rootDsl, PlatformAuditRepositoryFactory auditRepoFactory) {
    this.rootDsl = rootDsl;
    this.auditRepoFactory = auditRepoFactory;
  }

  /** A page of audit entries with the total count for pagination. */
  public record AuditPage(List<PlatformAuditEntry> entries, long total, int page, int size) {}

  /**
   * Read the audit ledger newest-first (PG2), optionally filtered by {@code targetType} (e.g.
   * {@code ORG}/{@code USER}/{@code SESSION}) and/or {@code actorId}. {@code page} is 0-based;
   * {@code size} is clamped to {@code [1, MAX_PAGE_SIZE]}.
   */
  public AuditPage list(int page, int size, String targetType, UUID actorId) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    long rawOffset = (long) p * s;
    int offset = rawOffset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rawOffset;
    PlatformAuditRepository repo = auditRepoFactory.create(rootDsl);
    return new AuditPage(
        repo.find(offset, s, targetType, actorId), repo.count(targetType, actorId), p, s);
  }

  /** Audit outside any transaction, on the root context. */
  public void record(
      SecurityContext actor,
      Environment env,
      String action,
      String targetType,
      UUID targetId,
      Map<String, Object> detail) {
    recordInTx(rootDsl, actor, env, action, targetType, targetId, detail);
  }

  /** Audit inside {@code ctx}'s transaction, so it is atomic with the action being recorded. */
  public void recordInTx(
      DSLContext ctx,
      SecurityContext actor,
      Environment env,
      String action,
      String targetType,
      UUID targetId,
      Map<String, Object> detail) {
    auditRepoFactory
        .create(ctx)
        .insert(
            new PlatformAuditEvent(
                actor.actorId(),
                action,
                targetType,
                targetId,
                serialize(detail),
                env == null ? null : env.sourceIp(),
                env == null ? null : env.userAgent()));
  }

  private String serialize(Map<String, Object> detail) {
    if (detail == null || detail.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(detail);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // Detail is best-effort context; never let its serialisation fail the audited action.
      return null;
    }
  }
}
