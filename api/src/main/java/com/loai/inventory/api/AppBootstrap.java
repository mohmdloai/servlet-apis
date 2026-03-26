package com.loai.inventory.api;

import com.loai.inventory.api.config.AppConfig;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servlet lifecycle hook — Tomcat calls this on WAR deploy/undeploy.
 *
 * Responsibilities:
 *   contextInitialized  → build AppConfig (pool, migrations, jOOQ, services)
 *                       → store it in ServletContext under key "config"
 *   contextDestroyed    → close connection pool cleanly
 *
 * Servlets retrieve their services via:
 *   AppConfig config = (AppConfig) getServletContext().getAttribute("config");
 */
@WebListener
public class AppBootstrap implements ServletContextListener {

    private static final Logger log = LoggerFactory.getLogger(AppBootstrap.class);

    public static final String CONFIG_KEY = "config";

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        log.info("WAR deploying — building application context");
        try {
            AppConfig config = new AppConfig();
            sce.getServletContext().setAttribute(CONFIG_KEY, config);
            log.info("WAR deployed successfully");
        } catch (Exception e) {
            log.error("Failed to initialise application context — WAR will be unusable", e);
            throw e; // re-throw so Tomcat marks the app as failed
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        log.info("WAR undeploying — releasing resources");
        AppConfig config = (AppConfig) sce.getServletContext().getAttribute(CONFIG_KEY);
        if (config != null) {
            config.shutdown();
        }
    }
}