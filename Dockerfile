FROM maven:3.9-eclipse-temurin-25-noble@sha256:7e461cec477077c1d9e50b13df8aef9018764410f4c4cd7c34803f10c4c99e4c AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
COPY docs ./docs
# GitHub runners can inject a Unix OTEL socket that is invalid for Spring's
# build-time test context. Runtime observability remains deployment-configured.
RUN OTEL_SDK_DISABLED=true \
    OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4317 \
    OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://127.0.0.1:4317 \
    OTEL_EXPORTER_OTLP_METRICS_ENDPOINT=http://127.0.0.1:4318 \
    OTEL_EXPORTER_OTLP_LOGS_ENDPOINT=http://127.0.0.1:4317 \
    mvn -B -ntp package

FROM eclipse-temurin:25-jre-noble@sha256:2f1da100788559b397bcf48c736169ea5b070bde84e55f203bbee8e83d87a175

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 nexa \
    && useradd --system --uid 10001 --gid 10001 --no-create-home nexa

WORKDIR /app

COPY --from=build /workspace/target/api-*.jar /app/app.jar
COPY ops/healthcheck.sh /usr/local/bin/nexa-healthcheck

RUN chmod 0755 /usr/local/bin/nexa-healthcheck \
    && chown -R 10001:10001 /app

ENV SERVER_PORT=8080

EXPOSE 8080

USER nexa

HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=12 \
    CMD ["/usr/local/bin/nexa-healthcheck"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
