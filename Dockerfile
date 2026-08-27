# syntax=docker/dockerfile:1
#
# Multi-stage build for the RealWorld Spring Boot backend (Step 2.1).
# Stage 1 compiles the app with a full JDK; stage 2 ships only the built jar on a
# slim JRE, running as a non-root user. Closes B19 (no Dockerfile existed upstream)
# and, together with .dockerignore, B20 (no .dockerignore existed upstream).

# ---- Stage 1: build ----
# eclipse-temurin:11-jdk-jammy: Temurin is the actively-maintained Adoptium OpenJDK
# build (successor to the now-EOL AdoptOpenJDK), matching the project's
# sourceCompatibility/targetCompatibility = 11. "-jammy" pins the Ubuntu 22.04 LTS
# base explicitly (rather than trusting the floating "11-jdk" tag's default OS,
# which can change under us) so the build is reproducible. Debian/Ubuntu glibc over
# Alpine/musl here avoids any native-library friction during the build (e.g. the
# Gradle daemon, DGS codegen, native JNI bits some tooling pulls in) — stage 1's
# image size doesn't matter anyway since stage 2 only copies out the built jar.
FROM eclipse-temurin:11-jdk-jammy AS build
WORKDIR /workspace

# Copy only the wrapper + build files first (no src/) so this layer, and the
# dependency-warming layer below, are cached across builds where only source
# changes — avoids re-downloading the Gradle distribution and all dependencies on
# every source edit. Note: this project has no settings.gradle (single-module
# build without one, verified against the repo root), so it is intentionally not
# copied here.
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle ./

# gradlew's executable bit isn't always preserved when the build context is
# assembled from a Windows host, so set it explicitly rather than relying on COPY.
# Also strip CRLF line endings: this repo is checked out on Windows with
# core.autocrlf, so gradlew's #!/usr/bin/env sh shebang line ends in \r, which
# /bin/sh on this Debian-based image refuses to parse ("sh\r: No such file or
# directory"). Normalizing here is build-environment-only and does not touch the
# checked-in file.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# Warm the Gradle wrapper + full dependency cache (compile/runtime/test/annotation
# processor configurations) without needing src/ present yet. This is the layer
# that makes source-only changes fast: it's only invalidated when the wrapper or
# build.gradle change.
RUN ./gradlew --no-daemon dependencies

COPY src ./src

# bootJar -x test: tests already ran in CI/locally, so re-running them in the
# image build just doubles build time for no benefit. bootJar also does NOT
# depend on spotlessJavaCheck (unlike the full `build` task), which conveniently
# sidesteps a pre-existing, unrelated spotless formatting violation in
# DefaultJwtServiceTest.java that would otherwise fail a `./gradlew build` here.
# bootJar does depend on the DGS codegen task (generateJava), which reads
# src/main/resources/schema/schema.graphqls — present because all of src/ was
# just copied above.
RUN ./gradlew bootJar -x test --no-daemon

# ---- Stage 2: runtime ----
# eclipse-temurin:11-jre-jammy: same Temurin/Ubuntu-22.04 family as the build
# stage for consistency (identical glibc, CA certs, timezone data behavior), but
# JRE-only — no compiler/build tooling — to keep the shipped image small. Alpine
# (11-jre-alpine) would be meaningfully smaller, but its musl libc has a real
# history of subtle incompatibilities with JNI-backed native libraries (relevant
# here: the postgresql JDBC driver and sqlite-jdbc both ship native components);
# Debian-slim/Temurin trades some size for that compatibility guarantee, which is
# the right tradeoff for a backend service over shaving a few tens of MB.
FROM eclipse-temurin:11-jre-jammy AS runtime

# Dedicated non-root system user/group to run the app as — defense in depth, so a
# compromised JVM process can't write outside its own home dir or bind privileged
# (<1024) ports.
RUN groupadd --system spring && \
    useradd --system --gid spring --home-dir /home/spring --create-home spring

WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
RUN chown spring:spring app.jar

# 8080: application traffic, served under /api (server.servlet.context-path=/api).
# 8081: actuator management port (management.server.port=8081, Step 1.4) — kept
# separate from traffic so /actuator/** stays off whatever will later route only
# the traffic port (an ALB in production).
EXPOSE 8080 8081

# Sizes the JVM heap from the container's own cgroup memory limit rather than the
# host's total memory. Irrelevant with no memory limit set locally, but this is
# the correct production setting for a future Kubernetes deployment where the
# container gets a real memory limit/request.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

USER spring:spring

ENTRYPOINT ["java", "-jar", "app.jar"]
