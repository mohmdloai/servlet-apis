package com.loai.inventory.api;

import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.filter.CorsFilter;
import com.loai.inventory.api.filter.CustomerAuthFilter;
import com.loai.inventory.api.filter.JwtAuthFilter;
import com.loai.inventory.api.filter.RateLimitFilter;
import com.loai.inventory.api.servlet.AdminServlet;
import com.loai.inventory.api.servlet.AuthServlet;
import com.loai.inventory.api.servlet.MeServlet;
import com.loai.inventory.api.servlet.OrgServlet;
import com.loai.inventory.api.servlet.PlatformImpersonationServlet;
import com.loai.inventory.api.servlet.PortalServlet;
import com.loai.inventory.api.servlet.PublicOrderServlet;
import com.loai.inventory.api.servlet.PublicStorefrontServlet;
import com.loai.inventory.api.servlet.PublicUnsubscribeServlet;
import java.io.File;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

/** Embedded Tomcat launcher for running via {@code mvn exec:java}. */
public class EmbeddedTomcatLauncher {
  public static void main(String[] args) throws Exception {
    try {
      Tomcat tomcat = new Tomcat();
      // SERVER_PORT lets a second instance (e.g. the perfdb benchmark stack) run beside dev.
      tomcat.setPort(Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080")));

      File baseDir = new File("target/tomcat");
      baseDir.mkdirs();
      tomcat.setBaseDir(baseDir.getAbsolutePath());

      File docBase = new File(baseDir, "webapps/ROOT");
      docBase.mkdirs();
      Context ctx = tomcat.addContext("", docBase.getAbsolutePath());

      ctx.setParentClassLoader(Thread.currentThread().getContextClassLoader());

      AppConfig config = new AppConfig();
      ctx.getServletContext().setAttribute(AppBootstrap.CONFIG_KEY, config);

      // Filters: CORS → RateLimit (auth + anonymous public) → JwtAuth (bypasses /api/public/* and
      // the /api/auth/login + /api/auth/refresh endpoints). One RateLimitFilter, two URL patterns:
      // /api/auth/* (login/refresh buckets) and /api/public/* (pub-read/pub-checkout buckets) — see
      // stories/public_rate_limiting.md.
      // Filter order on portal paths: CORS → RateLimit → JwtAuth (bypasses /api/portal/) →
      // CustomerAuth. The RateLimit filter's coverage now includes /api/portal/* (portal buckets)
      // and the OTP bootstrap under /api/public/*. See stories/portal_auth_core.md.
      addFilter(ctx, "corsFilter", CorsFilter.class.getName(), "/api/*");
      addFilter(
          ctx,
          "rateLimitFilter",
          RateLimitFilter.class.getName(),
          "/api/auth/*",
          "/api/public/*",
          "/api/portal/*");
      addFilter(ctx, "jwtAuthFilter", JwtAuthFilter.class.getName(), "/api/*");
      addFilter(ctx, "customerAuthFilter", CustomerAuthFilter.class.getName(), "/api/portal/*");

      // Servlets
      Tomcat.addServlet(ctx, "authServlet", new AuthServlet());
      ctx.addServletMappingDecoded("/api/auth/*", "authServlet");
      Tomcat.addServlet(ctx, "meServlet", new MeServlet());
      ctx.addServletMappingDecoded("/api/me/*", "meServlet");
      Tomcat.addServlet(ctx, "orgServlet", new OrgServlet());
      ctx.addServletMappingDecoded("/api/orgs/*", "orgServlet");
      Tomcat.addServlet(ctx, "publicStorefrontServlet", new PublicStorefrontServlet());
      ctx.addServletMappingDecoded("/api/public/*", "publicStorefrontServlet");
      // Authenticated customer portal (behind CustomerAuthFilter).
      Tomcat.addServlet(ctx, "portalServlet", new PortalServlet());
      ctx.addServletMappingDecoded("/api/portal/*", "portalServlet");
      // More specific than /api/public/* — the anonymous order-view magic-link route.
      Tomcat.addServlet(ctx, "publicOrderServlet", new PublicOrderServlet());
      ctx.addServletMappingDecoded("/api/public/orders/*", "publicOrderServlet");
      // Anonymous one-click unsubscribe magic-link route.
      Tomcat.addServlet(ctx, "publicUnsubscribeServlet", new PublicUnsubscribeServlet());
      ctx.addServletMappingDecoded("/api/public/unsubscribe/*", "publicUnsubscribeServlet");
      Tomcat.addServlet(ctx, "adminServlet", new AdminServlet());
      ctx.addServletMappingDecoded("/api/admin/*", "adminServlet");
      // More specific than /api/admin/* — Tomcat longest-path match routes impersonation here.
      Tomcat.addServlet(ctx, "platformImpersonationServlet", new PlatformImpersonationServlet());
      ctx.addServletMappingDecoded("/api/admin/impersonate/*", "platformImpersonationServlet");

      Runtime.getRuntime().addShutdownHook(new Thread(config::shutdown));

      tomcat.getConnector();
      tomcat.start();

      System.out.println("Embedded Tomcat started at http://localhost:8080");
      System.out.println("   Auth:    http://localhost:8080/api/auth/login");
      System.out.println("   Orgs:    http://localhost:8080/api/orgs");
      System.out.println("   Press Ctrl+C to stop");

      tomcat.getServer().await();

    } catch (Exception e) {
      System.err.println("Failed to start Tomcat:");
      e.printStackTrace();
      System.exit(1);
    }
  }

  private static void addFilter(Context ctx, String name, String className, String... urlPatterns) {
    FilterDef def = new FilterDef();
    def.setFilterName(name);
    def.setFilterClass(className);
    ctx.addFilterDef(def);

    FilterMap map = new FilterMap();
    map.setFilterName(name);
    for (String urlPattern : urlPatterns) {
      map.addURLPattern(urlPattern);
    }
    ctx.addFilterMap(map);
  }
}
