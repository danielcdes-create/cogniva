# Changelog — Cogniva

Todas as mudanças arquiteturais, de segurança, acessibilidade e operacionais relevantes do ecossistema **Cogniva** são documentadas neste arquivo.

---

## [2.4.6] - 2026-11-27 (build 36)

Auditoria adversarial exaustiva em 4 camadas (`backend-rust/`, `android-app/`, `web-admin/` + `web-missions/`, `scripts/` + `deploy/` + `.github/workflows/`), endurecendo sincronização *offline-first*, precisão metacognitiva/SRS, segurança de cota e sessão, performance em `192 MB`, acessibilidade WCAG 2.1 e pipeline de promoção atômica — preservando integralmente `contract_version: 5`, `schema_version: 2`, `validator_revision: 7` e `Room v8`. Relatório técnico completo em [`AUDIT_REPORT_2_4_6.md`](AUDIT_REPORT_2_4_6.md).

### 1. Sincronização Offline-First sem Corridas e Simetria Metacognitiva (`backend-rust/`, `android-app/`, `web-missions/`)
- **Eliminação de Fabricação de Confiança Declarada (`[P0-6]`):** `DeclaredConfidence::from_legacy_band(_, false)` retorna `None`/`null` em Rust e Kotlin; `LearningEvent` persiste `confidence_explicit` e `strategy_skipped`; `PracticeAndQuestionScreen.kt` preserva `"Não informada"` quando não selecionada e `web-missions/app.js` adota os 5 níveis canônicos (`GUESS`..`VERY_HIGH`) reiniciados a cada questão sem herança indevida.
- **Drenagem Concorrente Segura e Paridade de Contratos (`[P0-1]`, `[P0-5]`, `[P1-6]`):** Alinhamento de `additional_active_seconds` e `status: "recorded"` em `POST /api/v1/study/recommendation-feedback`, remoção atômica por chaves concluídas sob `mutationMutex` nas 4 filas secundárias de `syncPendingAttempts`, reconciliação unificada `concept_id` ↔ `canonical_topic_id` + DAG em `GET /api/v1/study/next-action`, preservação de `pendingCtvEvaluation` em rotações de tela, *flush* prévio na promoção de convidado e separação de `retrospective_legacy_hits` nos analíticos de fundamento.

### 2. Integridade V2, Precisão de SRS, Performance (`192 MB`) e Segurança/LGPD (`backend-rust/`)
- **UTF-8 Seguro, CAS Completo e Elegibilidade Estrita (`[P0-2]`, `[P0-3]`, `[P1-2]`, `[P1-3]`):** Fatiamento UTF-8 seguro para espaços multi-byte (`NBSP`/`U+202F`) em `pick_decisive_stem_fragment`, guarda CAS estendida a `editorial_card`/`diagnostic_meta`/`evidence_challenge`, suporte completo a `reconfirm_validity` e filtro `PARTIAL` no `/admin/`, suspensão imediata de itens `AI_ON_DEMAND` sob reporte aberto e higienização recursiva de `Bson::Null`.
- **Âncora Monotônica de SRS e Reconciliação `O(N)` (`[P1-1]`, `[P1-4]`):** Campo `ultima_resposta_em` separa omissões estratégicas (`is_blank = true`) da curva de esquecimento e protege `pastSessions` contra desalojamento; reconciliação CTV em memória `O(N)`, `$min` atômico em timestamps de oferta, projeções enxutas e índices compostos + TTL de 90 dias em `study_recommendation_events`.
- **Endurecimento de Cota CTV/xAI, Proxy IP e LGPD Art. 18 (`[P0-4]`, `[P1-5]`, `[P1-7]`):** Campos internos de teste com `#[serde(skip)]`, contagem autoritativa de CTV por sessão e *rate limit* `ctv_eval` por usuário, teto de `2 MiB` na leitura xAI, seleção do IP público mais à direita em `X-Forwarded-For`, isolamento do cookie `cogniva_missions_session` e trava `deleting_account` contra corridas durante exclusão física LGPD.

### 3. Portais Web, Scripts de Migração, Nginx IPv6 `/64` e Promoção Atômica (`web-*`, `scripts/`, `deploy/`, `.github/workflows/`)
- **Curadoria `/admin/`, Acessibilidade WCAG 1.4.11 e Scripts (`web-admin/`, `web-missions/`, `scripts/`):** Restauração reversível de `evaluated_content_revision` ao desmarcar `reconfirmValidity`, leitura de `payload.detail` em respostas `503` de manutenção, `mainShell.inert` no menu móvel, contraste não-textual `>= 3.0:1` em bordas de controles (`#758691` / `#5676a4`), modo *insert-only* padrão (`--force-overwrite` opcional) em `migrate_cnu_to_cogniva.py` e preservação de pontuações fracionárias em `import_questions_batch.py`.
- **Nginx, Rollback e Workflows (`deploy/`, `.github/workflows/`):** Modificador `(?s)` (`PCRE_DOTALL`) na extração binária de prefixo IPv6 `/64`, inclusão de `quiz/ctv/evaluate$` em `cogniva_ai_mut`, trava `.cogniva-deploy.lock` e proteção de templates `.conf` em `sync_nginx_site.py`, verificação de `/api/v1/ready` no rollback de `promote.py` e permissões mínimas explícitas em `emulator-insets.yml`.

---

## [2.4.5] - 2026-11-26 (build 35)

Auditoria e consolidação do **Motor de Avaliação Metacognitiva** e refinamento da experiência de resolução de questões (`shared/`, `backend-rust/` e `android-app/`), eliminando redundâncias de interação pós-resposta, padronizando ergonomia visual e preservando integralmente dados históricos.

### 1. Descontinuação Controlada da Rastreabilidade Pós-Gabarito Legada e Integridade Histórica (`PracticeAndQuestionScreen.kt`, `metacognitive_engine`, `knowledge.rs`)
- **Remoção do Formulário Pós-Gabarito dos 3 Sinais:** Descontinuado na interface de resolução o bloco legado *"Rastreabilidade da Resposta · 3 Sinais (Opcional)"* (`evidence_challenge`), eliminando viés retrospectivo pós-exposição ao gabarito e acoplamentos ociosos na camada de UI/ViewModel, enquanto preserva `POST /api/v1/quiz/attempt-evidence` e `pending_evidence_patches` para drenagem idempotente de registros *offline* históricos.
- **Separação Estrita de Proveniência (`RETROSPECTIVE_LEGACY_EVIDENCE`):** Registros históricos com `selected_evidence_id` permanecem preservados sem reescrita destrutiva; evidências coletadas após a exposição ao gabarito sem estratégia pré-gabarito são classificadas explicitamente como `RETROSPECTIVE_LEGACY_EVIDENCE` (`credits_comprehension = false`), jamais sendo equiparadas automaticamente a declarações pré-exposição.

### 2. Desambiguação Contextual de Chutes e Padronização Visual de `CognitiveStrategy` (`QuestionComponents.kt`, `domain-contracts.json`)
- **Consolidação sem Redundância entre Confiança e Estratégia:** Ao selecionar `"Chutei"` (`DeclaredConfidence.GUESS`), o seletor apresenta confirmação simplificada e opcional da natureza do chute (*Chute totalmente aleatório*, *Chute após eliminar alternativas* ou *Chute por intuição ou reconhecimento parcial*), consolidando sinais redundantes sem segunda etapa obrigatória, sem fabricar estratégia quando omitida e diferenciando analiticamente chutes após eliminação (`UNCONFIRMED_MASTERY` / `POSSIBLE_KNOWLEDGE_GAP`) de chutes puramente aleatórios (`POSSIBLE_CASUAL_HIT` / `UNDEMONSTRATED_KNOWLEDGE`).
- **Cartões Uniformes de Largura Completa e Rótulos Concisos:** Substituição do `FlowRow` irregular por cartões tipo *radio* uniformes (`StrategyRadioOptionRow`, `fillMaxWidth()`, `minHeight >= 48.dp`, indicador circular alinhado à esquerda, cores neutras sem verde/vermelho) e textos concisos de menor carga cognitiva validados em `360dp`, `411dp`, `700dp`, `Font Scale 1.3`/`2.0` e temas claro/escuro via Paparazzi.

