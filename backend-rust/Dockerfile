# syntax=docker/dockerfile:1
# Context is repository root. Never build this image on the VPS.
FROM rust:1.93.1-alpine AS builder
RUN apk add --no-cache musl-dev gcc make
WORKDIR /app/backend-rust
COPY backend-rust/Cargo.toml backend-rust/Cargo.lock ./
COPY backend-rust/src ./src
COPY shared /app/shared
ARG BUILD_REVISION=local
ENV BUILD_REVISION=${BUILD_REVISION}
RUN --mount=type=cache,target=/usr/local/cargo/registry --mount=type=cache,target=/app/backend-rust/target cargo build --locked --release && cp target/release/api-rust-core /server

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=builder /server /usr/local/bin/app
ARG BUILD_REVISION=local
LABEL org.opencontainers.image.revision=${BUILD_REVISION}
WORKDIR /app
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=8s --start-period=20s --retries=3 CMD ["/usr/local/bin/app", "--health-check"]
ENTRYPOINT ["/usr/local/bin/app"]
