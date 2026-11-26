# Contratos e Schema de Questões V2 (Cogniva 2.4 — Compatível `schema_version = 2`)

No **Cogniva `2.4.5`** (`NT-COGNIVA-QUESTIONS-002` + Ficha Editorial Rastreável, Grafo de Conceitos & Verificação Conceitual `CTV`), 100% do acervo em `cogniva.todasQuestoes` preserva `schema_version = 2` com endurecimento completo de proveniência autenticada, separação entre identidade semântica e política de correção, vínculo opcional a conceitos (`concept_id`), desafio de evidência pós-resposta (`evidence_challenge`), **Ficha Editorial Rastreável (`editorial_card`)**, metadados permanentes de verificação complementar (`diagnostic_meta`), idempotência transacional, elegibilidade centralizada e validação estrita de `ObjectId` e inteiros.

Os contratos formais residem em:
- Schema JSON canônico: [`shared/schemas/question-contracts.schema.json`](../shared/schemas/question-contracts.schema.json)
- Vetores de paridade Rust/Python: [`shared/examples/question-contracts/parity-vectors-v2-1.json`](../shared/examples/question-contracts/parity-vectors-v2-1.json)
- Validador e avaliador em Rust: [`backend-rust/src/services/question_contracts.rs`](../backend-rust/src/services/question_contracts.rs) e [`backend-rust/src/services/question_eligibility.rs`](../backend-rust/src/services/question_eligibility.rs)
- Validador e avaliador em Python: [`scripts/question_contracts.py`](../scripts/question_contracts.py)
- Persistência, CAS e validadores MongoDB: [`backend-rust/src/services/question_storage.rs`](../backend-rust/src/services/question_storage.rs) (`question-v2-validator-2.4.0`, `validator_revision = 7`)

---

## 1. Coleções e Separação de Autoridade

| Contrato / Coleção | Autoridade |
| :--- | :--- |
| `todasQuestoes` | Documento V2 completo: enunciado, alternativas/gabarito ou rubrica, hashes canônicos (`content_revision`, `semantic_revision`, `grading_revision`), origens, classificação, validade, revisões CAS e caches pedagógicos. |
| `question_review_events` | Trilha imutável de decisões humanas autenticadas no servidor (`#/$defs/review_event`), vinculadas a `principal_id`, `action`, `resource_id`, `resource_revision`, `content_revision` e `semantic_revision`. |
| `question_revision_events` | Eventos históricos de mutação do documento (extraídos de `schema_outbox` de forma idempotente via `$setOnInsert`). |
| `question_commands` | Reservas idempotentes por comando individual (`operation_id` + `payload_sha256`). |
| `question_import_commits` | Recibos duráveis de idempotência de lote (`idempotency_key` + `payload_digest` + `reviewed`). |
| `question_classification_suggestions` | Sugestões geradas por IA (`PENDING`, `ACCEPTED`, `EDITED_ACCEPTED`, `DEFERRED`, `REJECTED`, `SUPERSEDED`); nunca alteram seleção de treino sem aceitação humana autenticada. |
| `question_exam_compatibility` | Decisões explícitas (`DIRECT`, `ANALOGOUS`, `EXCLUDED`) vinculadas ao proprietário da grade, componente, revisão do edital e `question_semantic_revision`. |
| `reportedQuestoes` | Reportes de problemas abertos por estudantes, isolados por `user_id` denunciante até resolução administrativa (anonimizáveis sob LGPD Art. 18; reportes abertos sobre itens IA CTV `AUTO_APPROVED` suspendem imediatamente `diagnostic_meta.quality_status = "SUSPENDED"`). |
| `answer_evaluations` | Avaliações imutáveis de respostas discursivas referenciando tentativa, rubrica e política de revisão. |
| `conceptual_transfer_attempts` | Tentativas complementares de verificação adaptativa (`CTV`) vinculadas a `parent_attempt_id`, `source_question_id`, `complementary_question_id` e `verification_timing` (`PRE_FEEDBACK` / `POST_INSTRUCTION`), expurgadas sob LGPD Art. 18 sem remover a questão do acervo. |
| `ai_generation_audit` | Trilha operacional impessoal do motor CTV (`REUSED_FROM_DB`, `GENERATED_PERSISTED`, `GENERATION_REJECTED`, `GENERATION_FAILED`), sem dados pessoais de estudantes, alimentando os 8 KPIs operacionais do `/admin/`. |

---