### 3. Leitura Metacognitiva em Duas Camadas e Deduplicação Pós-Resposta (`PracticeAndQuestionScreen.kt`, `PerformanceEngines.kt`)
- **Camada Primária Pedagógica + Camada Secundária Expansível:** Remoção do banner vermelho duplicado de erro com alta confiança e reestruturação do retorno pós-resposta em mensagem pedagógica concisa e não-julgamental (`pedagogical_feedback_pt`, integrada ao desfecho da Verificação Complementar `CTV` sem repetir gabarito, confiança ou tempo ativo já exibidos no cabeçalho) acompanhada de divulgação progressiva (`"Ver detalhes da avaliação"`) com sustentação diagnóstica, estratégia, interações, histórico prévio e explicação técnica do motor.

---

## [2.4.4] - 2026-11-26 (build 34)

Evolução ponta a ponta para **Verificação Conceitual Alinhada à Regra Decisiva, Semântica Simétrica Online/Offline, Calibração Temporal *Walk-Forward*, Recomendação Adaptativa e Reutilização Auditável de Questões (`CTV — Database First, AI on Demand`)** integrada em `shared/`, `scripts/`, `backend-rust/`, `web-admin/` e `android-app/`.

### 1. Alinhamento Conceitual e Verificação da Regra Decisiva (`ctv_alignment`, `ctv_engine`, `Room v8`)
- **Identificação do Conceito-Alvo e da Regra Decisiva (`CONCEPTUAL_TRANSFER` vs. `TOPIC_PRACTICE`):** Resolução explícita de `target_concept_id` e `tested_rule` na tentativa original (preservando ambiguidade quando há múltiplos conceitos primários), priorização de itens elegíveis inéditos que exigem aplicar a mesma regra em nova situação (`SAME_CONCEPT_AND_RULE_NEW_CONTEXT`), rejeição de cópias/paráfrases ou preservação literal da pista do gabarito e classificação honesta de itens apenas do mesmo tópico como `TOPIC_PRACTICE` (mantendo o conceito original em `TRANSFER_UNMEASURED`).
- **Governança Estrita sobre Itens Gerados por IA (`ctv-generator/2`, `question-v2-validator-2.4.0`):** Candidatos gerados com `normative_certainty = "UNCERTAIN"` ou aderência conceitual duvidosa são retidos em `PENDING_HUMAN_REVIEW` (`MONITORING`), decodificados exclusivamente por `decode_for_ctv` (mantendo `StandardObjective` e `SIMULADO` isolados até `HUMAN_CONFIRMED`) e suspensos automaticamente (`SUSPENDED`) sob reportes abertos ou anomalias operacionais.

### 2. Semântica Unificada Online/Offline e Reconciliação Multi-Dispositivo (`ctv_reconciliation`, `ConcursoRepository`)
- **Denominador Independente Simétrico (`evaluate_ctv_independence`):** Separação estrita entre qualidade da verificação, condição de aplicação e resultado (`is_correct` entra exclusivamente no numerador; respostas erradas em complementares inéditas pré-feedback contabilizam `+1` tentativa independente e `0` acertos tanto online quanto em modo avião).
- **Ciclo de Vida Real da Oferta (`ctv_offers`, `POST /api/v1/quiz/ctv/events`, `GET /api/v1/quiz/ctv/state`):** Distinção entre pré-carregamento (`SUPPLIED` = `0` exposições), primeira exibição real do enunciado (`DISPLAYED`, idempotente em rotações/reaberturas), dispensa via *"Ver gabarito agora"* (`DISMISSED` = `0` tentativas independentes, marcando o item e sua `content_revision`/`semantic_revision` como vistos em reencontros futuros) e resposta após ajuda reveladora (`POST_INSTRUCTION_FIXATION` = `0` tentativas independentes, preservando independência em `contextual-lookup` genérico ou perda de foco isolada), com reconciliação cronológica entre aparelhos e contagem explícita de `UNKNOWN_CONDITION` para registros legados.

### 3. Força da Evidência, Calibração *Walk-Forward* e Próxima Ação de Estudo (`calibration_engine`, `prioritization_engine`, `learning_analytics`)
- **Fundamento Verificável sem Crédito por Omissão (`classify_hit_grounding`):** Acertos sem estratégia ou evidência observável permanecem como `UNKNOWN_GROUNDING` (`0` crédito de compreensão), reconhecendo `LOGICAL_DEDUCTION` e `ELIMINATION` como `VALID_FUNCTIONAL_STRATEGY` e preservando divergências (`DIVERGENT_RATIONALE`), com frases explícitas de amostra, numerador, denominador e cobertura.
- **Calibração Temporal *Walk-Forward* (`calibration-walkforward-v2.0`) e Coortes Adaptativas:** Previsão $p_t$ estritamente anterior a cada tentativa ordenada por `(occurred_at, attempt_id)`, separando `TRUE_FALSE` e `MULTIPLE_CHOICE` nos 5 níveis de `DeclaredConfidence`, identificando previsões *cold-start*, calculando Brier puro $\frac{1}{N}\sum(p_t - y_t)^2$ e desagregando as coortes `DIFFICULTY_DETECTED` vs. `PERIODIC_SAMPLING` (dispensas excluídas do denominador).
- **Recomendação Guiada pelo Diagnóstico (`GET /api/v1/study/next-action`, `POST /api/v1/study/recommendation-feedback`):** `priority_utility_metacognitive` e `ADAPTIVE_LOOP` desempatam a favor de equívocos recorrentes com alta confiança, agendam retomada espaçada após fixação pós-explicação, solicitam transferência sob evidência conflitante, avançam após transferência funcional verificada e registram recomendação, aceitação/dispensa, tempo adicional e acerto inédito subsequente.

### 4. Paginação Completa de Reuso, Revalidação Pós-Duplicata e Custo por Verificação Concluída (`ctv_admin_metrics`, `/admin/`)
- **Varredura por Cursor e Reconsulta sob Trava:** Busca paginada estável (`_id > cursor`) em `todasQuestoes` registrando `SEARCH_INCONCLUSIVE` em falhas/tetos de leitura, reconsulta sob trava de unidade e após reserva de cota, persistência permanente antes da entrega (`AI_GENERATED_PERMANENT`) e revalidação integral de elegibilidade, conceito e exposição caso a IA produza duplicata (`DUPLICATE_PREVENTED`: `1` chamada real, `1` duplicata barrada, `0` chamadas evitadas).
- **Contabilidade Operacional vs. Simulador Hipotético (`GET /api/v1/admin/ai-ctv/metrics`):** Agregação em *streaming* de todo o período filtrado (sem truncamento silencioso), separação explícita entre entregas por cache offline e reuso online, e indicador auditável de **Custo por verificação concluída** (`custo atribuível / verificações respondidas`, exibindo `"sem conclusões"` quando o denominador é zero e rotulando valores estimados) separado visualmente do Simulador de Economia com Reutilização.

---

## [2.4.3] - 2026-11-23 (build 33)

Revisão integral do **processo pós-resposta** nas sessões de questões e simulados (`backend-rust/` + `android-app/`), eliminando duplicações textuais, falsos positivos de cláusulas de exceção, truncamentos no meio de palavras, chips redundantes/incompatíveis com o formato do item e divergências de IDs no Diagnóstico dos 3 Sinais.

### 1. Síntese Editorial Determinística e Diagnóstico de 3 Sinais (`backend-rust/`)
- **Eliminação de Falsos Positivos em `"Ressalva / Exceção"` (`models/question.rs`):**
  - Restrição de `extract_genuine_exception_sentence` exclusivamente a conectivos reais de ressalva (`salvo`, `exceto`, `excetuado`, `ressalvado`, `à exceção`, `sem prejuízo`), removendo advérbios de escopo/finalidade como `apenas` ou `independentemente` que faziam o próprio enunciado ser repetido como `"Ressalva / Exceção"`.
  - Extração restrita a fontes fidedignas (excertos normativos ativos do catálogo ou fundamentação oficial `explanation`, admitindo o enunciado apenas em itens `Certo` sem comentário cadastrado) — impedindo que assertivas falsas (`Errado`) fossem exibidas como exceções verdadeiras.
