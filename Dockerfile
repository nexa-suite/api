FROM maven:3.9-eclipse-temurin-26 AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:26-jre

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
