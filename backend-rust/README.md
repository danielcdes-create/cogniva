# 🦀 Cogniva Backend (`cogniva-api` `2.0.0`)

Serviço HTTP assíncrono em **Rust (`Axum 0.7` + `Tokio` + `MongoDB 3.1`)** responsável pela API REST `/api/v1/*`, pelo servidor estático incorporado do painel administrativo `/admin/`, pela distribuição OTA do APK Android e pelas rotinas agendadas de manutenção pedagógica sobre o banco **`cogniva`**.

Para a visão integrada do sistema, consulte [`docs/ARQUITETURA.md`](../docs/ARQUITETURA.md) e [`docs/OPERACAO.md`](../docs/OPERACAO.md).

---

## 1. Principais Responsabilidades

- **Autenticação Nativa (`routes/auth.rs`, `routes/web_admin.rs`, `security.rs`):**
  - Modo convidado imediato (`POST /api/v1/auth/guest`), cadastro com promoção *in-place* do mesmo `user_id` (`POST /api/v1/auth/register`), login com `Argon2id` (`POST /api/v1/auth/login`), logout com revogação na coleção `sessions` (`POST /api/v1/auth/logout`) e perfil (`GET /api/v1/auth/me`).
  - Sessões administrativas web com cookie `HttpOnly` + `SameSite=Strict` + `Secure` e proteção `Origin` + `X-Cogniva-CSRF: 1`.
  - Limite global de payload (`DefaultBodyLimit::max(2 * 1024 * 1024)`) sem duplo buffering em memória.
- **Catálogo em Execução e Templates Globais (`services/catalog_runtime.rs`, `routes/exams.rs`, `services/exam_storage.rs`):**
  - Snapshot do catálogo canônico (`shared/knowledge-catalog.json` v2) mantido em cache `RwLock` com TTL de 10 segundos e invalidação instantânea após `POST /api/v1/admin/catalog/activate`.
  - Separação estrita entre `exam_templates` (globais, geridos no `/admin/`) e `user_exams` (grades individuais dos alunos), com detecção automática de `upstream_update_available` e adoção explícita via `POST /api/v1/exams/:id/adopt-template`.
  - Sincronização atômica entre `is_active_in_ladder` e `usuarios.active_contest_ids`.
- **Acervo 100% V2, Seleção e Sincronização (`services/question_storage.rs`, `services/selection_v2.rs`, `routes/quiz.rs`):**
  - Persistência validada contra o contrato V2, hash canônico JCS SHA-256 (RFC 8785), outbox de auditoria e suporte a edição completa (`explanation`, `level`, `exam_year` e `validity.status`).
  - Seleção para os 8 modos de estudo filtrando por disciplina, componente e `syllabus_topic_id`, excluindo questões `OPEN_ENDED` das filas objetivas e isolando reportes abertos (`question_reports`) apenas para o usuário denunciante.
  - Ingestão idempotente de lotes de respostas (`POST /api/v1/quiz/submit-batch`) com ordenação cronológica garantida e janela limitada em `pastSessions`.
- **Governança xAI e Curadoria (`services/ai_tutor.rs`, `services/classification.rs`, `services/question_import.rs`, `services/rate_limit.rs`):**
  - Cliente `reqwest::Client` compartilhado em `AppState`, modelo lido obrigatoriamente de `XAI_MODEL`, dupla reserva de cota (`XAI_DAILY_USER_LIMIT` + `XAI_DAILY_GLOBAL_LIMIT`), estorno em falhas de rede/parse e metadados `ai_resource_meta`.
  - Rotas administrativas para curadoria de reportes (`/api/v1/admin/reports`) e propostas de classificação IA V2 (`/api/v1/admin/classification/*`), além de compatibilidade por proprietário em `/api/v1/exams/:id/compatibility`.
- **Distribuição OTA Segura (`routes/apk_ota.rs`):**
  - Entrega streaming sem compressão dinâmica em `/api/v1/apk/download`, `/apk` e `/cogniva.apk`.

---

## 2. Variáveis de Ambiente Essenciais

Consulte o modelo completo em [`.env.example`](../.env.example):

| Variável | Descrição |
| :--- | :--- |
| `MONGODB_URI` | String de conexão do MongoDB (obrigatória). |
| `MONGODB_DB_NAME` | Nome do banco de dados ativo (padrão: `cogniva`). |
| `COGNIVA_JWT_SECRET` | Segredo HS256 com no mínimo 32 caracteres aleatórios (obrigatório). |
| `COGNIVA_PUBLIC_ORIGIN` | Origem pública HTTPS autorizada para CORS e validação CSRF do `/admin/`. |
| `ADMIN_EMAILS` | Lista separada por vírgulas de e-mails com papel `admin` automático. |
| `XAI_API_KEY` / `XAI_MODEL` | Chave privada da xAI e identificador explícito do modelo (ex.: `grok-4.20-non-reasoning`). |
| `XAI_DAILY_USER_LIMIT` / `XAI_DAILY_GLOBAL_LIMIT` | Limites diários de chamadas pagas por conta (padrão `120`) e global (padrão `500`). |

---

## 3. Comandos CLI de Manutenção

```bash
# Instalar/verificar validadores BSON, índices, templates globais e snapshot do catálogo no banco cogniva
cogniva-api --prepare-question-storage

# Conceder papel administrativo a uma conta já registrada por e-mail
cogniva-api --grant-admin admin@exemplo.com
```

---

## 4. Execução e Testes Locais

> **Importante:** O `Dockerfile` e a incorporação estática de `web-admin/` e `shared/` assumem os diretórios irmãos na raiz do repositório.

```bash
# Rodar suíte de testes unitários e de contrato
cargo test --manifest-path backend-rust/Cargo.toml --locked

# Rodar suíte completa incluindo testes de integração em MongoDB local isolado (nunca usar URI de produção!)
COGNIVA_TEST_MONGODB_URI="mongodb://127.0.0.1:27048/" \
  cargo test --manifest-path backend-rust/Cargo.toml --locked -- --include-ignored --test-threads=1

# Executar servidor localmente em modo desenvolvimento
cargo run --manifest-path backend-rust/Cargo.toml
```