- **Higiene de `tested_rule`, Recorte Decisivo e Rastreabilidade sem Eco (`models/question.rs`, `services/ai_tutor.rs`):**
  - Priorização do excerto normativo oficial do catálogo antes do fallback em `explanation`, remoção determinística de prefixos redundantes de veredito (`Certo.`, `Errado.`, `A alternativa correta é...`, `✅:`/`❌:`) e separação de rodapés `Referência(s): ...`.
  - Truncamento respeitando fronteira de palavra (eliminando cortes abruptos como `Co…`), janela focada em torno do delimitador lógico para enunciados extensos (`<= 135` caracteres) e reformulação neutra e concisa das opções de `evidence_challenge` (`EV_DECISIVE` vs. `EV_PREMISSA_DIVERGENTE`) com ordem determinística por `question_id` e zero repetição da explicação principal.
  - Adaptação do prompt pós-resposta de `AiAssistKind::Plausibility` quando invocado sobre itens binários `TRUE_FALSE`.
- **Retrocompatibilidade de IDs de Evidência (`services/attempts.rs`, `routes/study_loop.rs`, `services/knowledge.rs`):**
  - Normalização transparente de IDs sintetizados legados (`ev_decisive`, `ev_err_1`, `ev_err_2` → `EV_DECISIVE`, `EV_PREMISSA_DIVERGENTE`) preservando validação estrita para desafios curados customizados.

### 2. Superfície Única Pós-Resposta, Chips Contextuais e Estado de Sessão (`android-app/`)
- **Consolidação Visual sem Duplicidade (`PracticeAndQuestionScreen.kt`, `Models.kt`):**
  - Unificação da Ficha Editorial e do comentário principal em uma única superfície de alto contraste, suprimindo `Regra cobrada` e `Ressalva / Exceção` sempre que forem textualmente redundantes com `explanation` (`isPostAnswerTextRedundant`), limpando o prefixo repetido `Certo.`/`Errado.` abaixo do cabeçalho de gabarito e destacando `Referência(s):` em rodapé tipográfico próprio.
  - Saneamento automático em memória de payloads legados cacheados na versão `2.4.2` (`cached_questions` / `quiz_sessions`), corrigindo sessões offline mesmo antes da primeira ressincronização.
  - Rótulos concisos de gabarito em Múltipla Escolha (`Alternativa B` em vez de repetir parágrafos extensos no título do cartão), resumo compacto da tentativa registrada (`Sua marcação · Confiança · Tempo`) e supressão de erros genéricos `ERR_ALT_*` que apenas copiavam o texto literal da opção.
- **Chips de Aprofundamento Aderentes ao Formato e Navegação Pós-Resposta (`PracticeAndQuestionScreen.kt`, `MainViewModel.kt`, `ConcursoRepository.kt`):**
  - Exibição de **`Gerar Comentário IA`** (`explanation`) apenas quando a questão ainda não possui comentário exibido em tela (evitando reabrir em modal o mesmo texto já visível) com hidratação imediata em memória após geração, e restrição de **`Análise dos Distratores`** (`plausibility`) a questões `MULTIPLE_CHOICE`.
  - Preservação de `selectedEvidenceId` no mapa local `userAnswers` ao navegar entre questões (`Anterior` / `Próxima`), reconciliação em `syncPendingAttempts` para toques ocorridos durante envio HTTP em voo e reset automático de rolagem (`LazyListState.scrollToItem(0)`) ao avançar/voltar de questão.

---

## [2.4.2] - 2026-11-23 (build 32)

Auditoria profunda de arquitetura de testes, eliminação de *Mock Drift* e código morto, correção de defeitos críticos e altos revelados pela suíte remediada e expansão de cobertura comportamental nas quatro camadas do ecossistema (`backend-rust/`, `android-app/`, `web-admin/` + `web-missions/` e `scripts/` + `deploy/`), documentada integralmente em [`TEST_AUDIT_REPORT.md`](TEST_AUDIT_REPORT.md).

### 1. Backend Rust (`backend-rust/` — `113` testes unitários/roteador + `15` suítes de integração MongoDB)
- **LGPD Art. 18, Orçamento xAI e Segurança de Sessão (`auth_session.rs`, `security.rs`, `ai_tutor.rs`, `classification.rs`):**
  - Alinhamento do plano de exclusão LGPD em `question_review_events` para casar com `actor_user_id` / `principal_id` e ações pessoais (`COMPATIBILITY_*`, `EVALUATION_*`), preservando eventos editoriais do acervo compartilhado e mantendo `sessions` e `usuarios` nas duas últimas etapas.
  - Liquidação efetiva de cota (`quota_guard.commit()`) quando chamadas pagas reais à xAI são consumidas e bloqueadas por `detect_pre_answer_spoiler` (prevenindo dreno infinito de orçamento via repetição de spoiler), mantendo estorno automático em falhas HTTP/timeout do provedor; postergação de `commit()` em `classification.rs` até confirmação de persistência.
  - Rejeição imediata (`401`) de cabeçalhos `Authorization` inválidos/expirados também em `POST /api/v1/auth/register` (evitando criação silenciosa de conta vazia e perda do histórico convidado mobile), reutilização de cookie convidado ativo em `POST /api/v1/auth/guest` (`student_web`), execução de `dummy_password_hash` estritamente sob `spawn_blocking` + guarda pré-semáforo `1024` bytes e consolidação sem perda de múltiplos cabeçalhos `Vary` em `security::authenticate`.
- **Omissões Estratégicas, Motores `v5`, Missões e Índices (`missions.rs`, `selection_v2.rs`, `scheduler.rs`, `attempts.rs`, `study_loop.rs`, `knowledge.rs`, `question_storage.rs`):**
  - Isolamento completo de `is_blank = true` em `compute_demonstrated_mastery`, `fetch_related_human_questions`, `fetch_user_history_buckets`, `scheduler` diário e `calculate_monthly_study_frequency`.
  - Retenção espaçada calculada também no nível do tópico/conceito (`>= 24h` entre acertos verificados em questões distintas) e ordenação cronológica determinística de observações em `study_loop.rs`; validação simétrica de `selected_evidence_id` em `submit-batch` e `attach_evidence`; conformidade RFC 9110 §13.1.5 em `If-Range` (rejeitando weak ETag `W/`); suporte a overrides `mission_definitions` em `resolve_media_asset_fast` e `/api/v1/admin/missions`; e correção do índice `idx_questions_schema_outbox` para `schema_outbox._id`.

### 2. Aplicativo Nativo Android (`android-app/` — `125` testes JVM + Paparazzi + API 35 E2E)
- **Concorrência, Projeção Offline, Missões e Segurança OTA (`ConcursoApp.kt`, `Learning.kt`, `ConcursoRepository.kt`, `AppUpdateManager.kt`, `MainActivity.kt`, `AuthStore.kt`, `MarkdownParser.kt`, `QuestionImportDraft.kt`):**
  - Cancelamento síncrono de `OfflineSyncWorker.UNIQUE_WORK_NAME` (`"ConcursoPrepOfflineSyncWork"`) e `"CognivaImmediateSync"` antes da troca ou exclusão física do SQLite por conta; preservação de `practiceAvailable = false` em `LearningProjection.projectHistory`.
  - Eliminação da contagem dupla (`2x`) de omissões estratégicas em `buildLocalDecisionTelemetry`; sincronização ordenada por cena e em 2 etapas (`firstSelectedOptionId` → `latestSelectedOptionId`) em `flushDirtyMissionRecord` preservando `first_attempt_correct = false` e `scenePositionsMs`.
  - Bloqueio de path traversal percent-encoded (`%2e`, `%2f`, `%5c`) e fragmentos `#` em `AppUpdateManager.resolveDownloadUrl`, rastreamento explícito de coletores visíveis (`STARTED`) para disparo garantido do diálogo do `PackageInstaller` em background/rotação e remoção do método legado `ConcursoRepository.downloadApkUpdate`.
  - Filtro `ownerUserId` em `updateAttemptEvidence`, limpeza transacional completa das tabelas de concursos em `deleteAccount`, suporte a URLs HTTPS UTF-8 (Wikipédia PT) + cercas de 4+ crases em `MarkdownParser` e exigência de `source_reference` para `confirmOrigin = true`.

