FROM maven:3.9-eclipse-temurin-25-noble@sha256:dd8e01b3be719853578c07b57ff8d9bbbbfe746f802226f05b19689420815221 AS build

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

FROM eclipse-temurin:25-jre-noble@sha256:b573af9e331196fbc42e246da4df24df9b6c556c73e7efddfde0511f1c9508c5

RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
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
