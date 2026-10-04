# syntax=docker/dockerfile:1

# ---- Build stage: compiles the Spring Boot jar with JDK 25 --------------------------------------------------------
FROM gradle:jdk25 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle :app-service:bootJar -x test --no-daemon --console=plain -q

# ---- Runtime stage: JRE only, non-root user ------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/applications/app-service/build/libs/TicketingPlatform.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
