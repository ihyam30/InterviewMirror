FROM golang:1.24-alpine AS build

ARG MINIO_VERSION=RELEASE.2025-10-15T17-29-55Z
ENV CGO_ENABLED=0

RUN go install -v "github.com/minio/minio@${MINIO_VERSION}"

FROM alpine:3.22

RUN addgroup -S minio \
    && adduser -S -G minio minio \
    && mkdir -p /data \
    && chown minio:minio /data

COPY --from=build /go/bin/minio /usr/local/bin/minio
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt

USER minio
WORKDIR /data
VOLUME ["/data"]
EXPOSE 9000 9001
HEALTHCHECK --interval=5s --timeout=3s --start-period=15s --retries=24 CMD wget -q -O /dev/null http://127.0.0.1:9000/minio/health/ready || exit 1
ENTRYPOINT ["minio"]
CMD ["server", "/data", "--console-address", ":9001"]