### 3. Portais Web (`/missoes/` e `/admin/`) e Ferramental Python/Deploy (`web-missions/`, `web-admin/`, `deploy/`, `scripts/`)
- **Portal `/missoes/` (`97,49%` Lines) e Painel `/admin/` (`91,16%` Lines):**
  - Correção crítica do envio de `QuestionDto.question_revision` e tratamento explícito de `SubmitBatchResponse.rejected` em `/missoes/`; parada garantida de `<video>` (incluindo preload oculto) e trava `busy` em transições, `logout` e `401`; rótulos dinâmicos de *Duration* no SVG.
  - Leitura de `proposed_links` e filtros reais (`ALL`, `PENDING`, `ACCEPTED`, `REJECTED`, `STALE`) na Classificação IA do `/admin/`; invalidação de `preview` e bloqueio de `"Confirmar edição"` sob `editorialError`; correção de `classification.state = "PARTIAL"`; validação estrita de rubrica `OPEN_ENDED` e auditoria WCAG 2.1 AA extraída diretamente de `web-admin/style.css`.
- **Infraestrutura, Promoção Atômica e CLI (`100` testes Python, `76,26%` cobertura nos scripts rastreados):**
  - Reload garantido do Nginx ao restaurar `/etc/nginx/cogniva-gate.conf` em falha pré-mutação (`if not runtime_mutated:`) e gravação de `previous_image` por digest imutável (`@sha256:`) em [`deploy/promote.py`](deploy/promote.py); rollback atômico de `sites-available/cogniva` + `conf.d/cogniva-rate-limits.conf` em [`deploy/sync_nginx_site.py`](deploy/sync_nginx_site.py) e [`deploy/bootstrap-ip-tls.sh`](deploy/bootstrap-ip-tls.sh); logout garantido em `try ... finally` para `--mint-admin-token` em [`scripts/cogniva_import.py`](scripts/cogniva_import.py); suporte a `--report-out` em [`scripts/migrate_questions_v2_1.py`](scripts/migrate_questions_v2_1.py) e preservação de validade `SEMANTIC` V2.1 em [`scripts/migrate_cnu_to_cogniva.py`](scripts/migrate_cnu_to_cogniva.py).

---

## [2.4.1] - 2026-11-22 (build 31)

Auditoria global ponta a ponta nos 268 arquivos versionados do repositório (`shared/`, `scripts/`, `deploy/`, `.github/workflows/`, `web-missions/`, `web-admin/`, `backend-rust/`, `android-app/` e `docs/`), consolidando endurecimento de segurança, controle de concorrência, proteção contra exaustão de memória (`192 MB`), conformidade LGPD Art. 18, paridade matemática multi-runtime (`contract_version: 5`) e resiliência *offline-first*.

### 1. Segurança, Autenticação, Borda TLS/Nginx e LGPD Art. 18 (`backend-rust/`, `deploy/`, `android-app/`)
- **Hardening HTTP, Sessões e Criptografia (`security.rs`, `auth_session.rs`, `error.rs`, `config.rs`, `models/user.rs`):**
  - Semáforo global de concorrência Argon2id (`3` permissões simultâneas) e limite de `1024` bytes na entrada de senha prevenindo exaustão dos `192 MB` de RAM; redação explícita de `Debug` em `AppConfig` e `UserDocument`; mascaramento integral de detalhes internos em `AppError::Internal` (registrados apenas no log com `correlation_id`).
  - Rejeição imediata (`401`) de cabeçalhos `Authorization` inválidos sem fallback silencioso para cookies; comparação *case-insensitive* do esquema `Bearer `; preservação de `Cache-Control: private, no-store` em respostas autenticadas por cookie com mesclagem em `Vary: Cookie`; bloqueio de spoofing de cabeçalho `Host` contra `COGNIVA_PUBLIC_ORIGIN` não-loopback.
  - Promoção `guest -> conta registrada` remove `$unset` de `installation_id` e `installation_secret_sha256` e revoga sessões anteriores; recuperação automática de corrida `E11000` em `POST /api/v1/auth/guest`; revogação imediata de cookie web anterior ao reautenticar; limpeza condicional de `Set-Cookie` apenas em clientes web; hash SHA-256 de `subject_key` em `auth_rate_limits`.
  - **Exclusão LGPD Art. 18 Ordenada e Resiliente (`DELETE /api/v1/auth/account`, `ConcursoRepository.kt`, `ConcursoApp.kt`):** anonimização prévia de `reportedQuestoes`, expurgo completo das coleções derivadas (`mission_progress`, `userActivityLog`, `attempt_receipts`, `user_exams`, etc.) e remoção estritamente por último de `sessions` e `usuarios` (permitindo retentativa segura em falha transitória), além de limpeza integral de `learning_records`, `local_metadata`, cache de questões e diretórios locais `missions`/`updates` no Android.
- **Nginx, Promoção Atômica e OTA (`deploy/`, `AppUpdateManager.kt`):**
  - Sincronização atômica em [`deploy/sync_nginx_site.py`](deploy/sync_nginx_site.py) cobrindo tanto `/etc/nginx/sites-available/cogniva` quanto `/etc/nginx/conf.d/cogniva-rate-limits.conf` com validação `nginx -t` e rollback imediato; isenção de leituras `GET`/`HEAD` da fila de propostas no rate-limit estrito de IA (`$cogniva_ai_limit_key`); zona dedicada `cogniva_media` (`60r/s`, `burst=120`) para streaming `HTTP Range` de Missões; inclusão de `native-session` em `cogniva_auth`; eliminação de cabeçalhos duplicados (`proxy_hide_header`); `charset utf-8` no gate `503`; preservação de `env_file` e restauração atômica em [`deploy/promote.py`](deploy/promote.py); renovação TLS antecipada para `60h` (`-checkend 216000`) alinhada ao gate de `>= 48h`.
  - Validação estrita de esquema, host, porta, ausência de `userInfo` e path traversal via `java.net.URI` em `AppUpdateManager.resolveDownloadUrl` (eliminando bypass por prefixo de domínio) e supressão de diálogo duplo no `ApkInstallStatusReceiver` quando a Activity já observa `statusEvents`.

### 2. Concorrência, Memória, Motores Simétricos e Paridade Cross-Stack (`backend-rust/`, `android-app/`)
- **CAS, Guardas RAII de Cota e Projeções MongoDB (`missions.rs`, `rate_limit.rs`, `ai_tutor.rs`, `selection_v2.rs`, `study_loop.rs`, `question_storage.rs`):**
  - Loop Compare-And-Swap (`revision` + recuperação `E11000`) em `mission_progress`, imposição de decisões obrigatórias prévias antes de concluir cena subsequente, filtragem de carreiras `ARCHIVED`, streaming de mídia e APK com handle único e suporte combinado `If-None-Match` + `Range` + `If-Range`.
  - `QuotaGuard` RAII garantindo estorno automático da cota xAI (`tutor_budgets`) em qualquer retorno antecipado/cancelamento em `ai_tutor.rs`, `classification.rs` e `question_import.rs`; retry CAS em `ai_resource_meta`; lookup enciclopédico em `ai_help.rs` limitado a `2,2s` e `64 KiB` com rate-limit por usuário; projeções MongoDB enxutas em `selection_v2.rs` e `study_loop.rs`; correção do índice parcial `idx_mission_definitions_mission_id` e adição de índices compostos em `question_storage.rs`.
