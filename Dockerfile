FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp package

FROM tomcat:11.0.25-jdk25-temurin
RUN rm -rf /usr/local/tomcat/webapps/* && useradd --system --uid 10001 appuser \
    && chown -R appuser /usr/local/tomcat
COPY --from=build --chown=appuser /build/target/studentManagerSix.war /usr/local/tomcat/webapps/studentManagerSix.war
USER appuser
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 CMD ["java", "-cp", "/usr/local/tomcat/webapps/studentManagerSix/WEB-INF/classes", "com.utils.HealthProbe"]
