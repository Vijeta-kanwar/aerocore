# syntax=docker/dockerfile:1

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# pom.xml alone first: dependency resolution re-runs only when dependencies change,
# not on every source edit.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B clean package -DskipTests

# Split the fat jar into layers ordered by how often they change, so a code-only
# change pushes a few hundred KB instead of the whole image.
RUN java -Djarmode=layertools -jar target/aerocore.jar extract --destination extracted

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:17-jre-jammy

# Debian base, so Debian tooling: apt-get / groupadd / useradd.
# (Mixing Alpine commands with a Debian base is exactly the bug that broke v1 of this file.)
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -g 1000 aerocore \
 && useradd -u 1000 -g aerocore -m -s /usr/sbin/nologin aerocore

WORKDIR /app

COPY --from=builder --chown=aerocore:aerocore /build/extracted/dependencies/ ./
COPY --from=builder --chown=aerocore:aerocore /build/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=aerocore:aerocore /build/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=aerocore:aerocore /build/extracted/application/ ./

USER aerocore

EXPOSE 8080

# MaxRAMPercentage sizes the heap from the container's memory limit instead of a
# hardcoded -Xmx that must be kept in sync with the k8s manifest.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

HEALTHCHECK --interval=30s --timeout=3s --start-period=45s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
