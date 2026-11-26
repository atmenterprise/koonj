# =============================================================================
# Koonj Server — Dockerfile
# =============================================================================
# This Dockerfile expects the pre-built JAR to be in the build context:
#   docker build -f Dockerfile.server -t koonj-server .
#
# The deployment ZIP provides the executable JAR.
# =============================================================================
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Koonj <support@appsthatmatter.uk>"
LABEL org.opencontainers.image.title="koonj-server"
LABEL org.opencontainers.image.description="Koonj Data Catalog API Server"

# ── Security: run as non-root ────────────────────────────────────────────────
RUN addgroup -S koonj && adduser -S -G koonj -h /opt/koonj koonj

# ── Runtime dependencies ────────────────────────────────────────────────────
RUN apk add --no-cache curl tini

# ── Application layout ──────────────────────────────────────────────────────
WORKDIR /opt/koonj
COPY koonj-server.jar /opt/koonj/app.jar

# ── JVM tuning (container-aware defaults) ────────────────────────────────────
ENV JAVA_OPTS="-XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:InitialRAMPercentage=50.0 \
  -Djava.security.egd=file:/dev/./urandom"

# ── Spring Boot configuration via environment ───────────────────────────────
# All config is injected through environment variables.
# See .env.example for the full list.
ENV SPRING_PROFILES_ACTIVE="production"

EXPOSE 8080

USER koonj

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -sf http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["tini", "--"]
CMD ["sh", "-c", "exec java ${JAVA_OPTS} -jar /opt/koonj/app.jar"]
