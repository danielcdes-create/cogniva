# Nota Técnica: Arquitetura de Microsserviços e Pipeline de CI/CD Custo Zero para VPS (DigitalOcean)

**Data:** 30 de Outubro de 2026  
**Escopo:** Padronização de Stack, Otimização de Recursos, Controle de Custos (Free Tier) e Automação de Deploy Contínuo (CI/CD) via Containers.  
**Infraestrutura Alvo:** Droplet DigitalOcean (Linux) + GitHub Actions + GitHub Container Registry (GHCR) + MongoDB Atlas.

---

## 0. Diretrizes Estritas para Assistentes de IA / LLMs (Context Rules)

> **INSTRUÇÃO PARA O LLM:** Ao analisar este projeto, gerar código, criar scripts de automação ou sugerir alterações de infraestrutura, você **DEVE** cumprir estritamente as seguintes regras arquiteturais:
> 1. **Proibição de Build na VPS:** Nunca gere scripts que executem `cargo build`, `npm run build`, `tsc` ou `docker build` dentro do Droplet da DigitalOcean. Todo processo de compilação deve ocorrer no GitHub Actions ou localmente na máquina de desenvolvimento.
> 2. **Stack Alvo:** Priorize **Rust** (`Axum`/`Actix-web` + `tokio` + driver `mongodb`) para qualquer novo microsserviço ou refatoração de backend. O **Kotlin** atua exclusivamente como aplicação cliente (Mobile/Android) consumindo as APIs REST/gRPC — nunca sugira subir containers baseados em JVM dentro deste Droplet.
> 3. **Preservação do Free Tier (Custo Zero):** Toda solução proposta deve operar dentro dos limites gratuitos do GitHub Free (2.000 min/mês de Actions e 500 MB no GHCR para repositórios privados) e do MongoDB Atlas (Tier M0).
> 4. **Imagens Enxutas e Cache:** Todo `Dockerfile` deve ser *multi-stage*, utilizando bases mínimas no estágio final (`debian:bookworm-slim`, `alpine` ou `distroless`) e mantendo a imagem final abaixo de **50 MB**.
> 5. **Gatilhos Econômicos no CI/CD:** Nos arquivos `.github/workflows/*.yml`, sempre inclua suporte a execução manual (`workflow_dispatch`), filtro de mensagens (`!contains(github.event.head_commit.message, '[skip ci]')`) e limpeza automática de camadas antigas para não esgotar a franquia de minutos ou armazenamento.
> 6. **Containers em Produção:** Nunca utilize flags de desenvolvimento (`--reload`, `--watch`, `nodemon`, `ts-node-dev`) nos comandos `CMD`/`ENTRYPOINT` dos containers destinados ao Droplet.

---

## 1. Sumário Executivo e Diagnóstico Atual

Atualmente, a infraestrutura no Droplet da DigitalOcean executa três aplicações containerizadas via Docker (desenvolvidas em **Rust**, **Python** e **TypeScript/Node.js**), consumindo banco de dados externo via **MongoDB Atlas** e servindo aplicações cliente em **Kotlin (Mobile)**.

### 1.1. Métricas de Saúde do Servidor (Baseline)
A avaliação telemétrica do Droplet demonstra um ambiente estável, porém com oportunidades claras de otimização:
* **Memória RAM (~37,8% de ocupação contínua):** Estabilidade linear que comprova ausência de *memory leaks*. Contudo, a maior fatia desse consumo decorre do *overhead* dos interpretadores/VMs (Node.js V8 e Python) frente ao binário nativo em Rust, que consome cerca de **10% dos recursos** das demais stacks.
* **CPU (9% a 14% constantes em repouso) e Load Average (~0.20):** O patamar contínuo em horários de baixa demanda (madrugada) e os micro-picos regulares são causados por ciclos de *Garbage Collection* (Python/Node), rotinas de *polling* ou modos de *file watching* (`--reload`) ativos em produção.
* **Disco (~28,5% de uso, I/O próximo a 0 B/s):** Excelente saúde de armazenamento, beneficiada pela externalização da persistência de dados para o MongoDB Atlas.

---

## 2. Diretrizes de Arquitetura e Padronização de Stack

Para maximizar a densidade de aplicações no mesmo Droplet sem necessidade de *upgrade* de hardware (escala vertical), estabelecem-se as seguintes diretrizes:

1. **Unificação Gradual do Backend em Rust:**
   * Como os serviços são recentes, recomenda-se a migração progressiva dos backends em Python e TypeScript para **100% Rust**.
   * **Impacto Esperado:** Redução do uso total de RAM da VPS de ~38% para a faixa de **12% a 15%**, uso de CPU em repouso **< 1%** e inicialização de containers na casa dos milissegundos.
2. **Isolamento de Runtimes Pesados:**
   * O **Kotlin** permanece estritamente na camada cliente (Mobile) consumindo as APIs em Rust. Evita-se rodar a JVM no Droplet para não introduzir consumo elevado de memória base (150 MB–300 MB por container).
3. **Persistência Stateless no VPS:**
   * Manter os containers do Droplet totalmente *stateless*, delegando armazenamento e indexação ao **MongoDB Atlas**. Isso preserva o I/O de disco local próximo de zero e simplifica a substituição rápida de containers.

---

## 3. Gestão de Custos e Otimização de Free Tier (Fase de Desenvolvimento)

Toda a arquitetura de CI/CD e registro de containers foi desenhada para operar com **custo adicional zero** ($0/mês além do custo fixo do Droplet):

| Serviço | Limite Gratuito (Repositórios Privados) | Estratégia de Mitigação no Projeto |
| :--- | :--- | :--- |
| **GitHub Actions** | 2.000 minutos / mês | Uso de cache de camadas Docker (`type=gha`) reduzindo builds de Rust de ~6 min para ~1.5 min; uso de `[skip ci]` e gatilhos manuais/tags. |
| **GitHub Packages (GHCR)** | 500 MB de armazenamento e 1 GB de transferência / mês | Uso de *Multi-stage builds* gerando imagens de ~30 MB; sobrescrita da tag `:latest` e exclusão periódica de imagens *untagged*. |
| **MongoDB Atlas** | 512 MB (Cluster M0 Sandbox) | Armazenamento exclusivo de documentos operacionais; logs de aplicação mantidos em disco efêmero no Docker local com rotação de 10 MB. |

*(Nota: Caso os repositórios sejam públicos, os minutos do GitHub Actions e o armazenamento no GHCR tornam-se ilimitados).*

---

## 4. Padrão de Containerização (Multi-Stage Builds com Cache)

Para acelerar a compilação do Rust no GitHub Actions e gerar uma imagem minúscula para o GHCR, utilize este padrão de `Dockerfile` com `cargo-chef` ou cache de dependências:

```dockerfile
# ==========================================
# Estágio 1: Build (Executado no GitHub Actions)
# ==========================================
FROM rust:1.82-slim-bookworm AS builder
WORKDIR /app

# Dependências de sistema necessárias para compilar suporte TLS (MongoDB Atlas)
RUN apt-get update && apt-get install -y pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*

# Truque de Cache: Compila apenas as dependências primeiro se o Cargo.toml não mudou
COPY Cargo.toml Cargo.lock ./
RUN mkdir src && echo "fn main() {}" > src/main.rs && cargo build --release && rm -rf src

# Copia o código-fonte real e compila o binário final
COPY src ./src
RUN touch src/main.rs && cargo build --release

# ==========================================
# Estágio 2: Runtime Enxuto (Enviado ao Droplet)
# ==========================================
FROM debian:bookworm-slim
WORKDIR /app

# Instala apenas certificados raiz para conexões HTTPS / MongoDB Atlas TLS
RUN apt-get update && apt-get install -y ca-certificates libssl3 && rm -rf /var/lib/apt/lists/*

# Executa como usuário não-root por segurança
RUN useradd -m -u 10001 appuser
USER appuser

# Copia apenas o binário compilado (Imagem final ~25MB a 40MB)
COPY --from=builder /app/target/release/meu_app_rust /usr/local/bin/app

EXPOSE 8000
CMD ["app"]
```

---

## 5. Orquestração no Droplet (`docker-compose.yml`)

Centralize todos os serviços do Droplet em um único diretório (`/opt/apps/docker-compose.yml`):

