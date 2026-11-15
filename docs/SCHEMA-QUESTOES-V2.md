# Contratos e Schema de Questões V2

No **Cogniva `2.0.1`**, 100% do acervo em `cogniva.todasQuestoes` segue estritamente o contrato `schema_version = 2`, formalizado em [`shared/schemas/question-contracts.schema.json`](../shared/schemas/question-contracts.schema.json), exemplificado em [`shared/examples/question-contracts/`](../shared/examples/question-contracts/) e validado por [`scripts/question_contracts.py`](../scripts/question_contracts.py) e pelo backend Rust.

---

## 1. Coleções e Separação de Autoridade

| Contrato / Coleção | Autoridade |
| :--- | :--- |
| `todasQuestoes` | Documento V2 completo: enunciado, alternativas/gabarito ou rubrica, origens, classificação confirmada, validade, revisões CAS e caches pedagógicos. |
| `question_classification_suggestions` | Sugestões geradas por IA (`PENDING`, `ACCEPTED`, `REJECTED`, `STALE`, `SUPERSEDED`); nunca alteram seleção de treino sem aceitação humana explícita. |
| `question_exam_compatibility` | Decisões explícitas (`DIRECT`, `ANALOGOUS`, `EXCLUDED`) vinculadas ao proprietário da grade (`POST /api/v1/exams/:id/compatibility`), componente e revisão. |
| `reportedQuestoes` | Reportes de problemas abertos por estudantes, isolados por `user_id` denunciante até resolução administrativa na aba **Reportes** (anonimizáveis sob LGPD Art. 18). |
| `answer_evaluations` | Avaliações imutáveis de respostas discursivas referenciando tentativa, rubrica e política de revisão. |

---

## 2. Tipos de Questão e Modo Preparatório `OPEN_ENDED`

| `question_type` | `answers` | `correct_answer_indices` | Correção / Seleção |
| :--- | :--- | :--- | :--- |
| `MULTIPLE_CHOICE` | 4 ou 5 alternativas não vazias e distintas | `[0..3]` ou `[0..4]` (1 índice válido) | `EXACT_OPTION` · elegível para prática objetiva |
| `TRUE_FALSE` | Exatamente `["Certo", "Errado"]` | `[0]` ou `[1]` | `EXACT_OPTION` · elegível para prática objetiva |
| `OPEN_ENDED` | `[]` | `[]` | `RUBRIC` (`max_score = 100`) · **modo preparatório** |

- **Invariante `OPEN_ENDED`:** Questões discursivas possuem validação completa de rubrica (soma dos critérios igual a `max_score`, critérios essenciais identificados e `confirmation_policy = "HUMAN_REVIEW"`) e `content_revision` JCS SHA-256 consistente, mas permanecem **fora de todas as filas de prática objetiva** até a ativação futura de fluxo discursivo dedicado. Tanto o `/admin/` quanto o módulo de inserção Android exibem aviso explícito ao operador.

---

## 3. Hash Canônico Único (`content_revision` — RFC 8785 JCS)

O Cogniva 2.0 aposenta definitivamente hashes binários legados e adota um único cálculo determinístico em todo o ecossistema ([`shared/domain-contracts.json`](../shared/domain-contracts.json), `contract_version: 3`):

1. Constrói-se o objeto JSON contendo exatamente os cinco campos de conteúdo:
   - `question_type`
   - `question`
   - `answers`
   - `correct_answer_indices`
   - `grading`
2. Serializa-se esse objeto segundo a **RFC 8785 (JSON Canonicalization Scheme — JCS)** em UTF-8, sem normalização Unicode implícita das strings.
3. Calcula-se o digest `SHA-256` hexadecimal minúsculo (64 caracteres).

Qualquer edição no enunciado, nas alternativas, no gabarito ou na rubrica altera `content_revision`, invalidando decisões anteriores de validade definitiva e exigindo reconfirmação explícita. Alterar `explanation`, `level`, `exam_year` ou caches de tutores IA incrementa `revision` (CAS) sem alterar `content_revision`.

---

## 4. Classificação, Validade e Edição V2

- **Classificação (`classification`):**
  - Estados: `UNCLASSIFIED` (sem vínculos), `PARTIAL` ou `CONFIRMED` (exatamente um vínculo `PRIMARY` com `coverage = "DIRECT"` e vínculos `SECONDARY` opcionais).
  - Todo `subject_id` e `topic_id` é validado contra o snapshot correspondente do catálogo canônico.
- **Validade (`validity`):**
  - Status explícito editável no `/admin/`: `CURRENT` (vigente e revisada), `UNDER_REVIEW` (em revisão — suspensa das filas correntes), `OBSOLETE` (superada — preservada apenas no acervo histórico) e `UNKNOWN` (sem revisão conclusiva).
  - Se houver mudança no conteúdo (`content_revision`) sem nova decisão explícita do revisor, o status retorna automaticamente para `UNDER_REVIEW`.
- **Campos Pedagógicos, Evidências e Limites:**
  - Edições V2 persistem integralmente `explanation` (comentário do gabarito), `level` (dificuldade) e `exam_year` (`1900..=2200`, sincronizado com `origins[0].exam_year`), limitando `concursos` a no máximo 50 entradas.
  - Todas as URIs documentais (`origins[*].source_reference.uri` e `evidence[*].uri`) exigem esquema `http://`, `https://`, URN interna de auditoria `urn:cogniva:*` ou referência arquivística de caderno PDF (`file:///*.pdf`, sem travessia `..`), bloqueando esquemas executáveis ou caminhos arbitrários (`javascript:`, `data:`, `file:///etc/passwd`).

---

## 5. Migração Versionada de Validadores MongoDB (`cogniva_schema_versions`)

- Cada coleção regida pelos contratos V2 (`todasQuestoes`, `question_classification_suggestions`, `question_exam_compatibility`, `answer_evaluations`) possui recibo durável em `cogniva_schema_versions` (`question-v2:<coleção>`) com o `validator` expandido e seu `source_sha256`.
- Qualquer alteração em [`shared/schemas/question-contracts.schema.json`](../shared/schemas/question-contracts.schema.json) que modifique o `source_sha256` exige etapa explícita na tabela `VALIDATOR_MIGRATIONS` de [`backend-rust/src/services/question_storage.rs`](../backend-rust/src/services/question_storage.rs) (ex.: `question-v2-validator-2.0.1`, `validator_revision = 2`).
- Antes de executar `collMod` (`validationLevel: "strict"`, `validationAction: "error"`), `question_storage::prepare` verifica `count_documents({ "$nor": [target_validator] }) == 0`, preservando 100% dos documentos existentes sem reescrita ou descarte e suportando recuperação idempotente em caso de falha intermediária.

---

## 6. Verificação Local

```bash
python3 scripts/knowledge_catalog.py --check
python3 scripts/question_contracts.py --examples
pytest scripts/tests -v
```
