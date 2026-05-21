package com.loai.inventory.api.servlet.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * Contract for org-scoped sub-resource handlers mounted under {@code /api/orgs/{orgId}/{resource}}.
 *
 * <p>Implementations are registered by URL segment in {@link
 * com.loai.inventory.api.servlet.OrgServlet#init()}. Adding a new sub-resource is a single map
 * entry — the dispatcher does not need to change.
 */
public interface OrgResourceHandler {

  /**
   * @param remainingPath path after {@code /api/orgs/{orgId}/{resource}}; empty for list/create or
   *     starts with {@code /} for nested ids/actions
   */
  void handle(
      String method,
      HttpServletRequest req,
      HttpServletResponse resp,
      UUID orgId,
      String remainingPath)
      throws IOException;
}
