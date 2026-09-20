FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /build

# Cache dependencies separately so source changes don't bust the Maven layer.
COPY pom.xml .mvn/ ./
# Resolve application/test libraries here; go-offline also downloads every
# report/security plugin and makes a runtime image depend on their repositories.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:resolve

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp package

FROM tomcat:11.0.25-jdk25-temurin
RUN rm -rf /usr/local/tomcat/webapps/* && useradd --system --uid 10001 appuser \
    && chown -R appuser /usr/local/tomcat
# P1-5: overlay server.xml so the HTTP Connector enables gzip compression.
COPY --chown=appuser docker/tomcat-server.xml /usr/local/tomcat/conf/server.xml
COPY --from=build --chown=appuser /build/target/studentManagerSix.war /usr/local/tomcat/webapps/studentManagerSix.war
USER appuser

# P3-5: JVM honors the container memory limit; exit on OOM so the orchestrator
# can restart instead of running degraded.
ENV CATALINA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD ["java", "-cp", "/usr/local/tomcat/webapps/studentManagerSix/WEB-INF/classes", "com.utils.HealthProbe"]
