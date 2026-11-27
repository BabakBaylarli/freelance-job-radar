# syntax=docker/dockerfile:1

# ---- build stage: compile and package (tests run in CI / `mvn verify`, not while building the image) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/freelance-job-radar-*.jar extract --layers --destination /build/layers

# ---- runtime stage: JRE image (glibc: the Telegram client's native library needs it), non-root, layered ----
FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends libssl3 zlib1g \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home --shell /usr/sbin/nologin radar \
 && mkdir -p /data/tdlight && chown radar:radar /data/tdlight
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
    TELEGRAM_SESSION_DIR=/data/tdlight \
    TZ=UTC
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && grep -q "\"status\":\"UP\"" <&3' || exit 1
ENTRYPOINT ["java", "-jar", "freelance-job-radar-0.1.0.jar"]