- **Simetria de Tentativas, Omissões Estratégicas e Sincronização Android (`attempts.rs`, `PerformanceEngines.kt`, `AuthStore.kt`, `ConcursoRepository.kt`, `MainViewModel.kt`, `Learning.kt`):**
  - Isolamento completo de omissões estratégicas (`is_blank = true`) nas médias de tempo/confiança, variância de alternativas, caderno de erros e projeção local `LearningProjection.projectHistory`; persistência completa de `LearningEvent` no backend; limitação de intervalo SRS (`1..=3650` dias) e validação de `selected_evidence_id` contra o desafio efetivo da questão.
  - Normalização `apiUserId` (`0` para `ownerUserId < 0` offline) evitando rejeições `403` ao sincronizar sessões convidadas criadas sem rede; espelhamento imediato do token guest em `AuthStore.ensureSession`; preservação de progresso local `dirty` e cache concorrente de vídeo verificando `.sha256` no módulo Missões; guarda contra `NaN`/`Infinity` e limitação `uEarly <= 0.85` idêntica entre Rust e Kotlin; regras R8 completas para enums `@SerialName` e serializadores internos.

### 3. Portais Web (`/missoes/` e `/admin/`), Ferramental CLI e Contratos (`web-missions/`, `web-admin/`, `scripts/`, `shared/`)
- **Portal `/missoes/` (`web-missions/app.js`):** Atualização cirúrgica *in-place* do gráfico de Valor Presente e dos controles de legenda/transcrição/auto-avanço sem reconstruir o `<video>` ativo, parada explícita de mídia ao sair/deslogar/expirar sessão e trava anti-duplo envio na prática de questões reais.
- **Painel `/admin/` (`web-admin/`):** Validação em linha do JSON de `editorial_card` bloqueando envio inválido, persistência imediata de `reference_date` e redesenho por `basis`, estilo `.button-link` com contraste WCAG AA e documentação precisa dos TTLs de sessão (`12h` cookie web vs. `30d` Bearer Android).
- **Contratos e Scripts (`shared/`, `scripts/`):** Alinhamento de `PROVA_REALIZADA` em `exam-templates.schema.json`, modo seguro *dry-run* por padrão em `migrate_cnu_to_cogniva.py`, emissão efêmera automática de token administrativo (`--mint-admin-token` + logout) e normalização `LEGAL` + `review_due_on` em `cogniva_import.py`, e verificação de integridade/CSP estendida ao portal `/missoes/` em `verify-web-admin-assets.py` e `verify-production-release.py`.

---

## [2.4.0] - 2026-11-20 (build 30)

Implementação e integração completa do módulo **Missões** (`shared/missions-catalog.json`, `backend-rust`, `/missoes/`, `/admin/` e aplicativo nativo Android), entregando o episódio piloto **Missão 01: O Choque dos Juros** (*Banco Central do Brasil · Analista — Economia e Finanças · Finanças / Renda Fixa*).

- **Catálogo Declarativo Multi-Runtime (`shared/schemas/missions.schema.json`, `shared/missions-catalog.json`, `scripts/missions_contracts.py`):**
  - Hierarquia declarativa `Carreira → Disciplina → Temporada → Missão → Cena → Interação → Evidência` validada por schema JSON e SHA-256 dos ativos locais, permitindo adicionar novas carreiras, temporadas e missões sem alterar o código dos players.
- **Backend Rust & Streaming `HTTP Range` (`services/missions.rs`, `routes/missions.rs`, `routes/web_missions.rs`):**
  - Streaming binário em blocos de 64 KB fora do `CompressionLayer` (`206 Partial Content`, `Accept-Ranges: bytes`, `ETag`/`304`, `416`), legendas `WEBVTT` sincronizadas em PT-BR, guarda sequencial anti-adulteração, sessões web `student_web` com cookie `HttpOnly` + CSRF e expurgo LGPD Art. 18 em `mission_progress`.
- **Portais Web (`/missoes/` e `/admin/`) e Aplicativo Android (`MissionsScreen.kt`, `MissionsModels.kt`):**
  - Player imersivo 16:9 com **Modo Transcrição Ativo** automático em indisponibilidade de vídeo, gráficos matemáticos nativos na Cena 4 ($PU = VF / (1+i)^n$) e na Cena 5 ($\Delta P / P$ por *Duration*), e separação epistemológica estrita entre progresso narrativo, decisões formativas e avaliação formal em questões humanas reais (`todasQuestoes`).

---

## [2.3.0] - 2026-11-20 (build 29)

Evolução disruptiva do Cogniva em três camadas epistemológicas e comportamentais (**Escassez com Diagnóstico por Evidências**, **Simulador de Decisão sob Incerteza & Curva de 300 Minutos** e **Protocolo de Entrada de 60 Segundos "Terça · 22h"**), somada à **Consulta Contextual em Camada (Catálogo + IA + Google Overlay)**, estatísticas aprofundadas em **Progresso** e correção de sincronização pós-resposta (`contract_version: 5`).

### 1. Escassez — Diagnóstico por Evidências, Ficha Editorial e Regra `< 15` (`shared/`, `backend-rust/`, `web-admin/`, `android-app/`)
- **Ficha Editorial Rastreável (`editorial_card` em `question-contracts.schema.json`, `question-v2-validator-2.3.0` / `CURRENT_VALIDATOR_REVISION = 6`):**
  - Suporte nativo no schema V2, no editor do `/admin/`, no backend Rust e no Android para `legal_provisions[]`, `tested_rule`, `exception_clause`, `decisive_stem_excerpt`, `official_source_excerpt` e `plausible_conceptual_errors[]` fora do `content_revision`, com sintetizador determinístico baseado nas fontes normativas do catálogo (zero geração sintética de questões).
- **Diagnóstico Cruzado de 3 Sinais e Sincronização Pós-Resposta (`diagnostic_engine`, `POST /api/v1/quiz/attempt-evidence`):**
  - Cruzamento de **resposta × confiança declarada × evidência selecionada** tanto em erros quanto em acertos, identificando `ILLUSORY_COMPETENCE` quando o candidato acerta o gabarito apoiado em premissa incorreta.
  - Novo endpoint idempotente `POST /api/v1/quiz/attempt-evidence` e fila durável `pending_evidence_patches` no Android eliminando perda do `selected_evidence_id` quando o lote `submit-batch` sincroniza antes do clique opcional pós-resposta.
- **Restrição Explícita `< 15` Questões e Transferência Cross-Banca (`MIN_QUESTIONS_FOR_MASTERY = 15`, `TRANSFER_PROBE`):**
  - Com menos de 15 questões humanas distintas, o motor identifica indícios de erro e marca progresso positivo como `EVIDENCE_IN_PROGRESS`, jamais afirmando domínio (`VERIFIED_COMPETENCE`) sem `>= 15` itens e evidência independente em outra banca (`TRANSFER_VERIFIED` vs. `CONSOLIDATED_NO_TRANSFER`). Quando inexiste questão humana inédita de outra banca, registra explicitamente `TRANSFER_UNMEASURED`.

### 2. Fadiga, Pontuação Líquida e Simulador de Decisão sob Incerteza (`strategy_engine`, `fatigue_engine`, `contract_version: 5`)
- **Maximização de Atingimento de Meta e Complementaridade Binária (`simulate_decision_under_uncertainty`, `normalize_binary_choice_probability`):**
  - Em provas estilo Cebraspe ($+1 / -1 / 0$), $VE = 2p - 1$ ($p = 0{,}55 \implies +0{,}10\text{ pt}$) e complementaridade binária ($48\% \leftrightarrow 52\%$). O motor calcula analiticamente $P(S_{\text{final}} \ge \max(S_{\text{meta}}, S_{\text{min}}))$ para **Responder** vs. **Omitir** integrando estado atual da prova, itens restantes e incerteza epistêmica $\sigma_p$ em cenários simulados de `35m`, `165m`, `275m` e `285m`.
- **Modelo Logístico Individual de 300 Minutos com Controle de Dificuldade (`performance_at_minute`, `evaluate_fatigue_with_difficulty`):**
  - Implementação de $p(t) = \sigma(\operatorname{logit}(p_0) - \lambda_i \frac{t}{300})$ ajustado à dificuldade dos itens e regularizado por precisão informacional, distinguindo `FATIGUE_DETECTED` ($\lambda_i > 0$), `STABLE_ENDURANCE` e `WARMUP_GAIN_DETECTED` ($\lambda_i < 0$) apenas após `>= 3` simulados longos contínuos e `>= 120` itens cronometrados.

