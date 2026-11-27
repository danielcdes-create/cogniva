# Relatório de Auditoria Adversarial Completa — Cogniva `2.4.6` (`build 36`)

**Data:** 2026-11-27  
**Versão auditada → publicada:** `2.4.5` (`build 35`) → **`2.4.6` (`build 36` / `versionCode = 36`)**  
**Contrato preservado:** `contract_version = 5`, `schema_version = 2`, `validator_revision = 7`, catálogo gênesis SHA-256 `af2d711baaeebd3383cba39a02ce014b6697952f7d9438c125e009de68df1abb`, Room `v8`.

---

## 1. Escopo e Metodologia

Foi conduzida auditoria adversarial exaustiva em quatro frentes independentes cobrindo **100% das superfícies de código, contratos, persistência, UI e infraestrutura**:

1. **Backend Rust (`backend-rust/`):** segurança de sessão/cookies, LGPD Art. 18, extração de IP sob proxy reverso, motores metacognitivos/calibração/priorização/SRS, `CTV` (*Database First, AI on Demand*), contabilidade de cotas xAI, índices e memória em container de `192 MB`.
2. **Cliente Android (`android-app/`):** sincronização *offline-first* (`Room v8` + `LocalCtvLedger`), condições de corrida em filas concorrentes, simetria matemática Rust ↔ Kotlin, preservação de estado em rotações de tela e promoção de conta convidada.
3. **Portais Web (`web-admin/` e `web-missions/`):** curadoria V2 (`reconfirm_validity`, `PARTIAL`, `editorial_card`, `diagnostic_meta`), não-fabricação de confiança declarada, travas anti-duplo-clique e contraste não-textual WCAG 2.1 SC 1.4.11 (`>= 3.0:1`).
4. **Scripts Python, Nginx, Promoção Atômica e CI/CD (`scripts/`, `deploy/`, `.github/workflows/`):** migração *read-only* `CNU → cogniva`, importação em lote sem truncamento numérico, regex PCRE IPv6 `/64` com `DOTALL`, proteção de templates em `sync_nginx_site.py`, prontidão real `/api/v1/ready` em rollback e endurecimento de permissões em workflows.

---

## 2. Achados Críticos e Altos Remediados (`P0` / `P1` / `P2`)

### 2.1. Integridade Transacional, Sincronização Offline e UTF-8 (`P0`)

- **`[P0-1]` Drenagem permanente de `pendingRecommendationFeedback` + corrida de sobrescrita em filas offline (`routes/study_loop.rs`, `Models.kt`, `ConcursoRepository.kt`, `attempts.rs`):**
  - *Causa-raiz:* `RecommendationFeedbackRequestDto` serializava `additional_seconds` enquanto o backend lia `additional_active_seconds`, e a resposta omitia `status`, causando falha de desserialização em Kotlin e reenvio infinito. Além disso, `syncPendingAttempts` sobrescrevia `pending_evidence_patches`, `pendingEvents`, `pending_ctv_submissions` e `pendingRecommendationFeedback` com snapshots lidos antes do I/O de rede, podendo descartar itens gravados concorrentemente pela UI.
  - *Correção:* Alinhado `@SerialName("additional_active_seconds")` (com `#[serde(alias = "additional_seconds")]` no Rust), adicionado `"status": "recorded"` na resposta, auto-correlacionado `subsequent_unseen_correct_observed` em acertos inéditos subsequentes e refatoradas todas as 4 filas de `syncPendingAttempts` para remover apenas `completedKeys` sobre a lista relida dentro de `mutationMutex.withLock`.
- **`[P0-2]` Pânico por fatiamento UTF-8 em espaços multi-byte (`models/question.rs`):**
  - *Causa-raiz:* `pick_decisive_stem_fragment` avançava `+ 1` byte ao buscar fronteira de palavra com `char::is_whitespace()`, causando pânico ao encontrar `U+00A0` (`NBSP`, 2 bytes) ou `U+202F` (3 bytes) em enunciados importados de PDFs de bancas.
  - *Correção:* Substituído `+ 1` por `ws_ch.len_utf8()` sobre `char_indices()`, coberto por teste unitário dedicado.
