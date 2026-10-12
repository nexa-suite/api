FROM --platform=$BUILDPLATFORM golang:1.24.8-alpine3.22@sha256:3d78beb141d98f42337f1252ecf2a5f20374109929a4c3f6817f9e4179cc0ae5 AS minio-build

ARG TARGETARCH
ARG MINIO_RELEASE=RELEASE.2025-10-15T17-29-55Z
ARG MINIO_COMMIT=9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a

RUN apk add --no-cache git
WORKDIR /src
RUN git init . \
    && git remote add origin https://github.com/minio/minio.git \
    && git fetch --depth=1 origin "refs/tags/${MINIO_RELEASE}" \
    && git checkout --detach FETCH_HEAD \
    && test "$(git rev-parse HEAD)" = "${MINIO_COMMIT}"
RUN mkdir -p /out \
    && ldflags="$(MINIO_RELEASE=RELEASE go run buildscripts/gen-ldflags.go 2025-10-15T17:29:55Z)" \
    && CGO_ENABLED=0 GOOS=linux GOARCH="${TARGETARCH}" go build -tags kqueue -trimpath \
       --ldflags "${ldflags}" -o /out/minio .

FROM --platform=$BUILDPLATFORM golang:1.24.8-alpine3.22@sha256:3d78beb141d98f42337f1252ecf2a5f20374109929a4c3f6817f9e4179cc0ae5 AS mc-build

ARG TARGETARCH
ARG MC_RELEASE=RELEASE.2025-08-13T08-35-41Z
ARG MC_COMMIT=7394ce0dd2a80935aded936b09fa12cbb3cb8096

RUN apk add --no-cache git
WORKDIR /src
RUN git init . \
    && git remote add origin https://github.com/minio/mc.git \
    && git fetch --depth=1 origin "refs/tags/${MC_RELEASE}" \
    && git checkout --detach FETCH_HEAD \
    && test "$(git rev-parse HEAD)" = "${MC_COMMIT}"
RUN mkdir -p /out \
    && ldflags="$(MC_RELEASE=RELEASE go run buildscripts/gen-ldflags.go 2025-08-13T08:35:41Z)" \
    && CGO_ENABLED=0 GOOS=linux GOARCH="${TARGETARCH}" go build -tags kqueue -trimpath \
       --ldflags "${ldflags}" -o /out/mc .

FROM alpine:3.22.2@sha256:4b7ce07002c69e8f3d704a9c5d6fd3053be500b7f1c69fc0d80990c2ad8dd412

ENV MC_CONFIG_DIR=/tmp/.mc
COPY --from=minio-build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=minio-build /out/minio /usr/bin/minio
COPY --from=minio-build /src/dockerscripts/docker-entrypoint.sh /usr/bin/docker-entrypoint.sh
COPY --from=minio-build /src/LICENSE /licenses/minio-LICENSE
COPY --from=mc-build /out/mc /usr/bin/mc
COPY --from=mc-build /src/LICENSE /licenses/mc-LICENSE
RUN addgroup -S -g 10001 minio \
    && adduser -S -D -H -u 10001 -G minio minio \
    && mkdir -p /data \
    && chown minio:minio /data \
    && chmod 0755 /usr/bin/minio /usr/bin/mc /usr/bin/docker-entrypoint.sh

EXPOSE 9000 9001
VOLUME ["/data"]
USER 10001:10001
ENTRYPOINT ["/usr/bin/docker-entrypoint.sh"]
CMD ["minio"]