### Metadado Diagnóstico Opcional V2.4 (`diagnostic_meta`) e Modo `COMPLEMENTARY_VERIFICATION`

Questões complementares geradas sob demanda pelo motor CTV são ativos V2 permanentes em `todasQuestoes` com o bloco opcional `#/$defs/diagnostic_meta` (fora de `content_revision` e `semantic_revision`):
- `source_question_id`, `subject_id`, `topic_id`, `concept_ids[]`, `diagnostic_purpose` (`CONCEPTUAL_TRANSFER`, `MISCONCEPTION_CHECK`, `RULE_BOUNDARY_CHECK`, `LONGITUDINAL_RETENTION`), `generation_origin` (`AI_ON_DEMAND`, `HUMAN_CURATED`, `AI_HUMAN_REVISED`), `model_identifier`, `prompt_id`, `prompt_version`, `generated_at`, `validation_status` (`VALIDATED`, `NEEDS_HUMAN_REVIEW`, `REJECTED`), `estimated_difficulty`, `content_hash`, `usage_count`, `quality_status` (`AUTO_APPROVED`, `PENDING_HUMAN_REVIEW`, `HUMAN_CONFIRMED`, `SUSPENDED`, `REJECTED`) e `quality_metrics` (`attempts_count`, `correct_count`, `avg_active_seconds`, `discrimination_index`, `open_reports_count`).
- **Isolamento de Elegibilidade:** No modo `STANDARD_OBJECTIVE` (simulados oficiais e baterias regulares), itens `AI_CTV` sem confirmação humana permanecem retidos por `HUMAN_REVIEW_REQUIRED`; já no modo `COMPLEMENTARY_VERIFICATION` (`evaluate_question_eligibility`), são elegíveis exclusivamente quando `quality_status` é `AUTO_APPROVED` ou `HUMAN_CONFIRMED` (itens `PENDING_HUMAN_REVIEW`, `SUSPENDED` ou `REJECTED` retornam `CTV_QUALITY_NOT_APPROVED`).

---

## 2. Tipos de Questão e Modo Preparatório `OPEN_ENDED`

| `question_type` | `answers` | `correct_answer_indices` | Correção / Seleção |
| :--- | :--- | :--- | :--- |
| `MULTIPLE_CHOICE` | Exatamente 5 alternativas não vazias e distintas | `[0..4]` (1 índice válido) | `EXACT_OPTION` · elegível para prática objetiva |
| `TRUE_FALSE` | Exatamente `["Certo", "Errado"]` | `[0]` ou `[1]` | `EXACT_OPTION` · elegível para prática objetiva |
| `OPEN_ENDED` | `[]` | `[]` | `RUBRIC` · **modo preparatório** (excluída da prática objetiva padrão) |

- **Invariante `OPEN_ENDED`:** Questões discursivas exigem `expected_responses` não vazio, rubrica com soma dos critérios igual a `max_score` e `confirmation_policy = "HUMAN_REVIEW"`. Permanecem bloqueadas por `evaluate_question_eligibility` no modo `STANDARD_OBJECTIVE` (`OPEN_ENDED_EXCLUDED_FROM_OBJECTIVE_PRACTICE`).

---

## 3. Identidades Canônicas: Conteúdo Completo, Núcleo Semântico e Correção (RFC 8785 JCS)

O Cogniva 2.1 preserva integralmente o `content_revision` V2.0 e introduz duas assinaturas JCS SHA-256 adicionais com paridade byte a byte entre Rust e Python:

| Campo | Versão do Algoritmo | Escopo Canônico (RFC 8785 JCS → UTF-8 → SHA-256 hex minúsculo) |
| :--- | :--- | :--- |
| `content_revision` | `question-v2-jcs-sha256` | `{ question_type, question, answers, correct_answer_indices, grading }` (inalterado em relação ao V2.0). |
| `semantic_revision` | `question-v2-semantic-jcs-sha256-v1` | `{ question_type, question, answers, correct_answer_indices, expected_responses, rubric_criteria }` — em objetivas, `expected_responses = []` e `rubric_criteria = []`; em `OPEN_ENDED`, `expected_responses` provém de `grading.expected_responses` e `rubric_criteria` projeta `{ criterion_id, description, essential }` (excluindo `max_points`, `max_score`, `pass_score`, `engine` e `policy_version`). |
| `grading_revision` | `question-v2-grading-jcs-sha256-v1` | `JCS(grading)` (ou `null` quando `grading == null`). |

