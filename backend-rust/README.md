# 🦀 Cogniva Backend (`cogniva-api` `2.2.0`)

Serviço HTTP assíncrono em **Rust (`Axum 0.7` + `Tokio` + `MongoDB 3.1`)** responsável pela API REST `/api/v1/*`, pelos **5 Motores de Decisão** (`contract_version: 4`), pelo servidor estático incorporado do painel administrativo `/admin/`, pela distribuição OTA do APK Android e pelas rotinas agendadas de manutenção pedagógica sobre o banco **`cogniva`**.

Para a visão integrada do sistema, consulte [`docs/ARQUITETURA.md`](../docs/ARQUITETURA.md), [`docs/MOTORES-DECISAO-CORE-LOOP.md`](../docs/MOTORES-DECISAO-CORE-LOOP.md), [`docs/OPERACAO.md`](../docs/OPERACAO.md), [`PRIVACY.md`](../PRIVACY.md) e [`SECURITY.md`](../SECURITY.md).

---

## 1. Principais Responsabilidades

- **Autenticação Nativa, Blindagem CSRF/IP e LGPD (`routes/auth_session.rs`, `routes/web_admin.rs`, `security.rs`, `services/rate_limit.rs`):**
  - Modo convidado imediato (`POST /api/v1/auth/guest`), cadastro com promoção *in-place* do mesmo `user_id` (`POST /api/v1/auth/register`), login com `Argon2id` (`POST /api/v1/auth/login`), logout com revogação na coleção `sessions` (`POST /api/v1/auth/logout`), perfil (`GET /api/v1/auth/me`) e exclusão física de dados pessoais do titular (`DELETE /api/v1/auth/account` com `{"confirm": "EXCLUIR"}`, preservando 100% do acervo compartilhado).
  - Sessões administrativas web com cookie `HttpOnly` + `SameSite=Strict` + `Secure`, validação combinada `Sec-Fetch-Site` + `X-Forwarded-Proto` + `Origin` + `X-Cogniva-CSRF: 1` e limitador deslizante em memória por IP (`BoundedSlidingWindowLimiter`).
- **Catálogo em Execução e Templates Globais (`services/catalog_runtime.rs`, `routes/exams.rs`, `services/exam_store.rs`):**
  - Snapshot do catálogo canônico (`shared/knowledge-catalog.json` v2) mantido em cache `RwLock` com TTL de 10 segundos e invalidação instantânea após `POST /api/v1/admin/catalog/activate`.
  - Separação estrita entre `exam_templates` (globais, geridos no `/admin/`) e `user_exams` (grades individuais dos alunos), com detecção automática de `upstream_update_available` e adoção explícita via `POST /api/v1/exams/:id/adopt-template`.
- **Acervo 100% V2.2, 5 Motores de Decisão e Sincronização (`services/question_storage.rs`, `services/attempts.rs`, `services/*_engine.rs`, `routes/study_loop.rs`):**
  - Validação estrita contra o contrato V2.2 (`question-v2-validator-2.2.0`, `CURRENT_VALIDATOR_REVISION = 5`), hash canônico JCS SHA-256 (RFC 8785), suporte a `concept_id` e `evidence_challenge`, e outbox de auditoria.
  - Motores modulares de **Diagnóstico**, **Retenção Contínua** ($R(t,S)$), **Priorização** (40/40/20 + DAG), **Calibração Beta-Binomial / Estratégia** ($VE$ + Holdout 20% + Fallback Cross-Banca), **Fadiga** (guarda de amostra mínima) e endpoint `GET /api/v1/study/next-action`.
  - Replay cronológico de lotes offline fora de ordem (`client_answered_at`), tratamento dedicado de omissões estratégicas (`is_blank = true`) e isolamento de reportes abertos (`reportedQuestoes`).
- **Governança xAI, Bússola Pré-Resposta e Privacidade de Cache (`services/ai_tutor.rs`, `services/classification.rs`, `services/question_import.rs`, `services/rate_limit.rs`):**
  - Delimitação XML `<untrusted_student_input>` / `<untrusted_raw_source>` contra injeção indireta de prompt, não-persistência em cache compartilhado quando há `user_note`/resposta pessoal, cota diária em `America/Sao_Paulo` (`UTC-3`) com teto reduzido para convidados (`5/dia`) e estorno automático em falhas.
  - Tutores pedagógicos `ai-tutor/3` com separação estrita entre pré-resposta (`child-friendly-pre`, `contextual-hint`, `dr-sincero` — gabarito enviado como `bussola_gabarito_sigiloso_nao_revelar` para zero alucinação, instrução de abstração em perguntas-guia e filtro determinístico `detect_pre_answer_spoiler` com retry corretivo antes do retorno/cache) e pós-resposta (`explanation`, `extra-concepts`, `child-friendly-post`, `mnemonic`, `plausibility` — fundamentação explícita de `correct_answer`).
- **Prontidão Real, Shutdown Gracioso e Distribuição OTA (`main.rs`, `routes/apk_ota.rs`):**
  - `GET /api/v1/ready` retorna HTTP `503` se o ping ao MongoDB falhar; servidor encerra conexões e agendador graciosamente sob `SIGTERM`/`Ctrl+C`; entrega streaming de 64 KiB em `/api/v1/apk/download` com suporte a `ETag` / `If-None-Match` (`304`).

---

## 2. Variáveis de Ambiente Essenciais

Consulte o modelo completo em [`.env.example`](../.env.example):

| Variável | Descrição |
| :--- | :--- |
| `MONGODB_URI` | String de conexão do MongoDB (obrigatória). |
| `MONGODB_DB_NAME` | Nome do banco de dados ativo (padrão: `cogniva`). |
| `JWT_SECRET` | Segredo HS256 com no mínimo 32 caracteres aleatórios (obrigatório). |
| `COGNIVA_PUBLIC_ORIGIN` | Origem pública HTTPS autorizada para CORS e validação CSRF do `/admin/`. |
| `ADMIN_EMAILS` | Lista separada por vírgulas de e-mails com papel `admin` automático. |
| `XAI_API_KEY` / `XAI_MODEL` | Chave privada da xAI e identificador explícito do modelo. |
| `XAI_DAILY_USER_LIMIT` / `XAI_DAILY_GLOBAL_LIMIT` | Limites diários de chamadas pagas por conta registrada (padrão `120`) e global (padrão `500`). |

---

## 3. Comandos CLI de Manutenção

```bash
# Instalar/verificar validadores BSON, índices operacionais/TTL, templates globais e snapshot do catálogo
cogniva-api --prepare-storage

# Conceder papel administrativo a uma conta já registrada por e-mail
cogniva-api --grant-admin admin@exemplo.com
```

---

## 4. Execução e Testes Locais

```bash
# Lint estrito sem avisos (Clippy)
cargo clippy --manifest-path backend-rust/Cargo.toml --all-targets --locked -- -D warnings

# Rodar suíte de testes unitários, roteamento e contratos
cargo test --manifest-path backend-rust/Cargo.toml --locked

# Rodar suíte completa incluindo integração em MongoDB local isolado (nunca usar URI de produção!)
COGNIVA_TEST_MONGODB_URI="mongodb://127.0.0.1:27048/" \
  cargo test --manifest-path backend-rust/Cargo.toml --locked -- --include-ignored --test-threads=1
```