```yaml
services:
  api-rust-core:
    image: ghcr.io/seu_usuario/api-rust-core:latest
    container_name: api-rust-core
    restart: unless-stopped
    env_file: .env.rust
    ports:
      - "127.0.0.1:8001:8000"
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"

  api-python-service:
    image: ghcr.io/seu_usuario/api-python-service:latest
    container_name: api-python-service
    restart: unless-stopped
    env_file: .env.python
    ports:
      - "127.0.0.1:8002:8000"
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"

  api-ts-service:
    image: ghcr.io/seu_usuario/api-ts-service:latest
    container_name: api-ts-service
    restart: unless-stopped
    env_file: .env.ts
    ports:
      - "127.0.0.1:8003:8000"
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"
```

---

## 6. Pipeline de CI/CD Econômico e Controlado (GitHub Actions)

Para não desperdiçar os 2.000 minutos gratuitos durante commits frequentes de desenvolvimento, oferecemos **duas modalidades de gatilho** no arquivo `.github/workflows/deploy.yml`:

1. **Disparo Manual (`workflow_dispatch`) ou por Tag de Versão (`v*`):** O código é salvo normalmente no GitHub a cada `git push`, mas o build/deploy para a DigitalOcean só acontece quando você clicar no botão **"Run workflow"** na aba Actions do GitHub ou quando publicar uma versão (ex: `git tag v1.0.1 && git push --tags`).
2. **Disparo Automático na `main` com suporte a `[skip ci]`:** Se preferir manter o deploy no `push` da branch `main`, basta incluir `[skip ci]` na mensagem do commit (ex: `git commit -m "wip: ajustando model [skip ci]"`) quando não quiser atualizar o Droplet.

### 6.1. Segredos Necessários no Repositório GitHub
Em **Settings > Secrets and variables > Actions**, configure:
* `DO_HOST`: Endereço IP do Droplet.
* `DO_USER`: Usuário SSH do Droplet.
* `DO_SSH_KEY`: Chave privada SSH dedicada ao deploy.

### 6.2. Arquivo de Workflow Otimizado (`.github/workflows/deploy.yml`)
```yaml
name: CI/CD - Build & Deploy to DigitalOcean (Free Tier Optimized)

on:
  # Opção 1: Permite disparar manualmente pelo botão "Run workflow" no GitHub
  workflow_dispatch:

  # Opção 2: Dispara automaticamente apenas ao enviar uma Tag de versão (ex: v1.0.0)
  push:
    tags:
      - 'v*'
    # Caso queira disparar em todo push na main (exceto quando usar [skip ci]), descomente a linha abaixo:
    # branches: [ "main" ]

env:
  REGISTRY: ghcr.io
  IMAGE_NAME: ${{ github.repository }}
  SERVICE_NAME: api-rust-core # Nome exato do serviço no docker-compose.yml

jobs:
  build-and-deploy:
    # Ignora a execução se a mensagem do commit contiver [skip ci]
    if: "!contains(github.event.head_commit.message, '[skip ci]')"
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write

    steps:
      - name: 1. Checkout do código
        uses: actions/checkout@v4

      - name: 2. Configurar Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: 3. Login no GitHub Container Registry (GHCR)
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: 4. Build e Push da Imagem (Com Cache de Camadas)
        uses: docker/build-push-action@v5
        with:
          context: .
          push: true
          tags: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max

      - name: 5. Deploy Automatizado no Droplet via SSH
        uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.DO_HOST }}
          username: ${{ secrets.DO_USER }}
          key: ${{ secrets.DO_SSH_KEY }}
          script: |
            cd /opt/apps
            docker compose pull ${{ env.SERVICE_NAME }}
            docker compose up -d --no-deps ${{ env.SERVICE_NAME }}
            docker image prune -f
```

---

## 7. Checklist de Configuração Única no Servidor (Droplet)

Execute estas verificações uma única vez no terminal do Droplet:

1. **Autenticar o Docker do Droplet no GHCR:**
   Crie um *Personal Access Token (Classic)* no GitHub apenas com o escopo `read:packages` e execute no Droplet:
   ```bash
   echo "SEU_TOKEN_GITHUB" | docker login ghcr.io -u SEU_USUARIO_GITHUB --password-stdin
   ```
2. **Garantir Permissão do Grupo Docker ao Usuário SSH:**
   ```bash
   sudo usermod -aG docker $USER
   ```
3. **Configurar Arquivo de Swap (2 GB) como Margem de Segurança:**
   ```bash
   sudo fallocate -l 2G /swapfile
   sudo chmod 600 /swapfile
   sudo mkswap /swapfile
   sudo swapon /swapfile
   echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
   ```