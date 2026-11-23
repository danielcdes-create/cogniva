# Relatório de Auditoria de Qualidade e Arquitetura de Testes — Cogniva

**Versão de Linha de Base Auditada:** `2.4.1` (`build 31`, commit `88f3fe7`)  
**Versão Final Pós-Remediação:** `2.4.2` (`build 32`)  
**Escopo Multi-Stack Auditado e Remediado:**
- **Portais Web JS (`web-admin/` e `web-missions/`):** ES Modules nativos (`node:test` + cobertura V8), acessibilidade WCAG 2.1 AA e paridade `web-admin/help.js` ↔ [`docs/GUIA-ADMINISTRACAO.md`](docs/GUIA-ADMINISTRACAO.md)
- **Python, Contratos e Deploy (`scripts/`, `deploy/`, `shared/`):** Validação JSON Schema Draft 2020-12 / JCS SHA-256, migrações, importação de questões, promoção atômica Docker/VPS, sincronização Nginx e aceite pós-deploy
- **Backend Rust (`backend-rust/`):** `cogniva-api` (`Axum` + `MongoDB`, `contract_version: 5`, `schema_version: 2`, `validator_revision: 6`)
- **Android Nativo (`android-app/`):** `Kotlin` + `Jetpack Compose` + `Room v7` + `Paparazzi` + `AndroidTest E2E` (API 35)

---

## 1. Comparativo Executivo: Antes vs. Depois da Remediação

| Camada / Suíte | Testes (Antes → Depois) | Cobertura / Métricas (Antes) | Cobertura / Métricas (Depois) | Status Final |
| :--- | :---: | :--- | :--- | :---: |
| **Web Admin (`web-admin/tests/*.test.js`)** | `28 → 35` (`+7`) | `62,70%` Lines / `65,76%` Branches / `52,41%` Functions (`app.js`: `56,95%` L / `61,15%` B) | **`91,16%` Lines** (`+28,46 pp`) / **`73,89%` Branches** (`+8,13 pp`) / **`70,10%` Functions** (`+17,69 pp`; `app.js`: `90,08%` L; `domain.js`: `94,69%` L / `85,47%` B / `100%` F) | `exit 0` (`35/35`) |
| **Web Missions (`web-missions/tests/*.test.js`)** | `7 → 10` (`+3`) | `88,08%` Lines / `63,69%` Branches / `76,29%` Functions (`app.js`: `87,27%` L / `73,26%` F) | **`97,49%` Lines** (`+9,41 pp`) / **`67,48%` Branches** (`+3,79 pp`) / **`95,96%` Functions** (`+19,67 pp`; `app.js`: `97,30%` L; `domain.js`: `100%` L / `100%` F) | `exit 0` (`10/10`) |
| **Python (`scripts/tests/test_*.py`)** | `73 → 100` (`+27`, `+49` subtestes) | `54,01%` das linhas executáveis nos módulos rastreados (`0%` direto em `sync_nginx_site.py`, `stage_bundle.py`, `package-release.py`, `cleanup-registry.py`, `verify-production-release.py`, `verify-web-admin-assets.py`, `check_markdown_links.py`) | **`76,26%` das linhas executáveis** (`+22,25 pp`; `promote.py`: `85,43%`, `stage_bundle.py`: `95,24%`, `sync_nginx_site.py`: `89,66%`, `package-release.py`: `95,65%`, `verify-production-release.py`: `98,11%`, `verify-web-admin-assets.py`: `97,96%`, `check_markdown_links.py`: `97,22%`) | `exit 0` (`100/100`) |
| **Rust Backend (`backend-rust`)** | `98 → 113` (`+15` unit/router) + `15` integração Mongo | `98` unitários (`0` testes HTTP diretos para `/lookup`, `/attempt-evidence`, `/study/next-action`) | **`113` unitários/roteador** + **`15` suítes de integração MongoDB** expandidas com asserções V2.1, LGPD, `QuotaGuard` e promoção `guest` (`cargo clippy -D warnings` + `cargo build --release`) | `exit 0` (`113/113` + `15` CI Mongo) |
| **Android JVM + Paparazzi (`android-app/src/test`)** | `101 → 125` (`+24`) | `101` testes (`0%` de cobertura direta em `AuthStore.kt` e `QuestionImportViewModel.kt`) | **`125` testes JVM** (`16` classes), `lintDebug` + `lintVitalRelease`, `verifyPaparazziDebug` (`0` diffs nos goldens) e `assembleRelease` (R8) | `exit 0` (`125/125`) |
| **Android Instrumentado E2E (`androidTest`)** | `5` suítes API 35 | `QuestionPracticeTest` invocava `model.submitAnswer` por código sem clicar na UI | Interação real de UI Compose (scroll, alternativa, bloqueio pré-confiança, confiança e confirmação) + compilação limpa `:app:compileDebugAndroidTestKotlin` | `exit 0` |