### Vinculação com `validity` e Preservação de Caches

- **`validity.evaluation_mode = "SEMANTIC"`:** exige `validity.evaluated_semantic_revision == semantic_revision` para `CURRENT` e `OBSOLETE`, mantendo `validity.evaluated_content_revision` preenchido como evidência histórica.
- **`validity.evaluation_mode = "LEGACY_CONTENT"` (ou omitido em documentos V2.0):** preserva compatibilidade com registros pré-V2.1 avaliados por `evaluated_content_revision == content_revision`.
- **Edições Operacionais de Pontuação:** alterar apenas `max_score`, `pass_score`, `max_points` proporcional, `engine` ou `policy_version` atualiza `content_revision` e `grading_revision`, mas **não altera `semantic_revision`**, preservando `validity.status = CURRENT`, vínculos `question_exam_compatibility` e caches pedagógicos (`explanation`, `mnemonico`, `drSincero`, etc.).
- **Edições Substantivas:** alterar enunciado, alternativas, gabarito, `expected_responses` ou critérios substantivos (`criterion_id`, `description`, `essential`) altera `semantic_revision`, exigindo nova confirmação humana ou retornando `validity.status` para `UNDER_REVIEW` (`SEMANTIC_REVISION_MISMATCH` / `STALE_SEMANTIC_VALIDITY`).

---

## 4. Proveniência Confiável e Auditoria Humana (`ReviewEvent`)

Declarações enviadas pelo cliente (`actor.type = "HUMAN"`, `method = "MANUAL_REVIEW"`) **não constituem prova suficiente de autorização**.

1. **Derivação Exclusiva no Servidor:** toda confirmação normativa (`classification.state = CONFIRMED`, `origins[*].verification = CONFIRMED` em `OFFICIAL_EXAM`, `validity.status = CURRENT | OBSOLETE`, aceitação/rejeição de sugestões IA e decisões em `question_exam_compatibility`) exige sessão autenticada com papel administrativo (`403 REVIEW_PERMISSION_DENIED` caso contrário).
2. **Bloqueio de Spoofing:** o servidor rejeita payloads externos que tentem injetar `review_event_id` arbitrário ou `trust_level = "SERVER_VERIFIED"` sem respaldo persistido (`400 REVIEW_ACTOR_UNTRUSTED`).
3. **Persistência Auditável:** cada aprovação gera um documento em `question_review_events` (`review_event_id`, `principal_id`, `actor_user_id`, `action`, `resource_id`, `resource_revision`, `content_revision`, `semantic_revision`, `reviewed_at`, `decision = "APPROVED"`) e carimba `provenance.review_event_id` e `provenance.trust_level = "SERVER_VERIFIED"`.
4. **Registros Legados:** decisões históricas sem trilha autenticada verificável recebem `trust_level = "UNVERIFIED_LEGACY"` na migração — jamais recebem `principal_id` humano fictício.

---

## 5. Responsabilidades das Camadas de Validação

| Camada | Responsabilidade Estrita |
| :--- | :--- |
| **JSON Schema** ([`question-contracts.schema.json`](../shared/schemas/question-contracts.schema.json)) | Formato estrutural, tipos primitivos (`"type": "integer"` estrito), enums, tamanhos, padrões regex e obrigatoriedade de propriedades. |
| **Domínio Rust & Python** (`question_contracts.rs` / `question_contracts.py`) | Hashes JCS SHA-256 (`content_revision`, `semantic_revision`, `grading_revision`), invariantes cruzados entre campos, integridade contra o catálogo canônico (`knowledge-catalog.json`), fronteiras de `ObjectId` e inteiros estritos. |
| **MongoDB `$jsonSchema`** (`question-v2-validator-2.1.0`) | Barreira estrutural `strict` no banco de dados (`bsonType: "objectId"` em `_id`, `bsonType: ["int", "long"]` em inteiros, enums e formatos essenciais). |
| **Serviço / Autorização** (`question_storage.rs`, `question_import.rs`, `classification.rs`) | Autenticação e RBAC do revisor, emissão de `ReviewEvent`, transações `ClientSession`, CAS (`expected_revision`), reservas de comando e idempotência de lote. |

---

## 6. Política Centralizada de Elegibilidade para Prática (`evaluate_question_eligibility`)

