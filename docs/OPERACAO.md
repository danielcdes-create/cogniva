# Manual de Operação e Produção — Cogniva 2.0

Este guia documenta a operação segura do **Cogniva `2.0.0`** na VPS DigitalOcean, incluindo credenciais, migração do acervo `CNU → cogniva`, administração de contas, TLS IPv4/IPv6, promoção atômica e rollback verificado.

---

## 1. Princípios de Runtime na VPS

- **Execução de Artefatos Imutáveis:** A VPS nunca compila código Rust, Android, Node.js ou imagens Docker. Todos os artefatos são construídos e verificados nos workflows do GitHub Actions e promovidos por digest SHA-256 via [`deploy/promote.py`](../deploy/promote.py).
- **Isolamento em Servidor Compartilhado:** A promoção opera com `/opt/apps/docker-compose.yml` + override `/opt/apps/cogniva-runtime.override.json`, preservando o nome padrão do serviço Compose `api-rust-core` (`COGNIVA_COMPOSE_SERVICE`) e inspecionando a integridade dos demais contêineres vizinhos antes e depois da troca.
- **Limites de Recursos:** Container `read_only: true`, `160 MB` de memória, `0.75` CPU, `pids_limit: 128`, sem privilégios (`no-new-privileges:true`) e rotação de logs `10m × 3`.

---

## 2. Variáveis Privadas e Concessão Administrativa

As credenciais residem exclusivamente no ambiente privado do servidor (`/opt/apps/.env.rust`, modelo em [`.env.example`](../.env.example)):

- **Banco de Dados:** `MONGODB_URI` privada e `MONGODB_DB_NAME=cogniva`.
- **Segurança de Sessão:** `COGNIVA_JWT_SECRET` (mínimo de 32 caracteres aleatórios) e `COGNIVA_PUBLIC_ORIGIN` (ex.: `https://198.199.65.103` ou domínio HTTPS configurado).
- **Governança de IA:** `XAI_API_KEY`, `XAI_MODEL` (obrigatório quando `XAI_API_KEY` está definido), `XAI_DAILY_USER_LIMIT=120` e `XAI_DAILY_GLOBAL_LIMIT=500`.
- **Administradores Nativos (`ADMIN_EMAILS` e CLI):**
  - Contas cujo e-mail estiver listado em `ADMIN_EMAILS` recebem automaticamente `role = "admin"` ao criar conta ou autenticar.
  - Para promover manualmente uma conta já registrada sem editar variáveis de ambiente:
    ```bash
    docker exec api-rust-core /usr/local/bin/cogniva-api --grant-admin operador@dominio.com
    ```

---

## 3. Migração Read-Only `CNU → cogniva` e Preparação do Banco

O banco legado `CNU` permanece intocado (*read-only*). A migração inicial ou incremental do acervo de questões para o banco `cogniva` (100% `schema_version = 2` com `content_revision` JCS SHA-256) é realizada por [`scripts/migrate_cnu_to_cogniva.py`](../scripts/migrate_cnu_to_cogniva.py):

```bash
# 1. Simulação sem escrita (valida 100% das questões convertidas contra o JSON Schema v2)
python3 scripts/migrate_cnu_to_cogniva.py --source-db CNU --target-db cogniva --dry-run

# 2. Execução idempotente (lê CNU apenas para leitura e grava em cogniva.todasQuestoes)
python3 scripts/migrate_cnu_to_cogniva.py --source-db CNU --target-db cogniva --apply
```

Durante toda promoção de release, [`deploy/promote.py`](../deploy/promote.py) executa automaticamente antes de abrir o tráfego:

```bash
cogniva-api --prepare-question-storage
```

Esse comando instala e verifica os validadores BSON, índices das coleções (`todasQuestoes`, `usuarios`, `sessions`, `exam_templates`, `user_exams`, `userActivityLog`, `question_reports`, `ai_daily_quotas`), os templates globais de [`shared/exam-templates.json`](../shared/exam-templates.json) e o snapshot versionado de [`shared/knowledge-catalog.json`](../shared/knowledge-catalog.json). Qualquer incompatibilidade aborta a promoção antes de expor a versão ao público.

---

## 4. Nginx, Gate de Manutenção e TLS (IPv4 + IPv6)

- **Configuração Nginx ([`deploy/nginx-cogniva.conf`](../deploy/nginx-cogniva.conf) e [`deploy/nginx-rate-limits.conf`](../deploy/nginx-rate-limits.conf)):**
  - Escuta TLS em IPv4 e IPv6 com redirecionamento HTTP → HTTPS (exceto `/.well-known/acme-challenge/`).
  - Aplica rate-limiting dedicado às rotas de autenticação (`/api/v1/auth/*`), rotas administrativas e API geral.
  - Mantém as rotas binárias de APK (`/api/v1/apk/download`, `/apk`, `/cogniva.apk`) com `gzip off`, `proxy_buffering off` e `Cache-Control: no-store, no-transform` (conforme [`LICAO-APRENDIDA-OTA-APK.md`](LICAO-APRENDIDA-OTA-APK.md)).
- **Bootstrap e Renovação TLS ([`deploy/bootstrap-ip-tls.sh`](../deploy/bootstrap-ip-tls.sh)):**
  - Provisiona o timer systemd `cogniva-renew-tls.timer` e executa renovação horária via Certbot usando `--deploy-hook "nginx -t && systemctl reload nginx"`, recarregando o Nginx apenas quando um novo certificado válido foi efetivamente emitido.

---

## 5. Promoção Atômica, Ensaio de Rollback e Verificação

O fluxo de promoção em [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml) e [`deploy/promote.py`](../deploy/promote.py) segue cinco etapas estritas:

1. **Gate `503`:** Ativa `/etc/nginx/snippets/cogniva-maintenance.conf` bloqueando mutações/leituras de dados enquanto preserva `/api/v1/health`.
2. **Preparação:** Executa `--prepare-question-storage` com a nova imagem candidata.
3. **Ensaio Controlado de Rollback (`--exercise-rollback`):** Provoca falha sintética pós-subida, exige restauração real da imagem e do symlink `/opt/apps/apk/current` anteriores e verifica `ROLLBACK_HEALTHY` (sem suprimir erros com `|| true`).
4. **Troca Atômica:** Atualiza container por digest e troca atomicamente `/opt/apps/apk/current` (APK assinado + `output-metadata.json`), validando `/api/v1/ready`, revisão Git, metadados do APK e rejeição `401` em rota privada anônima.
5. **Reabertura e Aceite Read-Only:** Remove o gate de manutenção e permite auditoria pós-deploy sem mutar dados de produção:

```bash
python3 scripts/verify-production-release.py <GIT_SHA> \
  --url https://198.199.65.103 \
  --version-name 2.0.0 \
  --version-code 25
```
