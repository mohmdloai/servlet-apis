package com.loai.inventory.api;

import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.servlet.ProductServlet;
import java.io.File;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

/**
 * Embedded Tomcat launcher for running via {@code mvn exec:java}.
 *
 * <p>Uses addContext (not addWebapp) to avoid WAR-extraction and classloader isolation issues that
 * arise when exec:java shares a single classpath.
 */
public class EmbeddedTomcatLauncher {
  public static void main(String[] args) throws Exception {
    try {
      Tomcat tomcat = new Tomcat();
      tomcat.setPort(8080);

      File baseDir = new File("target/tomcat");
      baseDir.mkdirs();
      tomcat.setBaseDir(baseDir.getAbsolutePath());

      File docBase = new File(baseDir, "webapps/ROOT");
      docBase.mkdirs();
      Context ctx = tomcat.addContext("", docBase.getAbsolutePath());

      // Let the context delegate to the system classloader so Tomcat
      // can find our application classes loaded by exec:java.
      ctx.setParentClassLoader(Thread.currentThread().getContextClassLoader());

      // Build application config (pool, migrations, jOOQ, services)
      // and store it in the servlet context for servlets to pick up.
      AppConfig config = new AppConfig();
      ctx.getServletContext().setAttribute(AppBootstrap.CONFIG_KEY, config);

      // Register servlets programmatically
      Tomcat.addServlet(ctx, "productServlet", new ProductServlet());
      ctx.addServletMappingDecoded("/api/products/*", "productServlet");

      // Shut down the connection pool when Tomcat stops
      Runtime.getRuntime().addShutdownHook(new Thread(config::shutdown));

      tomcat.getConnector();
      tomcat.start();

      System.out.println("Embedded Tomcat started at http://localhost:8080");
      System.out.println("   API endpoints: http://localhost:8080/api/products");
      System.out.println("   Press Ctrl+C to stop");

      tomcat.getServer().await();

    } catch (Exception e) {
      System.err.println("Failed to start Tomcat:");
      e.printStackTrace();
      System.exit(1);
    }
  }
}
