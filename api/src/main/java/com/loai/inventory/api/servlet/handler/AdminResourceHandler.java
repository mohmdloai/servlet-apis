package com.loai.inventory.api.servlet.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * A platform-plane resource handler dispatched from {@code AdminServlet} (the {@code /api/admin/*}
 * router), mirroring {@code OrgResourceHandler} on the org plane.
 *
 * @param remaining the path tail after the resource segment, with a leading slash or empty (e.g.
 *     {@code ""} for the collection, {@code "/{id}"}, {@code "/{id}/suspend"})
 */
public interface AdminResourceHandler {
  void handle(String method, HttpServletRequest req, HttpServletResponse resp, String remaining)
      throws IOException;
}