---

## 2. FASE 1 — Problemas de Validade Identificados e Remediados

1. **Mock Drift Eliminado:**
   - [`web-missions/tests/missions.test.js`](web-missions/tests/missions.test.js): substituído o campo inexistente `content_revision` pelo contrato real `QuestionDto.question_revision` e atualizado o mock de `POST /api/v1/quiz/submit-batch` para validar `question_revision` e responder com `SubmitBatchResponse { processed_count, rejected, results }`; alinhados payloads de `/decisions`, `/practice-questions`, `/missions` e `/telemetry`.
   - [`web-admin/tests/app.test.js`](web-admin/tests/app.test.js): incluído `[data-page="missions"]` no shell, `compatibility` + `SUBTOPIC`s filhos em `sampleCatalog`, hashes V2.1 + `provenance` completo em `sampleV2Question` e contratos reais de `/admin/web/me` e `/auth/web-logout`.
   - [`RepositoryContractTest.kt`](android-app/app/src/test/java/br/com/concursoprep/app/RepositoryContractTest.kt): suporte a `auth: AuthStore?` e `ownerUserId < 0L`, `countExams` retornando contagem real sem `coerceAtLeast(1)`, `getCachedQuestions(disciplina, limit)` fiel ao SQL, `updateLearningFlags` real, filtro `ownerUserId` em `updateAttemptEvidence` e `acknowledgeReport`, `StateFlow` reativo em `observeMetadata` e cobertura dos endpoints `2.3.0`–`2.4.2`.
   - [`selection_v2.rs`](backend-rust/src/services/selection_v2.rs) & [`integration_tests.rs`](backend-rust/src/integration_tests.rs): corrigida fixture impossível (`points: 50.0` com `dominada: false`) e populados hashes V2.1 (`semantic_revision`, `grading_revision`) nas fixtures de integração.
   - [`test_deploy_scripts.py`](scripts/tests/test_deploy_scripts.py), [`test_import_questions_batch.py`](scripts/tests/test_import_questions_batch.py) e [`test_knowledge_projection_schema.py`](scripts/tests/test_knowledge_projection_schema.py): validação integral da regex `cogniva_auth` (incluindo rotas `missions-*`), vínculo chave→zona Nginx (`$cogniva_limit_key` vs. `$cogniva_ai_limit_key`), `source_reference` obrigatório com `confirm_origin=True` e `FormatChecker()` nos validadores JSON Schema.
2. **Código Morto e Testes Obsoletos Removidos:**
   - Removido `scripts/classification_preservation.py` (módulo órfão sem consumidores em produção/CI).
   - Removidos **208 linhas** de tipos/helpers legados sem chamadores em [`ExamEngine.kt`](android-app/app/src/main/java/br/com/concursoprep/app/domain/exam/ExamEngine.kt), o método legado inseguro `ConcursoRepository.downloadApkUpdate`, `ApkInstallEvent.ReadyToInstall` e a expressão tautológica em `AuthStore.decryptSecret`.
   - Removidos helpers mortos em Rust (`apk_ota::{apk_info_value, read_apk_bytes}`, `rate_limit::{reserve_tutor, release_tutor}`, `ai_tutor::get_or_generate_ai_assist_budget`, `scheduler::spawn_background_daily_scheduler`, `question_eligibility::utc_today`).
   - Removida a chamada de rede morta `GET /api/v1/catalog/trunks` em [`web-admin/app.js`](web-admin/app.js) e os helpers mortos `form.conceptId`, `orderedConcepts` e `detectImpactedQuestionsBySourceHash` em [`web-admin/domain.js`](web-admin/domain.js).
