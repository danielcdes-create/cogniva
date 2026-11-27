# syntax=docker/dockerfile:1
# Context is repository root. Never build this image on the VPS.
FROM rust:1.93.1-alpine AS manifest-norm
WORKDIR /norm
COPY backend-rust/Cargo.toml backend-rust/Cargo.lock ./
RUN awk 'BEGIN{done=0} !done && /^version = "/{print "version = \"0.0.0\""; done=1; next} {print}' Cargo.toml > Cargo.toml.norm && \
    mv Cargo.toml.norm Cargo.toml && \
    awk 'BEGIN{in_pkg=0} /^\[\[package\]\]/{in_pkg=0} /^name = "cogniva-api"$/{in_pkg=1} in_pkg && /^version = "/{print "version = \"0.0.0\""; in_pkg=0; next} {print}' Cargo.lock > Cargo.lock.norm && \
    mv Cargo.lock.norm Cargo.lock

FROM rust:1.93.1-alpine AS builder
RUN apk add --no-cache musl-dev gcc make
WORKDIR /app/backend-rust
COPY --from=manifest-norm /norm/Cargo.toml /norm/Cargo.lock ./
RUN mkdir src && printf 'fn main() {}\n' > src/main.rs && \
    cargo build --locked --release && \
    rm -rf src target/release/cogniva-api target/release/deps/cogniva_api* target/release/.fingerprint/cogniva-api-*
COPY backend-rust/Cargo.toml backend-rust/Cargo.lock ./
COPY backend-rust/src ./src
COPY shared /app/shared
COPY web-admin /app/web-admin
COPY web-missions /app/web-missions
ARG BUILD_REVISION=local
ENV BUILD_REVISION=${BUILD_REVISION}
RUN touch src/main.rs && \
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
USER 65532:65532
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 CMD ["/usr/local/bin/cogniva-api", "--health-check"]
ENTRYPOINT ["/usr/local/bin/cogniva-api"]
