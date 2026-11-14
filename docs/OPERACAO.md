# Manual de Operação e Produção — Cogniva 2.0

Este guia documenta a operação segura do **Cogniva `2.0.1`** (`build 26`) na VPS DigitalOcean, incluindo credenciais, migração do acervo `CNU → cogniva`, administração de contas, conformidade LGPD, TLS IPv4/IPv6, promoção atômica e rollback verificado.

---

## 1. Princípios de Runtime na VPS

- **Execução de Artefatos Imutáveis:** A VPS nunca compila código Rust, Android, Node.js ou imagens Docker. Todos os artefatos são construídos, assinados e verificados com `SHA256SUMS` nos workflows do GitHub Actions e promovidos por digest SHA-256 via [`deploy/promote.py`](../deploy/promote.py).
- **Isolamento em Servidor Compartilhado:** A promoção opera com `/opt/apps/docker-compose.yml` + override `/opt/apps/cogniva-runtime.override.json`, preservando o nome padrão do serviço Compose `api-rust-core` (`COGNIVA_COMPOSE_SERVICE`) e inspecionando individualmente a integridade dos contêineres vizinhos persistentes (ignorando jobs efêmeros `--rm` como `certbot`) antes e depois da troca.
- **Limites de Recursos e Higiene de Disco:** Container `USER 65532:65532`, `read_only: true`, `192 MB` de memória (`mem_limit: 192m`), `0.75` CPU, `pids_limit: 128`, `cap_drop: ["ALL"]`, `no-new-privileges: true`, rotação de logs `10m × 3`, *hardlink* (`os.link`) para APKs e retenção automática das 3 releases mais recentes + alvo de rollback ativo (`prune_old_releases`).

---

## 2. Variáveis Privadas, Concessão Administrativa e LGPD

As credenciais residem exclusivamente no ambiente privado do servidor (`/opt/apps/.env.rust`, modelo em [`.env.example`](../.env.example)):

- **Banco de Dados:** `MONGODB_URI` privada e `MONGODB_DB_NAME=cogniva`.
- **Segurança de Sessão:** `JWT_SECRET` (mínimo de 32 caracteres aleatórios) e `COGNIVA_PUBLIC_ORIGIN` (ex.: `https://198.199.65.103` ou domínio HTTPS configurado).
- **Governança de IA:** `XAI_API_KEY`, `XAI_MODEL` (obrigatório quando `XAI_API_KEY` está definido), `XAI_DAILY_USER_LIMIT=120` (contas convidadas limitadas a `5/dia`) e `XAI_DAILY_GLOBAL_LIMIT=500` no fuso `America/Sao_Paulo` (`UTC-3`).
- **Administradores Nativos (`ADMIN_EMAILS`, CLI e GitHub Actions):**
  - Contas cujo e-mail estiver listado em `ADMIN_EMAILS` recebem automaticamente `role = "admin"` ao criar conta ou ao autenticar.
  - A concessão pode ser disparada remotamente de forma auditada via `workflow_dispatch` em [`.github/workflows/admin-ops.yml`](../.github/workflows/admin-ops.yml) ou diretamente na VPS:
    ```bash
    docker exec api-rust-core /usr/local/bin/cogniva-api --grant-admin operador@dominio.com
    ```
- **Exclusão de Conta do Titular (LGPD Art. 18):**
  - `DELETE /api/v1/auth/account` com corpo `{"confirm": "EXCLUIR"}` elimina fisicamente os dados pessoais do titular (`usuarios`, `sessions`, `userActivityLog`, `attempt_receipts`, `user_exams`, `exam_revisions` de usuário, `question_exam_compatibility`, `answer_evaluations`, `tutor_budgets` individuais, rascunhos privados e requisições de IA), anonimiza `reportedQuestoes` e preserva integralmente o acervo compartilhado (`todasQuestoes`, `question_revision_events`, `knowledge_catalog_*` e `exam_templates`). Consulte [`PRIVACY.md`](../PRIVACY.md).

---

## 3. Migração Read-Only `CNU → cogniva` e Preparação do Banco

O banco legado `CNU` permanece intocado (*read-only*). A migração inicial ou incremental do acervo de questões para o banco `cogniva` (100% `schema_version = 2` com `content_revision` JCS SHA-256) é realizada por [`scripts/migrate_cnu_to_cogniva.py`](../scripts/migrate_cnu_to_cogniva.py):