3. **Falsos Positivos, Tautologias e Acoplamentos Corrigidos:**
   - Substituídos os hexadecimais hardcoded na auditoria WCAG de `web-admin/tests/app.test.js` por extração direta das regras de [`web-admin/style.css`](web-admin/style.css) (cobrindo `.button-link` e estados hover/focus).
   - Substituído o carregamento frágil via `ast.parse` + `exec` (`load_promote_helpers`) em `test_deploy_scripts.py` por importação modular padrão após refatoração de `main(argv=None)`.
   - Corrigida asserção frouxa `intervalDays == 1L || 7L || 30L` para `assertEquals(7L, intervalDays)` em [`AuditFindingsTest.kt`](android-app/app/src/test/java/br/com/concursoprep/app/AuditFindingsTest.kt), removidas contagens mágicas do catálogo em [`ExamEngineTest.kt`](android-app/app/src/test/java/br/com/concursoprep/app/ExamEngineTest.kt), validado o código JSON `"MEDIA_ASSET_UNAVAILABLE"` nos testes `404` de mídia em Rust e substituída a chamada direta `model.submitAnswer` por interação real de UI em [`QuestionPracticeTest.kt`](android-app/app/src/androidTest/java/br/com/concursoprep/app/QuestionPracticeTest.kt).

---

## 3. Bugs Reais de Produção Encontrados e Corrigidos (Fases 3 e 4)

### 3.1. Portais Web (`web-missions/` e `web-admin/`)
1. **`[P0]` Rejeição silenciosa de 100% das questões humanas reais em `/missoes/` ([`web-missions/app.js`](web-missions/app.js)):** `submitRealPracticeAnswer` enviava `q.content_revision` (inexistente em `QuestionDto`) em vez de `q.question_revision`, gerando `question_revision: ""` rejeitado em `SubmitBatchResponse.rejected` enquanto a UI exibia sucesso falso. Corrigido para enviar `q.question_revision` e tratar `batchResp.rejected` / `processed_count < 1` como erro visível reabilitando o botão.
2. **`[P0]` Fila de Classificação IA em branco no `/admin/` ([`web-admin/app.js`](web-admin/app.js)):** `classificationView` lia `item.links` em vez de `item.proposed_links`, omitia `status=ALL` na busca `"Todas"` (caindo em `PENDING` no backend) e expunha status inexistentes (`EDITED_ACCEPTED`, `DEFERRED`). Corrigido para `proposed_links` e status canônicos (`ALL`, `PENDING`, `ACCEPTED`, `REJECTED`, `STALE`, `SUPERSEDED`).
3. **`[P1]` Bypass de erro inline em `editorial_card` e estado inválido `"PROPOSED"` ([`web-admin/app.js`](web-admin/app.js), [`web-admin/domain.js`](web-admin/domain.js)):** `editorialError` passa a invalidar `draft.preview = null`, desabilitar `"Confirmar edição"`, marcar rascunho sujo em `isQuestionDraftDirty` e limpar ao sincronizar JSON bruto; `classification.state` parcial corrigido de `'PROPOSED'` para `'PARTIAL'`; rubrica `OPEN_ENDED` validando `essential: boolean`, `expected_responses`, `revision >= 1` e chaves desconhecidas; parada garantida de `<video>` (incluindo preload oculto) e rótulos dinâmicos de *Duration* em `web-missions/app.js`.

