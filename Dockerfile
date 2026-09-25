# syntax=docker/dockerfile:1

# Built by CI and pulled by the server, never built on the server. The Gradle
# daemon is given a 2 GiB heap (gradle.properties); inside a small container
# that swapped hard enough to stall every other guest on the same host.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
# Tests and lint ran in CI before anything reached main; this only packages.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :app:bootJar --no-daemon --console=plain \
 && java -Djarmode=tools -jar app/build/libs/app.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.source=https://github.com/seeeeeeong/tickguard-kotlin
RUN useradd --system --uid 10001 --no-create-home tickguard \
 && mkdir /data && chown tickguard /data
WORKDIR /app
# Dependencies change far less often than the code, so they get their own layers
# and a deploy of a one-line fix ships one small layer.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER tickguard

# The status page listens on every interface inside the container; which host
# address it is published on is the compose file's decision.
ENV TICKGUARD_DB=/data/tickguard.db \
    TICKGUARD_METRICS_HOST=0.0.0.0
EXPOSE 9464

# A fixed heap rather than a share of the container's memory: idle RSS is about
# 70MB, and a leak should end in a restart at 256MB rather than grow into the
# memory the other services on the box need. Serial GC: one small heap on a
# low-power CPU, where a concurrent collector's threads cost more than they save.
ENTRYPOINT ["java", "-Xms64m", "-Xmx256m", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", \
            "org.springframework.boot.loader.launch.JarLauncher"]