### 3. Protocolo de Entrada de 60s ("Terça · 22h"), Consulta em Camada e Analytics (`TodayScreen.kt`, `PracticeAndQuestionScreen.kt`, `ProgressScreen.kt`)
- **Primeira Tela Reversível sem Culpa (`TodayScreen.kt`, `QuizMode.ENTRY_60S`):**
  - Remoção de metas diárias atrasadas, barras de cobrança e contadores alarmistas da tela inicial. Cabeçalho temporal `DIA · HORA` (`UTC-3`), proposta **"Só uma questão."**, botão **`Resolver 1 questão`** (`Certo · Errado · Branco`, gabarito + fundamento imediato, saída **`Encerrar`** ou **`Resolver outra`**) e saída visível **`Sem estudar hoje`** com métricas voluntárias de início, conclusão da 1ª questão e retorno espontâneo.
- **Seleção de Texto e Consulta em Camada (`ContextualLookupSheet.kt`, `POST /api/v1/questions/:id/lookup`, `term-lookup`):**
  - Seleção nativa de texto no enunciado (`LayerSelectionTextToolbar`) + chip **`Consultar / Google`** abrindo Bottom Sheet sobreposto com fontes normativas do catálogo, síntese aberta Wikimedia PT, explicação IA sem spoiler (`term-lookup` / `term-lookup-post`, nunca gravada em cache compartilhado) e **Pesquisa Google / Planalto / STF-STJ em Camada** via Partial Custom Tab sobre a questão.
- **Estatísticas Aprimoradas em Progresso (`ProgressScreen.kt`):**
  - Novos painéis de Confiabilidade Epistêmica (`< 15`), Mapa de Transferência Cross-Banca, Diagnóstico dos 3 Sinais + saldo de Omissões Estratégicas, Cenários de Decisão sob Incerteza, Curva de 300 Minutos $p(t)$, Hábito Voluntário 60s e amostra distinta `X/15` por disciplina.

---

## [2.2.0] - 2026-11-18 (build 28)

Evolução estrutural do Cogniva para um **sistema inteligente de preparação orientado por evidência de aprendizagem**, implantando o Grafo de Conceitos e Fontes Normativas Versionadas, os **5 Motores Simétricos de Decisão** (`contract_version: 4`), a reserva determinística **Holdout 20%**, o **Fallback Cross-Banca** em simulados e o **Core Loop Diário de 60 Minutos** em Rust, Python, Web Admin e Android (`Room v7`).

### Grafo de Conhecimento, Contratos `v4` e Validador `2.2.0` (`shared/`, `scripts/`, `question_storage.rs`)
- **Conceitos com Pré-Requisitos (DAG) e Fontes Normativas Versionadas (`shared/knowledge-catalog.schema.json`, `shared/knowledge-concepts-seed.json`, `scripts/knowledge_catalog.py`, `services/catalog_editor.rs`, `services/catalog_runtime.rs`):**
  - Preservação integral do hash gênese `af2d711b...` de [`shared/knowledge-catalog.json`](shared/knowledge-catalog.json) na cadeia `knowledge_catalog_activations` em produção, combinada ao seed canônico de `concepts[]` e `normative_sources[]` (`content_sha256` para rastreamento de impacto normativo) e validação topológica de Kahn impedindo ciclos em `prerequisite_concept_ids[]`.
  - Extensão de [`shared/schemas/question-contracts.schema.json`](shared/schemas/question-contracts.schema.json) e migração explícita `question-v2-validator-2.2.0` (`CURRENT_VALIDATOR_REVISION = 5`) suportando `concept_id` opcional em `classification.links[]` (com fallback automático transparente `concept_id ?? topic_id`) e `evidence_challenge` opcional (`prompt`, `options`, `correct_evidence_id`) fora do hash `content_revision`.
- **Paridade Matemática Multi-Runtime (`shared/domain-contracts.json` `contract_version: 4`):**
  - Novos vetores determinísticos compartilhados entre Python, Rust e Kotlin para `holdout`, `diagnostics`, `retention`, `prioritization`, `calibration`, `strategy` e `fatigue`.

### Cinco Motores de Decisão, Endpoint `next-action` e Blindagem RAG (`backend-rust/`, `web-admin/`)
- **Motores Simétricos em `O(1)`/`O(N)` compatíveis com o limite de `192 MB` de RAM (`backend-rust/src/services/*_engine.rs` & `PerformanceEngines.kt`):**
  - **Diagnóstico (`diagnostic_engine`):** exige `distinct_questions >= 3` + retenção espaçada para `VERIFIED_COMPETENCE`, diferenciando `OVERCONFIDENCE_ERROR`, `CONCEPTUAL_ERROR`, `RETRIEVAL_FAILURE` e `INSUFFICIENT_EVIDENCE` sobre vínculos `PRIMARY` e `SECONDARY`.
  - **Retenção Contínua (`retention_engine`):** curva $R(t,S) = (1 + \frac{19}{81}\frac{t}{S})^{-0{,}5}$ com proteção anti-chute (`confidence <= 1` limita $S \le 1{,}5$ dia), integrada a `attempts.rs` e `scheduler.rs`.
  - **Priorização e Intercalação (`prioritization_engine`):** utilidade marginal por minuto ponderada pelo edital, impulso suave não-punitivo de pré-requisitos (`0.55^k`) e intercalação 40/40/20 (`ADAPTIVE_LOOP`).
  - **Calibração Beta-Binomial e Estratégia (`calibration_engine`, `strategy_engine`):** calibração empírica por formato (`TRUE_FALSE` vs `MULTIPLE_CHOICE`) e faixa de confiança (`1..3`), cálculo de $VE = (1+\lambda)\hat{p} - \lambda$, limiar $p^* = \frac{\lambda}{1+\lambda}$, postura próxima à nota de corte (`PROTECT_MARGIN` / `CALCULATED_RISK` / `MAXIMIZE_EV`), partição determinística **Holdout 20%** (`SHA-256`) e **Fallback Cross-Banca** automático em simulados (`selection_v2.rs`).
  - **Fadiga com Guarda Estrita (`fatigue_engine`):** permanece em `INSUFFICIENT_EVIDENCE` até acumular `>= 3` simulados longos contínuos sem interrupção e `>= 120` itens cronometrados.
- **Rota `GET /api/v1/study/next-action`, Tutoria Normativa e Curadoria (`/admin/`):**
  - Novo endpoint [`routes/study_loop.rs`](backend-rust/src/routes/study_loop.rs) retornando a questão de Entrada Inteligente, diagnóstico consolidado do edital, células Beta-Binomial, recomendação de estratégia e relatório de fadiga.
  - Injeção automática de fontes normativas vigentes do catálogo e diretriz `NORMATIVE_GROUNDING_GUARDRAIL_PT_BR` nos tutores pós-resposta em `ai_tutor.rs`; helpers de conceitos e detecção de impacto por `content_sha256` em `web-admin/domain.js`.

### Core Loop Diário de 60 Minutos e Room `v7` no Android (`android-app` `2.2.0` / `build 28`)
- **Migração Room `6 → 7` (`AppDatabase.kt`, `ExamLocal.kt`, `schemas/.../7.json`):** Adicionadas colunas de telemetria e decisão em `pending_attempts` (`isBlank`, `confidenceExplicit`, `selectedEvidenceId`, `positionInSession`, `elapsedSessionSeconds`, `sessionInterrupted`) preservando 100% da fila offline.
- **Jornada Diária sem Atrito e sem Punição (`TodayScreen.kt`, `PracticeAndQuestionScreen.kt`, `ProgressScreen.kt`):**
  - **01 · Entrada Inteligente (0–5 min):** card superior *"Comece com uma questão. Você pode encerrar após responder."* com justificativa concreta da prioridade e painel *"O que falta demonstrar até a prova"*.
  - **02 · Treinamento Adaptativo (5–45 min):** transição fluida pós-1ª questão para rota intercalada (`ADAPTIVE_LOOP`), seleção explícita obrigatória de confiança nas sessões ao vivo e card opcional **Diagnóstico de Evidência** pós-erro.
  - **03 · Estratégia de Prova (45–60 min) & Progresso:** modalidade `STRATEGY_BATTERY`, botão explícito **Deixar em Branco (Omitir · 0 pt)** sem distorcer acurácia/streak, e cards de **Calibração Empírica ($VE$ / $p^*$)** e **Resistência em Provas Prolongadas** em **Progresso**. Documentação completa em [`docs/MOTORES-DECISAO-CORE-LOOP.md`](docs/MOTORES-DECISAO-CORE-LOOP.md).