### 3.2. Backend Rust (`backend-rust/`)
4. **`[P0]` Filtro LGPD Art. 18 em `question_review_events` ([`auth_session.rs`](backend-rust/src/routes/auth_session.rs)):** `lgpd_deletion_plan` filtrava campos inexistentes (`actor.id` / `event_type`); corrigido para casar com `actor_user_id` / `principal_id` e ações pessoais (`COMPATIBILITY_*`, `EVALUATION_*`), preservando eventos editoriais globais e mantendo `sessions` e `usuarios` por último.
5. **`[P0]` Dreno infinito de cota paga xAI após bloqueio de spoiler ([`ai_tutor.rs`](backend-rust/src/services/ai_tutor.rs), [`classification.rs`](backend-rust/src/services/classification.rs)):** Quando duas chamadas reais à xAI eram bloqueadas por `detect_pre_answer_spoiler`, `release_now()` estornava 100% da cota. Agora `quota_guard.commit()` debita normalmente as chamadas consumidas (mantendo estorno apenas em falhas HTTP/rede do provedor), e `classification.rs` só comita após persistência válida.
6. **`[P0/P1]` Proteção de progresso convidado, Argon2id não-bloqueante e `Vary` ([`auth_session.rs`](backend-rust/src/routes/auth_session.rs), [`security.rs`](backend-rust/src/security.rs)):** `POST /api/v1/auth/register` rejeita `Authorization` inválido/expirado com `401` em vez de criar conta vazia silenciosa; `POST /api/v1/auth/guest` (`student_web`) reutiliza cookie convidado ativo; `dummy_password_hash` executa exclusivamente em `spawn_blocking` após a guarda de `1024` bytes; `security::authenticate` consolida todos os headers `Vary` via `get_all`.
7. **`[P1]` Isolamento de `is_blank = true`, retenção por tópico em `v5`, `If-Range` RFC 9110 e índice `schema_outbox` ([`missions.rs`](backend-rust/src/services/missions.rs), [`selection_v2.rs`](backend-rust/src/services/selection_v2.rs), [`scheduler.rs`](backend-rust/src/services/scheduler.rs), [`attempts.rs`](backend-rust/src/services/attempts.rs), [`study_loop.rs`](backend-rust/src/routes/study_loop.rs), [`question_storage.rs`](backend-rust/src/services/question_storage.rs)):** Omissões em branco isoladas de maestria, seleção de inéditas, agendamento diário e frequência mensal; retenção espaçada `>= 24h` habilitada entre questões distintas do mesmo tópico/conceito com ordenação cronológica determinística; validação de `selected_evidence_id` em `submit-batch` + decode canônico em `attach_evidence`; rejeição de weak ETag `W/` em `If-Range`; e correção de `idx_questions_schema_outbox` para `schema_outbox._id`.

### 3.3. Aplicativo Android (`android-app/`)
8. **`[P0]` Cancelamento do `WorkManager` periódico e preservação de `practiceAvailable` ([`ConcursoApp.kt`](android-app/app/src/main/java/br/com/concursoprep/app/ConcursoApp.kt), [`Learning.kt`](android-app/app/src/main/java/br/com/concursoprep/app/domain/model/Learning.kt)):** Cancelamento síncrono de `OfflineSyncWorker.UNIQUE_WORK_NAME` (`"ConcursoPrepOfflineSyncWork"`) + `"CognivaImmediateSync"` antes de trocar/apagar o SQLite da conta; `LearningProjection.projectHistory` preserva `base?.practiceAvailable ?: true`.
9. **`[P1]` Contagem única de omissões estratégicas e replay fiel de Missões offline ([`ConcursoRepository.kt`](android-app/app/src/main/java/br/com/concursoprep/app/data/repository/ConcursoRepository.kt)):** Corrigida a soma duplicada (`2x`) em `buildLocalDecisionTelemetry` e reestruturado `flushDirtyMissionRecord` para ordenar decisões pela sequência das cenas e enviar 2 etapas (`firstSelectedOptionId` → `latestSelectedOptionId`) + `scenePositionsMs`.
10. **`[P1]` Endurecimento de URL e ciclo de vida OTA, LGPD local e Markdown ([`AppUpdateManager.kt`](android-app/app/src/main/java/br/com/concursoprep/app/data/update/AppUpdateManager.kt), [`MainActivity.kt`](android-app/app/src/main/java/br/com/concursoprep/app/MainActivity.kt), [`AppDatabase.kt`](android-app/app/src/main/java/br/com/concursoprep/app/data/local/AppDatabase.kt), [`MarkdownParser.kt`](android-app/app/src/main/java/br/com/concursoprep/app/ui/components/MarkdownParser.kt), [`QuestionImportDraft.kt`](android-app/app/src/main/java/br/com/concursoprep/app/domain/model/QuestionImportDraft.kt)):** Bloqueio de `%2e`/`%2f`/`%5c` e `#fragment` em `resolveDownloadUrl`, roteamento do diálogo `PackageInstaller` baseado em coletores visíveis (`STARTED`), filtro `ownerUserId` em `updateAttemptEvidence`, limpeza das tabelas de concursos em `deleteAccount`, suporte a links HTTPS UTF-8 + cercas de 4+ crases e bloqueio de `confirmOrigin = true` sem `source_reference`.