Armazenar uma questão em `todasQuestoes` **nunca** a torna automaticamente elegível para estudo. Todos os consumidores de prática (`POST /api/v1/quiz/generate`, `POST /api/v1/questions/cache-state`, `GET /api/v1/learning/state`, `POST /api/v1/exams/:id/weighted-simulado` e `POST /api/v1/exams/trunk/common-batch`) delegam para a política centralizada, que exige simultaneamente na prática objetiva padrão (`STANDARD_OBJECTIVE`):

1. `status == "ACTIVE"` (`QUESTION_ARCHIVED`);
2. `question_type` em `{"MULTIPLE_CHOICE", "TRUE_FALSE"}` (`OPEN_ENDED_EXCLUDED_FROM_OBJECTIVE_PRACTICE`);
3. `classification.state == "CONFIRMED"` com exatamente 1 vínculo `PRIMARY` (`coverage == "DIRECT"`), disciplina/tópico ativos no catálogo e proveniência humana confiável (`CLASSIFICATION_NOT_CONFIRMED` / `HUMAN_REVIEW_REQUIRED`);
4. `validity.status == "CURRENT"` com decisão humana confiável (`VALIDITY_NOT_CONFIRMED` / `HUMAN_REVIEW_REQUIRED`);
5. `validity.scope == "CURRENT_KNOWLEDGE"` (questões `EXPLICIT_HISTORICAL_DATE` só entram quando o modo histórico é solicitado explicitamente — `VALIDITY_NOT_CONFIRMED`);
6. `validity.review_due_on == null` ou `review_due_on >= data_da_avaliacao` (`VALIDITY_REVIEW_EXPIRED`);
7. Versão avaliada coerente com `semantic_revision` atual no modo `SEMANTIC` ou `content_revision` no modo `LEGACY_CONTENT` (`SEMANTIC_REVISION_MISMATCH` / `CONTENT_REVISION_MISMATCH`);
8. Em origens `OFFICIAL_EXAM` confirmadas, proveniência humana confiável (`HUMAN_REVIEW_REQUIRED`);
9. **Matriz de Compatibilidade com Concurso (`question_exam_compatibility`):**
   - `EXCLUDED` confirmado → bloqueio imediato no concurso/componente (`EXAM_COMPATIBILITY_EXCLUDED`);
   - `DIRECT` confirmado ou tópico canônico direto sem exclusão → elegível (`coverage_mode = "DIRECT"`, `is_borrowed_analogous = false`);
   - `ANALOGOUS` confirmado → bloqueado nas cotas diretas de `SIMULADO`, permitido nos demais modos adaptativos com `coverage_mode = "ANALOGOUS"` e `is_borrowed_analogous = true` (`EXAM_COMPATIBILITY_MISSING` quando ausente/inaplicável).
   - Toda reprovação inclui o marcador agregado `QUESTION_NOT_ELIGIBLE`.

---

## 7. Fronteiras de `ObjectId` e Validação Estrita de Inteiros

### Representação de `ObjectId` por Fronteira

| Fronteira | Representação Aceita | Exemplos Rejeitados (`400 INVALID_OBJECT_ID`) |
| :--- | :--- | :--- |
| **MongoDB (`todasQuestoes._id`)** | `Bson::ObjectId` nativo | Strings hexadecimais soltas ou subdocumentos `$oid` gravados como documento. |
| **Contrato Canônico V2 (`#/$defs/object_id`)** | Extended JSON estrito `{"$oid": "<24-hex>"}` | `"65f0..."` puro, `{"$oid": "curto"}`, chaves extras no objeto `$oid`. |
| **Rotas HTTP / DTOs de API** | String hexadecimal de 24 caracteres | Objetos aninhados ou strings fora de `^[0-9a-fA-F]{24}$`. |

### Validação Estrita de Inteiros (`400 NON_INTEGER_NUMBER`)

Em todos os campos inteiros (`schema_version`, `revision`, `catalog_version`, `correct_answer_indices[*]`, `exam_year`, `rubric.revision`, `resource_revision`):
- **JSON Schema / Python / Rust:** rejeitam `1.0`, `"1"`, `true`/`false`, `NaN`, `Infinity` e inteiros fora da faixa segura de 64 bits com sinal.
- **BSON / MongoDB:** exigem `Bson::Int32` ou `Bson::Int64` (`bsonType: ["int", "long"]`), rejeitando explicitamente `Bson::Double(1.0)`.

---

## 8. Catálogo Estável de Códigos de Erro Estruturados

