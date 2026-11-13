# syntax=docker/dockerfile:1
# Context is repository root. Never build this image on the VPS.
FROM rust:1.93.1-alpine AS builder
RUN apk add --no-cache musl-dev gcc make
WORKDIR /app/backend-rust
COPY backend-rust/Cargo.toml backend-rust/Cargo.lock ./
COPY backend-rust/src ./src
COPY shared /app/shared
COPY web-admin /app/web-admin
ARG BUILD_REVISION=local
ENV BUILD_REVISION=${BUILD_REVISION}
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/app/backend-rust/target \
    cargo build --locked --release && \
    install -d /out/bin && \
    cp target/release/cogniva-api /out/bin/cogniva-api && \
    ln -s cogniva-api /out/bin/app

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=builder /out/bin/ /usr/local/bin/
ARG BUILD_REVISION=local
LABEL org.opencontainers.image.source="https://github.com/danielcdes-create/cogniva" \
      org.opencontainers.image.title="cogniva-api" \
      org.opencontainers.image.revision=${BUILD_REVISION}
WORKDIR /app
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 CMD ["/usr/local/bin/cogniva-api", "--health-check"]
ENTRYPOINT ["/usr/local/bin/cogniva-api"]
