# syntax=docker/dockerfile:1
#
# Runtime image for the inventory API (embedded Tomcat on :8080).
#
# This is a RUNTIME-ONLY image: it expects the Maven build to have already produced
#   api/target/classes         — the api module's compiled classes + resources
#   api/target/dependency/*.jar — the full runtime classpath (internal modules + deps)
#
# The build is NOT done here because jOOQ codegen needs a live, migrated Postgres, which the
# CI job provides as a service container (see .github/workflows/deploy.yml). Build locally with:
#   mvn -Pcodegen -DskipTests clean install
#   mvn -pl api dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency
# then `docker build -t inventory-api .`.
FROM eclipse-temurin:25-jre

# Run as an unprivileged user.
RUN useradd --system --create-home --uid 10001 app
WORKDIR /app

# lib/ first (changes less often than app classes → better layer caching).
COPY api/target/dependency/ ./lib/
COPY api/target/classes/ ./classes/

RUN chown -R app:app /app
USER app

EXPOSE 8080

# JVM tuning: honour container memory limits; sane defaults overridable via JAVA_OPTS.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

# Embedded Tomcat writes a scratch dir under ./target/tomcat (relative to WORKDIR).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp 'classes:lib/*' com.loai.inventory.api.EmbeddedTomcatLauncher"]