- **`[P0-3]` Preservação e CAS de `editorial_card`, `diagnostic_meta` e `reconfirm_validity` (`question_storage.rs`, `admin_data.rs`, `web-admin/app.js`):**
  - *Causa-raiz:* Edições administrativas de questões V2 não propagavam `reconfirm_validity` no payload de `prepare-edit`, não restauravam `evaluated_content_revision` ao desmarcar a caixa e podiam sobrescrever `editorial_card`, `diagnostic_meta` ou `evidence_challenge` sem guarda CAS.
  - *Correção:* Persistência atômica e guarda CAS estendidas em `question_storage.rs`; sincronização automática de `diagnostic_meta` (`content_hash`, `subject_id`/`topic_id` primários e promoção `HUMAN_CONFIRMED` quando `CURRENT` + `CONFIRMED`); envio explícito de `reconfirm_validity` e restauração reversível no `/admin/`.
- **`[P0-4]` Blindagem de `POST /api/v1/quiz/ctv/evaluate` contra abuso de cota e enumeração (`ctv_engine.rs`, `nginx-cogniva.conf`):**
  - *Causa-raiz:* `max_search_pages` e `simulate_search_error_after_page` eram desserializáveis do JSON do cliente, `session_ctv_count` confiava apenas no contador enviado pelo cliente e a rota não estava na zona `cogniva_ai_mut` do Nginx.
  - *Correção:* Aplicado `#[serde(skip)]` nos campos internos de teste, *clamp* estrito de paginação, contagem autoritativa de ofertas `ctv_offers` na `session_uuid` no servidor, *rate limit* dedicado `ctv_eval` por usuário e inclusão de `quiz/ctv/evaluate$` na zona `cogniva_ai_mut`.
- **`[P0-5]` Reconciliação de `unit_id` (`concept_id` vs. `canonical_topic_id`), DAG e `post_explanation_spaced_due` (`routes/study_loop.rs`, `ConcursoRepository.kt`):**
  - *Causa-raiz:* `get_next_action` indexava unidades por `concept_id` mas cruzava CTV/recomendações por `canonical_topic_id`, mantinha `post_explanation_spaced_due` perpétuo mesmo após acerto fundamentado espaçado posterior e deixava `unmet_prerequisites_count`/`dependent_count` zerados.
  - *Correção:* Mapeamento canônico unificado por conceito/tópico (incluindo tentativas CTV independentes sem tentativa-pai no log legado), limpeza automática de `post_explanation_spaced_due` após acerto fundamentado em dia subsequente, contabilização de `TransferFailed` em `transfer_available_units` e cálculo real de pré-requisitos não atendidos e dependentes no DAG do catálogo ativo.
- **`[P0-6]` Eliminação total de fabricação de confiança declarada (`metacognitive_engine.rs`, `calibration_engine.rs`, `routes/learning.rs`, `PerformanceEngines.kt`, `PracticeAndQuestionScreen.kt`, `ConcursoRepository.kt`, `web-missions/app.js`):**
  - *Causa-raiz:* `DeclaredConfidence::from_legacy_band` convertia `confidence_level` padrão (`2`) em `MEDIUM` mesmo quando `explicit == false`, `PracticeAndQuestionScreen.kt` marcava `confidenceExplicitlySelected = true` após `submitted == true`, e `web-missions/app.js` enviava `confidence_explicit: true` fixo herdando a confiança da questão anterior.
  - *Correção:* `from_legacy_band(_, false)` retorna `None` tanto em Rust quanto em Kotlin; `confidence_explicit` e `strategy_skipped` são serializados em `LearningEvent`; `PracticeAndQuestionScreen.kt` preserva `"Não informada"` quando omitida; e `web-missions/app.js` oferece os 5 níveis canônicos (`GUESS`..`VERY_HIGH`) reiniciados a cada questão com `confidence_explicit: false` por padrão.

### 2.2. Precisão de SRS, Elegibilidade, Memória (`192 MB`), Segurança e LGPD (`P1`)

- **`[P1-1]` Âncora temporal monotônica de SRS separada de omissões em branco (`models/activity.rs`, `attempts.rs`, `scheduler.rs`, `selection_v2.rs`, `routes/learning.rs`):**
  - Adicionado `ultima_resposta_em` (`effective_last_answered()`) em `UserActivityDocument` para que omissões estratégicas (`is_blank = true`) atualizem telemetria sem rejuvenescer artificialmente a curva de esquecimento nem desalojar tentativas reais das 50 posições de `pastSessions`; cálculo de `elapsed_days` contínuo em `f64`.
- **`[P1-2]` Bloqueio transversal de itens `SUSPENDED` / `PENDING_HUMAN_REVIEW` e suspensão imediata por reporte aberto (`question_eligibility.rs`, `routes/quiz.rs`, `routes/ai_help.rs`, `routes/admin_data.rs`):**
  - `StandardObjective` bloqueia `SUSPENDED`, `RETIRED`, `PENDING_HUMAN_REVIEW` e `REJECTED`; reportes abertos sobre questões `AI_ON_DEMAND` suspendem imediatamente o item e `resolve_report` recalcula `open_reports_count` antes de reabilitar itens confirmados.
