# SentinelAI backend.
#
# Multi-stage so the runtime image carries a JRE and the built jar, not the JDK or the
# Maven cache. The build stage runs `mvn package -DskipTests` because tests need a
# PostgreSQL and this image's job is to serve traffic; CI runs them separately.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, in their own layer. Editing source should not re-download the
# world, which is the whole point of copying pom.xml before src.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

# Never run the service as root: a container escape should not start as root on the host.
RUN useradd --system --uid 10001 --create-home sentinel
USER sentinel

COPY --from=build /build/target/*.jar /app/sentinel.jar

EXPOSE 8080

# The actuator health endpoint is already unauthenticated, and the orchestrator needs
# it to decide whether to route traffic here.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/sentinel.jar"]