# ==========================================
# Estágio 1: Build (Executado EXCLUSIVAMENTE no GitHub Actions)
# PROIBIDO executar cargo build ou docker build dentro do Droplet!
# ==========================================
FROM rust:slim-bookworm AS builder
WORKDIR /app

# Dependências de sistema necessárias para compilar suporte TLS (MongoDB Atlas)
RUN apt-get update && apt-get install -y pkg-config libssl-dev ca-certificates && rm -rf /var/lib/apt/lists/*

# Truque de Cache (§4 da Nota Técnica): Compila apenas as dependências primeiro se Cargo.toml/Cargo.lock não mudaram
COPY Cargo.toml Cargo.lock ./
RUN mkdir src && echo "fn main() {}" > src/main.rs && cargo build --release && rm -rf src

# Copia o código-fonte real e compila o binário final (otimizado com LTO e strip via Cargo.toml)
COPY src ./src
RUN touch src/main.rs && cargo build --release

# ==========================================
# Estágio 2: Runtime Enxuto (< 40MB enviado ao GHCR e baixado pelo Droplet)
# ==========================================
FROM debian:bookworm-slim
WORKDIR /app

# Instala apenas certificados raiz para conexões HTTPS / MongoDB Atlas TLS
RUN apt-get update && apt-get install -y ca-certificates libssl3 && rm -rf /var/lib/apt/lists/*

# Executa como usuário não-root por segurança
RUN useradd -m -u 10001 appuser
USER appuser

# Copia apenas o binário compilado (Imagem final ~25MB a 40MB)
COPY --from=builder /app/target/release/api-rust-core /usr/local/bin/app

EXPOSE 8000
CMD ["app"]