---

## [2.1.0] - 2026-11-17 (build 27)

### Contratos de Questões V2.1, Pacotes Prontos e Cabeçalho de Estudo no Android
- **Cabeçalho contextual de questões no Android (`PracticeAndQuestionScreen.kt`, `ExamEngine.kt`, `models/question.rs`):** Remoção do nível de dificuldade (`Fácil`/`Média`/`Difícil`) da barra superior de `QuestionSessionScreen` e inclusão de resolução canônica em tempo real (`ExamCatalog.resolveQuestionHeaderMeta`) exibindo `Disciplina · Origem/Ano` e o caminho hierárquico completo `Assunto › Subassunto` a partir do catálogo sincronizado (`/api/v1/catalog/knowledge`), evitando rótulos genéricos (`Disciplina Geral`) mesmo em cache local.
- **Guia e importador de pacotes prontos (`docs/PACOTES-QUESTOES.md`, `docs/INSERCAO-QUESTOES.md`, `scripts/cogniva_import.py`):** Documentação passo a passo para modelagem `Disciplina → TOPIC → SUBTOPIC` folha, validação local offline (`--check-local`), ativação versionada de catálogo, sincronização de `SUBTOPIC`s na grade do concurso, promoção automática para `classification = CONFIRMED` + `validity = CURRENT`, vínculo `DIRECT` e verificação pós-importação via sorteio real.

### Tutoria Pedagógica por IA (`ai-tutor/3`) e Blindagem Pré-Resposta
- **Bússola pedagógica interna sigilosa e filtro determinístico anti-spoiler nos tutores pré-resposta (`services/ai_tutor.rs`, `ConcursoRepository.kt`):**
  - Revisão dos prompts pré-resposta (`child-friendly-pre` / `ajudaInfantil`, `contextual-hint` / `contextualHint` com progressão explícita `#1..#3` e `dr-sincero` / `drSincero`) para receberem o gabarito sob chave dedicada `bussola_gabarito_sigiloso_nao_revelar` — garantindo alinhamento 100% com a banca sem risco de dica invertida/alucinação — combinada à diretriz `PRE_ANSWER_NO_SPOILER_GUARDRAIL_PT_BR` (*técnica de um nível de abstração acima*, leitura neutra/simétrica e perguntas-guia abertas sem revelar nem eliminar opções).
  - Adicionado validador determinístico pós-geração `detect_pre_answer_spoiler` em Rust (cobrindo também leitura de cache legado) com 1 tentativa automática de regeneração corretiva na mesma cota e estorno + bloqueio preventivo de persistência caso o vazamento persista.
  - No Android (`ConcursoRepository.kt`), `dr-sincero` passa a integrar o conjunto de tutores pré-resposta que jamais fazem fallback para `explanation` quando offline ou sob erro HTTP.
- **Revisão do contexto adicional pós-resposta (`extra-concepts` / `explanationExtra` e demais tutores pós-resposta):** Remoção do viés fixo em *"Administração Pública/Fiscalização"* e reestruturação didática ancorada na `disciplina` real da questão e na fundamentação explícita de `correct_answer` (contexto adicional, conceitos-chave/distinções, exemplos práticos e recorrência em prova), mantendo proibição de letras fixas (`A`, `B`, `C`) devido ao embaralhamento de alternativas. Documentação detalhada em [`docs/IA-XAI.md`](docs/IA-XAI.md), [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md) e [`backend-rust/README.md`](backend-rust/README.md).

---

## [2.0.1] - 2026-11-14 (build 26)

Blindagem estrutural pós-auditoria adversarial completa nos 8 eixos de engenharia (segurança, confiabilidade/SRS, performance, contratos Schema v2, UX/WCAG 2.1 AA, CI/CD, infraestrutura TLS/Nginx e governança LGPD/IA) sobre a arquitetura convergente `2.0`.

### Governança LGPD (Art. 18), Segurança e Privacidade de IA
- **Direito de eliminação imediata pelo titular (`DELETE /api/v1/auth/account` + fluxo Android):** Implementação física da exclusão de conta e dados pessoais mediante confirmação explícita `{"confirm": "EXCLUIR"}` no backend Rust e em **Ajustes → Privacidade e LGPD** no Android, removendo `usuarios`, `sessions`, `userActivityLog`, `attempt_receipts`, `user_exams`, `exam_revisions` de usuário, `question_exam_compatibility`, `answer_evaluations`, `tutor_budgets` individuais, rascunhos privados e requisições de IA, anonimizando `reportedQuestoes` e **preservando 100% do acervo compartilhado** (`todasQuestoes`, `question_revision_events`, `knowledge_catalog_*`, `exam_templates`). Adicionados [`PRIVACY.md`](PRIVACY.md) e [`SECURITY.md`](SECURITY.md).
- **Blindagem contra CSRF, enumeração e abuso de cotas:** Validação estrita de esquema + origem (`X-Forwarded-Proto` + `Origin`/`Referer`) e `Sec-Fetch-Site` em requisições autenticadas por cookie, extração resiliente de múltiplos cabeçalhos `Cookie`, CORS estrito sem curingas, limitador deslizante em memória por IP (`BoundedSlidingWindowLimiter`) nas rotas `/api/v1/auth/{guest,register,login,web-session}` e teto diário reduzido (`5/dia`) para contas convidadas no fuso `America/Sao_Paulo` (`UTC-3`).
- **Defesa contra injeção indireta de prompt e sigilo de cache (`xAI`):** Delimitação XML `<untrusted_student_input>` e `<untrusted_raw_source>` com neutralização de fechamento prematuro, bloqueio total de leitura/escrita em cache compartilhado quando há `user_note` ou resposta pessoal do estudante (`should_use_shared_ai_cache`) e estorno garantido de cota em falhas do provedor em `ai_tutor.rs` e `question_import.rs`.

### Confiabilidade Offline-First, Contratos V2 e Cliente Android (`build 26`)
- **Replay cronológico SRS e simetria de bônus:** Correção do fator de sequência em `srs_engine.rs` para utilizar `streak_atual` (em vez de `streak_max` histórico) e reordenação/replay cronológico em `attempts.rs` quando tentativas *offline* sincronizadas trazem `client_answered_at` anterior à última sessão registrada.
- **Endurecimento de contratos Schema v2:** Bloqueio de esquemas URI inseguros (`javascript:`, `data:`, `file:`) exigindo `^https?://` em `source_reference.uri` e `evidence[*].uri`, validação de limites em `exam_year` (`1900..=2200`) e `concursos` (`<= 50`) e conversão inteira BSON verificada sem truncamento silencioso em [`shared/schemas/question-contracts.schema.json`](shared/schemas/question-contracts.schema.json), Python e Rust.
- **Durabilidade e segurança no Android (`2.0.1` / `versionCode = 26`):** `android:allowBackup="false"` com regras explícitas de exclusão XML, `readTimeout(65s)` para chamadas IA, verificação estrita de tamanho + SHA-256 sem janela TOCTOU e limpeza de sessões órfãs no `PackageInstaller`, isolamento entre falhas de `syncPendingProfile` e `syncPendingAttempts`, descarte de itens em quarentena sem bloquear limpeza de cache, consulta em lote `learningForQuestions`, persistência atômica de simulados + rascunho resistente à morte de processo, parser Markdown multi-linha com links restritos a `https://`, tema próprio `Theme.Cogniva.EdgeToEdgeDialog` (zero `@PrivateResource`) e contraste WCAG 2.1 AA verificado por teste automatizado.

