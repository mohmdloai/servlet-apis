package com.loai.inventory.api.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.loai.inventory.api.servlet.CustomerAuthCookies;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The {@code customer_hint} UI cookie (slice R1, epic §11 / AC9): written alongside the session
 * cookies wherever they are written, cleared alongside them on logout(-all), <b>never</b> HttpOnly
 * (client JS reading it is its whole point), {@code Path=/}, {@code SameSite=Strict}, session
 * (refresh) lifetime. The session cookies themselves stay HttpOnly — the hint carries no authority.
 */
class CustomerAuthCookiesTest {

  private record Captured(List<Cookie> cookies, HttpServletResponse resp) {}

  private Captured capturingResponse() {
    List<Cookie> cookies = new ArrayList<>();
    HttpServletResponse resp = mock(HttpServletResponse.class);
    doAnswer(
            inv -> {
              cookies.add(inv.getArgument(0));
              return null;
            })
        .when(resp)
        .addCookie(any(Cookie.class));
    return new Captured(cookies, resp);
  }

  private static Cookie byName(List<Cookie> cookies, String name) {
    return cookies.stream().filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
  }

  @Test
  void hint_ridesTheSessionWrite_nonHttpOnly_rootPath_sameSiteStrict() {
    Captured out = capturingResponse();

    // The exact trio every session-writing site (verify-code, refresh) emits.
    CustomerAuthCookies.writeAccess(out.resp(), "access-token", 900, true);
    CustomerAuthCookies.writeRefresh(out.resp(), "refresh-token", 2_592_000, true);
    CustomerAuthCookies.writeHint(out.resp(), 2_592_000, true);

    Cookie hint = byName(out.cookies(), CustomerAuthCookies.HINT_COOKIE);
    assertEquals("1", hint.getValue());
    assertFalse(hint.isHttpOnly(), "client JS must be able to read the hint — its whole point");
    assertEquals("/", hint.getPath());
    assertEquals("Strict", hint.getAttribute("SameSite"));
    assertTrue(hint.getSecure());
    assertEquals(2_592_000, hint.getMaxAge(), "session (refresh) lifetime, not the access TTL");

    // The authority cookies stay HttpOnly — the hint adds no new surface.
    assertTrue(byName(out.cookies(), CustomerAuthCookies.ACCESS_COOKIE).isHttpOnly());
    assertTrue(byName(out.cookies(), CustomerAuthCookies.REFRESH_COOKIE).isHttpOnly());
  }

  @Test
  void clearAll_killsTheHintWithTheSession() {
    Captured out = capturingResponse();

    CustomerAuthCookies.clearAll(out.resp(), false);

    assertEquals(3, out.cookies().size(), "access + refresh + hint all cleared");
    Cookie hint = byName(out.cookies(), CustomerAuthCookies.HINT_COOKIE);
    assertEquals(0, hint.getMaxAge(), "expired immediately");
    assertEquals("", hint.getValue());
    assertEquals("/", hint.getPath());
    assertFalse(hint.isHttpOnly());
  }
}