```bash
# 1. Simulação sem escrita (acervo integral ou seletivo por concurso, ex.: Banco Central)
python3 scripts/migrate_cnu_to_cogniva.py --source-db CNU --target-db cogniva --contest-filter "banco central|bacen|\bbcb\b" --dry-run

# 2. Execução idempotente (lê CNU apenas para leitura e grava concurso + questões v2 em cogniva)
python3 scripts/migrate_cnu_to_cogniva.py --source-db CNU --target-db cogniva --contest-filter "banco central|bacen|\bbcb\b" --apply
```

Durante toda promoção de release, [`deploy/promote.py`](../deploy/promote.py) executa automaticamente antes de abrir o tráfego:

```bash
cogniva-api --prepare-storage
```

Esse comando instala e verifica os validadores BSON, índices operacionais e TTL das coleções (`todasQuestoes`, `usuarios`, `sessions`, `auth_rate_limits`, `exam_templates`, `user_exams`, `userActivityLog`, `attempt_receipts`, `reportedQuestoes`, `tutor_budgets`), os templates globais de [`shared/exam-templates.json`](../shared/exam-templates.json) e o snapshot versionado de [`shared/knowledge-catalog.json`](../shared/knowledge-catalog.json). Qualquer incompatibilidade estrutural aborta a promoção antes de expor a versão ao público.

---

## 4. Nginx, Gate de Manutenção e TLS

- **Configuração Nginx ([`deploy/nginx-cogniva.conf`](../deploy/nginx-cogniva.conf) e [`deploy/nginx-rate-limits.conf`](../deploy/nginx-rate-limits.conf)):**
  - Vincula `:443` apenas ao IPv4 público (habilitando IPv6 `:443` via [`deploy/bootstrap-ip-tls.sh`](../deploy/bootstrap-ip-tls.sh) exclusivamente quando o certificado possui SAN IPv6 válido), com `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Permissions-Policy` restritiva e `ssl_session_tickets off`.
  - Aplica rate-limiting com normalização de sub-rede IPv6 `/64` (`$cogniva_limit_key`, zonas de `4m`) às rotas de autenticação (`/api/v1/auth/(login|register|guest|logout|web-session|web-logout|account)`), IA (`cogniva_ai`), download OTA (`cogniva_apk`) e API geral (`cogniva_api`).
  - Durante manutenção (`$cogniva_maintenance = 1` em `/etc/nginx/cogniva-gate.conf`), retorna JSON estruturado HTTP `503` com `Retry-After: 15` e `Cache-Control: no-store`.
  - Mantém `/api/v1/apk/download` com `gzip off` e `proxy_buffering off` (conforme [`LICAO-APRENDIDA-OTA-APK.md`](LICAO-APRENDIDA-OTA-APK.md)).
- **Bootstrap e Renovação TLS ([`deploy/bootstrap-ip-tls.sh`](../deploy/bootstrap-ip-tls.sh)):**
  - Provisiona `cogniva-renew-tls.timer` (execução horária com verificação `openssl x509 -checkend 129600`), recarregando o Nginx apenas quando o certificado renovado difere em SHA-256.

---

## 5. Promoção Atômica, Ensaio de Rollback e Verificação

O fluxo de promoção em [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml) e [`deploy/promote.py`](../deploy/promote.py) segue cinco etapas estritas:

1. **Verificação `SHA256SUMS` e Ensaio Controlado de Rollback (`rollback-drill`):** Exercita `promote.py --exercise-rollback` em pilha Docker isolada antes de conectar à VPS, exigindo `CONTROLLED_ROLLBACK_VERIFIED`.
2. **Gate `503`:** Ativa `/etc/nginx/cogniva-gate.conf` (`set $cogniva_maintenance 1;`) bloqueando tráfego externo com `Retry-After: 15` enquanto preserva `/api/v1/health`.
3. **Troca Atômica e Prontidão:** Atualiza o container por digest imutável, vincula `/opt/apps/apk/current` ao novo APK assinado + `output-metadata.json` e aguarda `/api/v1/ready` (`contract_version: 3` e `revision` exata).
4. **Preparação e Higiene:** Executa `cogniva-api --prepare-storage`, reabre o gate (`set $cogniva_maintenance 0;`), grava `/opt/apps/cogniva-current-release.json` e poda releases antigas excedentes.
5. **Aceite Read-Only Pós-Promoção:** Audita saúde, prontidão, headers TLS/HSTS, validade restante do certificado (`>= 48h`), download integral do APK com SHA-256 e estado dos timers na VPS sem mutar dados de produção:

```bash
python3 scripts/verify-production-release.py <GIT_SHA> \
  --origin https://198.199.65.103 \
  --version-name 2.0.1 \
  --version-code 26
```
