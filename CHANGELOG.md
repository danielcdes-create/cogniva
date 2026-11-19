# Changelog — Cogniva

Todas as mudanças arquiteturais, de segurança, acessibilidade e operacionais relevantes do ecossistema **Cogniva** são documentadas neste arquivo.

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
