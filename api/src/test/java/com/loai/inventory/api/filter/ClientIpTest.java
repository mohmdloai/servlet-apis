package com.loai.inventory.api.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.util.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

/**
 * The shared client-IP resolver (stories/session_source_ip.md) — the logic RateLimitFilter always
 * had, now single-sourced so session/audit recording sites behind the deploy's Caddy stop stamping
 * the proxy's Docker-network hop.
 */
class ClientIpTest {

  private HttpServletRequest request(String remoteAddr, String xff) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRemoteAddr()).thenReturn(remoteAddr);
    when(req.getHeader("X-Forwarded-For")).thenReturn(xff);
    return req;
  }

  @Test
  void trustOff_ignoresXff_evenWhenPresent() {
    // XFF is client-spoofable; without an operator's declaration the socket peer is the truth.
    assertEquals("10.0.3.7", ClientIp.resolve(request("10.0.3.7", "203.0.113.9"), false));
  }

  @Test
  void trustOn_usesFirstXffHop() {
    assertEquals("203.0.113.9", ClientIp.resolve(request("10.0.3.7", "203.0.113.9"), true));
  }

  @Test
  void trustOn_multiHop_takesTheOriginatingClient() {
    assertEquals(
        "203.0.113.9", ClientIp.resolve(request("10.0.3.7", "203.0.113.9, 10.0.0.1"), true));
  }

  @Test
  void trustOn_blankOrEmptyXff_fallsBackToRemoteAddr() {
    assertEquals("10.0.3.7", ClientIp.resolve(request("10.0.3.7", null), true));
    assertEquals("10.0.3.7", ClientIp.resolve(request("10.0.3.7", "   "), true));
    assertEquals("10.0.3.7", ClientIp.resolve(request("10.0.3.7", " , 10.0.0.1"), true));
  }
}