### 3.4. Infraestrutura, Deploy e Scripts Python (`deploy/` e `scripts/`)
11. **`[P0]` Prevenção de gate `503` permanente e proteção da imagem de rollback ([`deploy/promote.py`](deploy/promote.py)):** Adicionado `nginx -t` + `systemctl reload nginx` ao restaurar `/etc/nginx/cogniva-gate.conf` em falha pré-mutação (`if not runtime_mutated:`) e gravado `'previous_image': old_runtime_ref` (`@sha256:`) para proteção no GHCR.
12. **`[P0]` Revogação garantida de sessão administrativa efêmera ([`scripts/cogniva_import.py`](scripts/cogniva_import.py)):** `POST /api/v1/auth/logout` e `conn.close()` protegidos em bloco `try ... finally` no `REMOTE_RUNNER`, leitura condicional de `JWT_SECRET`, validação de `OPEN_ENDED` em `--check-local` e preservação de `taxonomy.sources`.
13. **`[P1]` Rollback atômico de Nginx, CLI de migração V2.1 e preservação `SEMANTIC` ([`deploy/sync_nginx_site.py`](deploy/sync_nginx_site.py), [`deploy/bootstrap-ip-tls.sh`](deploy/bootstrap-ip-tls.sh), [`scripts/migrate_questions_v2_1.py`](scripts/migrate_questions_v2_1.py), [`scripts/migrate_cnu_to_cogniva.py`](scripts/migrate_cnu_to_cogniva.py)):** Backup e rollback simétrico de `sites-available/cogniva` e `conf.d/cogniva-rate-limits.conf` em falha de `nginx -t` ou `reload`, suporte a `--report-out` / `--db` + `client.close()` em `finally` e preservação de validade `SEMANTIC` + hashes V2.1 em questões `schema_version == 2`.

---

## 4. Gates de Qualidade Reexecutados (`100% exit 0`)

- `python scripts/knowledge_catalog.py --check` → `exit 0` (`af2d711b...` preservado)
- `python scripts/question_contracts.py --examples` → `exit 0` (`COGNIVA_V2_1_ACTIVE`)
- `python scripts/missions_contracts.py --check` → `exit 0` (`5/5` vídeos verificados)
- `python scripts/check_markdown_links.py` → `exit 0`
- `python -m py_compile` (`deploy/*.py` e `scripts/*.py`) → `exit 0`
- `pytest scripts/tests -v` → `exit 0` (`100 passed, 49 subtests passed`)
- `node scripts/export-admin-help.mjs --check` → `exit 0`
- `npm --prefix web-admin run check` & `node --test --experimental-test-coverage web-admin/tests/*.test.js` → `exit 0` (`35/35`)
- `npm --prefix web-missions run check` & `node --test --experimental-test-coverage web-missions/tests/*.test.js` → `exit 0` (`10/10`)
- `cargo fmt --manifest-path backend-rust/Cargo.toml --check` → `exit 0`
- `cargo clippy --manifest-path backend-rust/Cargo.toml --all-targets --locked -- -D warnings` → `exit 0`
- `cargo test --manifest-path backend-rust/Cargo.toml --locked` → `exit 0` (`113 passed; 0 failed; 15 ignored`)
- `cargo build --manifest-path backend-rust/Cargo.toml --release --locked` → `exit 0`
- `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:verifyPaparazziDebug :app:assembleRelease` → `exit 0` (`125/125`)
- `git diff --exit-code -- android-app/app/src/test/snapshots` → `exit 0`
