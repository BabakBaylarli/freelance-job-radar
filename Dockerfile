# syntax=docker/dockerfile:1

# ---- build stage: compile and package (tests run in CI / `mvn verify`, not while building the image) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/freelance-job-radar-*.jar extract --layers --destination /build/layers

# ---- runtime stage: small JRE image, non-root, layered for fast rebuilds ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S radar && adduser -S radar -G radar
WORKDIR /app
COPY --from=build /build/layers/dependencies/ ./
COPY --from=build /build/layers/spring-boot-loader/ ./
COPY --from=build /build/layers/snapshot-dependencies/ ./
COPY --from=build /build/layers/application/ ./
# default matching rules; docker compose mounts ./config over this directory so you can edit them
COPY config/keywords.yml ./config/keywords.yml
USER radar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    PROFILE_PATH=/app/config/profile.md \
    TZ=UTC
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "freelance-job-radar-0.1.0.jar"]