Respostas HTTP de erro seguem o envelope `{ "error": true, "code": "<CODIGO>", "field": "<campo|null>", "message": "<pt-BR>", "correlation_id": "<uuid>" }`:

| Código | HTTP | Significado |
| :--- | :--- | :--- |
| `REVIEW_ACTOR_UNTRUSTED` | `400` / `403` | Tentativa de forjar `review_event_id` / `trust_level = "SERVER_VERIFIED"` ou sessão sem ator confiável. |
| `REVIEW_PERMISSION_DENIED` | `403` | Conta autenticada sem papel administrativo para confirmar estado normativo. |
| `CONTENT_REVISION_MISMATCH` | `400` | `content_revision` diverge do hash JCS SHA-256 calculado sobre o documento. |
| `SEMANTIC_REVISION_MISMATCH` | `400` | `semantic_revision` diverge do payload semântico ou invalida `validity.evaluated_semantic_revision`. |
| `GRADING_REVISION_MISMATCH` | `400` | `grading_revision` diverge de `JCS(grading)`. |
| `QUESTION_ALREADY_EXISTS` | `409` | Inserção *create-only* para `_id` já existente. |
| `IDEMPOTENCY_KEY_CONFLICT` | `409` | Reuso de `operation_id` ou `idempotency_key` com payload divergente. |
| `POTENTIAL_DUPLICATE` | `409` / Aviso | Duplicidade exata (`EXACT_CONTENT`) ou semântica (`SEMANTIC_CORE`) detectada sem `acknowledge_duplicates`. |
| `QUESTION_NOT_ELIGIBLE` | Domínio | Questão reprovada por `evaluate_question_eligibility` (acompanhado dos motivos específicos como `OPEN_ENDED_EXCLUDED_FROM_OBJECTIVE_PRACTICE`, `CLASSIFICATION_NOT_CONFIRMED`, `VALIDITY_NOT_CONFIRMED`, `VALIDITY_REVIEW_EXPIRED`, `HUMAN_REVIEW_REQUIRED`, `EXAM_COMPATIBILITY_EXCLUDED` ou `EXAM_COMPATIBILITY_MISSING`). |
| `INVALID_OBJECT_ID` | `400` | Representação de `ObjectId` incompatível com a fronteira. |
| `NON_INTEGER_NUMBER` | `400` | Float (`1.0`), booleano, string ou `Bson::Double` enviado para campo inteiro. |

---

## 9. Runbook de Migração Segura V2.0 → V2.1 (`scripts/migrate_questions_v2_1.py`)

A rotina [`scripts/migrate_questions_v2_1.py`](../scripts/migrate_questions_v2_1.py) executa o enriquecimento idempotente e não destrutivo de bases V2.0 para V2.1:

1. **Modo `dry-run` (padrão — somente leitura):**
   ```bash
   python scripts/migrate_questions_v2_1.py --mongo-uri "$MONGODB_URI" --db cogniva --report-out migration-v2-1-dry-run.json
   ```
   Inspeciona `todasQuestoes`, `question_classification_suggestions` e `question_exam_compatibility`, calcula `semantic_revision` e `grading_revision`, reconcilia decisões humanas contra eventos auditáveis existentes (`SERVER_VERIFIED` quando comprovado; `UNVERIFIED_LEGACY` quando sem trilha autenticada, sem jamais fabricar identidades humanas) e grava relatório JSON completo sem alterar documentos.
2. **Aplicação Idempotente (`--apply`):**
   ```bash
   python scripts/migrate_questions_v2_1.py --mongo-uri "$MONGODB_URI" --db cogniva --apply --report-out migration-v2-1-applied.json
   ```
   Atualiza apenas campos derivados V2.1 ausentes (sem apagar campos ou eventos históricos). Reexecutar `--apply` converge com `0` mutações adicionais.
3. **Atualização do Validador MongoDB:**
   Ao iniciar o backend Rust (`question_storage::prepare`), a migração `question-v2-validator-2.3.0` (`validator_revision = 6`) verifica `count_documents({ "$nor": [target_validator] }) == 0` e promove `collMod` (`strict` / `error`) com recibo auditável em `cogniva_schema_versions`.

---

## 10. Verificação Automatizada

```bash
python scripts/knowledge_catalog.py --check
python scripts/question_contracts.py --examples
pytest scripts/tests -q
node --test web-admin/tests/*.test.js
cd backend-rust && cargo clippy --all-targets --locked -- -D warnings && cargo test --locked
```
