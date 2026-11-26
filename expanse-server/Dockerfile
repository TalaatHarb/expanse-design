# Headless Expanse Design server. Build context must contain the server jar:
#   docker build -f expanse-server/Dockerfile --build-arg JAR=expanse-server-<version>.jar -t expanse-server <dir-with-jar>
# Run (EXPANSE_API_TOKEN is required, 32+ URL-safe characters):
#   docker run -p 8765:8765 -e EXPANSE_API_TOKEN=... -v expanse-data:/data expanse-server
FROM eclipse-temurin:25-jre

ARG JAR=expanse-server.jar
ARG VERSION=dev
ARG REVISION=unknown

LABEL org.opencontainers.image.title="Expanse Design server" \
      org.opencontainers.image.description="Headless Expanse Design REST server (H2 by default, PostgreSQL via SPRING_PROFILES_ACTIVE=postgres)" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

RUN groupadd --system expanse && useradd --system --gid expanse --home-dir /data expanse \
    && mkdir -p /data /app && chown expanse:expanse /data

COPY --chown=root:root ${JAR} /app/expanse-server.jar

# Inside a container the loopback default would be unreachable; publish the port deliberately
# and put TLS in front of it for anything beyond a trusted network.
ENV SERVER_ADDRESS=0.0.0.0 \
    EXPANSE_DATA_DIR=/data \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

USER expanse
WORKDIR /data
VOLUME ["/data"]
EXPOSE 8765

ENTRYPOINT ["java", "-jar", "/app/expanse-server.jar"]