- **`[P1-3]` Blindagem BSON contra `null` explícito e preservação de notas fracionárias (`question_compatibility.rs`, `migrate_cnu_to_cogniva.py`, `import_questions_batch.py`):**
  - `strip_null_fields_recursive` higieniza `Bson::Null` em vetores/strings opcionais antes de `from_document`; `migrate_cnu_to_cogniva.py` grava `concursos: []`, usa `$setOnInsert` por padrão (`--force-overwrite` opcional) e limpa `reportedQuestoes` por `question_id` e `questao_id`; `import_questions_batch.py` preserva `max_score`/`pass_score` fracionários.
- **`[P1-4]` Otimização `O(N)` de reconciliação CTV, projeções enxutas e índices MongoDB (`ctv_reconciliation.rs`, `ctv_engine.rs`, `ctv_admin_metrics.rs`, `scheduler.rs`, `selection_v2.rs`, `learning_analytics.rs`, `question_storage.rs`):**
  - `reconcile_user_ctv_history` refatorado de consultas `O(N × M)` para snapshot em memória `O(N)`; `$min` atômico em `first_displayed_at`/`dismissed_at`; verificação de `attempt_receipts` antes de auditoria offline; correção da lógica `any_estimated_cost` em `ctv_admin_metrics.rs`; índices compostos CTV e TTL de 90 dias em `study_recommendation_events`.
- **`[P1-5]` Guarda de concorrência LGPD (`deleting_account`), extração segura de IP e teto de resposta xAI (`models/user.rs`, `routes/auth_session.rs`, `services/rate_limit.rs`, `services/ai_tutor.rs`):**
  - Flag `deleting_account` bloqueia recriação de sessões/tentativas durante `DELETE /api/v1/auth/account` e anonimiza integralmente `reportedQuestoes`; `extract_client_ip` seleciona o IP público mais à direita em `X-Forwarded-For`; leitura de corpo da xAI limitada a `2 MiB` e reserva de cota mantida quando a 1ª chamada paga sucedeu mas o 2º retry falhou (`PaidCallIncomplete`).
- **`[P1-6]` Paridade de `retrospective_legacy_hits` e calibração por formato (`services/learning_analytics.rs`, `ConcursoRepository.kt`):**
  - `RetrospectiveLegacyEvidence` contabilizado em campo próprio sem inflar `unknown_grounding_hits`; ponderação por formato (`TRUE_FALSE` vs. `MULTIPLE_CHOICE`) alinhada entre Rust e Kotlin.
- **`[P1-7]` Isolamento de cookies web, fusão de conceitos na curadoria de catálogo e acessibilidade WCAG 1.4.11 (`security.rs`, `catalog_editor.rs`, `PracticeAndQuestionScreen.kt`, `MainViewModel.kt`, `web-admin/*`, `web-missions/*`, `deploy/*`):**
  - Cookie `cogniva_missions_session` isolado para `student_web`; `catalog_editor.rs` preserva `seed_graph()` na ativação de novas versões; rotação de tela no Android preserva `pendingCtvEvaluation` e `ctvDisplayedAtIso`; promoção de convidado faz *flush* prévio de `syncPendingAttempts`; contraste de bordas elevado para `>= 3.0:1` em `/admin/` (`#758691`) e `/missoes/` (`#5676a4`); regex IPv6 `/64` no Nginx corrigida com `(?s)` (`PCRE_DOTALL`) e `promote.py` valida `/api/v1/ready` no rollback antes de reabrir o gate.

---

## 3. Cobertura de Validação Executada

- **Contratos & Python (`pytest`):** `knowledge_catalog.py --check` (`af2d711b...`), `question_contracts.py --examples`, `missions_contracts.py --check`, `check_markdown_links.py` e `102 passed` em `scripts/tests/`.
- **Portais Web (`node:test`):** `export-admin-help.mjs --check`, `web-admin/tests/*.test.js` e `web-missions/tests/*.test.js`.
- **Backend Rust (`cargo`):** `cargo fmt --check`, `cargo clippy --all-targets --locked -- -D warnings` e `cargo test --locked`.
- **Android (`Gradle`):** `:app:testDebugUnitTest`, `:app:compileDebugAndroidTestKotlin`, `:app:lintDebug`, `:app:verifyPaparazziDebug` (28 snapshots com paridade de pixels 100%) e `:app:assembleRelease` (R8 ativo).