### Painel Web Admin (`/admin/`), Borda TLS/Nginx e CI/CD
- **Resiliência e acessibilidade WCAG 2.1 AA no `/admin/`:** Teto de 20 snapshots locais sem aninhamento recursivo em `localStorage` + botão *Limpar histórico local*, tratamento gracioso de `QuotaExceededError`, logout resiliente sob falhas HTTP `502`/`503`, banner de recuperação em falhas parciais de carregamento, detecção de conflito entre rascunhos limpos/sujos, validação amigável do JSON de rubrica `OPEN_ENDED`, rotação de `aiOperationId` após erros, paginação bidirecional, busca normalizada NFD, *drawer* móvel responsivo, `<dialog>` acessível com preservação de foco/`<details>` e contraste `>= 4.5:1` auditado em `web-admin/tests/app.test.js`.
- **Infraestrutura, Nginx e Pipeline Imutável:** Cabeçalhos HSTS (`max-age=604800`), `Referrer-Policy: no-referrer`, `Permissions-Policy` e JSON `503` (`Retry-After: 15`) em [`deploy/nginx-cogniva.conf`](deploy/nginx-cogniva.conf), zonas de rate-limit `4m` com prefixo IPv6 `/64` em [`deploy/nginx-rate-limits.conf`](deploy/nginx-rate-limits.conf), inspeção individual tolerante a contêineres efêmeros + *hardlink* de APKs + poda automática de releases antigas em [`deploy/promote.py`](deploy/promote.py), alinhamento `contract_version == 3` e auditoria direta de validade TLS (`>= 48h`) em [`scripts/package-release.py`](scripts/package-release.py) e [`scripts/verify-production-release.py`](scripts/verify-production-release.py), `cargo clippy -- -D warnings` em `validate.yml`, verificação `SHA256SUMS` em `deploy.yml` e permissões KVM restritas (`0660`) em `emulator-insets.yml`.

---

## [2.0.0] - 2026-11-12 (build 25)

Convergência arquitetural completa do ecossistema **Cogniva 2.0** (ADRs 01–12), unificando identidade, autenticação nativa, banco `cogniva` 100% V2, catálogo único de conhecimentos, governança de templates globais, curadoria administrativa e pipeline de promoção segura.

### Identidade, Infraestrutura e Segurança
- **Unificação de marca e runtime (`cogniva-api` `2.0.0`):** Banco MongoDB padronizado em `cogniva`, binário/container Rust renomeado para `cogniva-api`, Nginx consolidado em [`deploy/nginx-cogniva.conf`](deploy/nginx-cogniva.conf) com `COGNIVA_PUBLIC_ORIGIN` parametrizado, TLS IPv4/IPv6 e `certbot --deploy-hook` sem janelas de corrida.
- **Compatibilidade OTA Android preservada:** Mantido `applicationId = "br.com.concursoprep.app"` em [`android-app/app/build.gradle.kts`](android-app/app/build.gradle.kts) e o nome default de serviço `api-rust-core` em [`deploy/promote.py`](deploy/promote.py), garantindo atualização OTA transparente a partir da `1.4.17` (`build 24`).
- **Autenticação 100% nativa Cogniva (zero Telegram):** Remoção integral de fluxos, telas e flags de vínculo Telegram. Novo modelo nativo com modo convidado imediato (`POST /api/v1/auth/guest`), cadastro com upgrade *in-place* preservando o mesmo `user_id` (`POST /api/v1/auth/register`), login por e-mail e senha com hash `Argon2id` (`POST /api/v1/auth/login`), sessões revogáveis na coleção `sessions` (`Bearer` no Android com armazenamento cifrado via Android Keystore AES-GCM e cookie `HttpOnly`/`Secure`/`SameSite=Strict` + CSRF no `/admin/`) e concessão administrativa por `ADMIN_EMAILS` ou `cogniva-api --grant-admin <email>`.
- **Middleware de alta performance:** Substituição do buffer global de corpo em memória por `DefaultBodyLimit::max(2 * 1024 * 1024)` e cache `RwLock` com TTL de 10s (e invalidação imediata ao ativar catálogo) para snapshots de catálogo em execução.

### Catálogo Unificado, Templates Globais e Acervo 100% V2
- **Catálogo único (`shared/knowledge-catalog.json` v2):** Incorporação de `icon_key` nos troncos e `default_weight` nos vínculos de disciplinas, eliminando `shared/canonical-catalog.json` e o *split-brain* `v1`/`v2` entre Rust, Web Admin e Android.
- **Separação entre `exam_templates` e `user_exams`:** O painel `/admin/` administra templates globais (`shared/exam-templates.json`: `CPNU`, `RFB`, `BNDES`, `AFT`). Alunos sem customização recebem atualizações oficiais automaticamente; alunos com grade personalizada preservam suas edições intactas e visualizam aviso de atualização disponível (`upstream_update_available`) com adoção opcional (`POST /api/v1/exams/:id/adopt-template`). Sincronização atômica entre `is_active_in_ladder` e `usuarios.active_contest_ids`.
- **Normalização 100% V2 e hash único JCS (`contract_version: 3`):** Migração *read-only* do acervo de questões `CNU → cogniva` via [`scripts/migrate_cnu_to_cogniva.py`](scripts/migrate_cnu_to_cogniva.py), aposentadoria do hash binário legado e padronização universal em `content_revision` SHA-256 sobre JSON canônico RFC 8785 (JCS) em Python, Rust e Kotlin.
- **Questões `OPEN_ENDED` em modo preparatório explícito:** Validação completa com rubrica estruturada e hash JCS consistente, aviso claro de acervo preparatório no `/admin/` e no Android, e exclusão estrita das filas de prática objetiva.

### Curadoria Web Admin, IA xAI e Cliente Android
- **Curadoria completa no `/admin/`:** Persistência integral de `explanation`, `level`, `exam_year` e seletor explícito de `Validade` (`CURRENT`, `UNDER_REVIEW`, `OBSOLETE`, `UNKNOWN`) na edição V2; novas abas **Reportes** (com isolamento de reportes abertos por aluno denunciante) e **Classificação IA** (geração em lote e revisão humana sobre o catálogo ativo); aprovação multi-administrador de rascunhos `REVIEW_READY`.
- **Governança xAI:** Exigência explícita de `XAI_MODEL` quando `XAI_API_KEY` está configurada, teto global diário (`XAI_DAILY_GLOBAL_LIMIT`, padrão `500/dia`) somado à cota por conta (`XAI_DAILY_USER_LIMIT`, padrão `120/dia`), estorno automático de cota em falhas do provedor, `reqwest::Client` compartilhado e gravação de `ai_resource_meta` em todos os 8 tutores.
- **Cliente Android `2.0.0` enxuto e resiliente:** Migração Room `5 → 6` (metadados de templates e serialização JSON segura de bancas), filtro real por tópico em **Treinar Tópico** (`syllabusTopicId`), destravamento de rascunhos de importação em erros HTTP 4xx, drenagem segura de banco na troca de conta e minificação R8 (`isMinifyEnabled = true`, `isShrinkResources = true`) na build de release.
- **CI/CD e Higiene de Repositório:** Workflows ativos em `push` e `pull_request`, ensaio real de rollback Docker em CI sem `|| true`, verificação automática de links Markdown e limpeza de artefatos históricos obsoletos.

---

## [1.4.11 – 1.4.17] - Novembro de 2026 (Resumo Pré-Convergência)

- **1.4.17 (build 24):** Ajustes edge-to-edge no editor de concursos Android e centralização da ajuda estrutural.
- **1.4.16 (build 23):** Central de Ajuda pesquisável no `/admin/` sincronizada com [`docs/GUIA-ADMINISTRACAO.md`](docs/GUIA-ADMINISTRACAO.md).
- **1.4.13 – 1.4.15 (builds 20–22):** Painel administrativo web incorporado ao binário Rust, módulo de inserção individual/lote de questões V2 e metadados documentais de origem.
- **1.4.11 (build 18):** Isolamento definitivo das rotas binárias de APK fora de camadas de compressão dinâmica (`no-transform`, `proxy_buffering off`), documentado em [`docs/LICAO-APRENDIDA-OTA-APK.md`](docs/LICAO-APRENDIDA-OTA-APK.md).
