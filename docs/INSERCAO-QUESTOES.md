# Inserção de Questões: Individual, em Lote e Assistida por IA (V2.1)

O módulo de inserção revisada de questões está disponível no painel web [`/admin/`](ADMIN-WEB.md), no aplicativo Android (**Ajustes → Inserir questões**, restrito a contas com `role = "admin"`) e via CLI administrativa ([`scripts/cogniva_import.py`](../scripts/cogniva_import.py)), operando integralmente sobre o [contrato endurecido de questões V2.1](SCHEMA-QUESTOES-V2.md).

> **Vai criar e importar pacotes completos de uma disciplina (com assuntos, subassuntos, validade `CURRENT` e rastreamento pronto)?** Consulte o passo a passo completo em **[`PACOTES-QUESTOES.md`](PACOTES-QUESTOES.md)**.

---

## 1. Fluxo Operacional (`assist → preview → commit`)

1. **Escolha do Modo:**
   - **Individual:** Formulário guiado para enunciado, alternativas/gabarito (ou rubrica em `OPEN_ENDED`), origem documental, classificação canônica (`Disciplina → Assunto/Subassunto`) e explicação do gabarito.
   - **Lote / IA:** Entrada direta em JSON ou JSONL (até **50 questões / 512 KiB**) ou texto bruto para estruturação/geração assistida pela IA configurada em `XAI_MODEL`.
2. **Modo Preparatório (`OPEN_ENDED`):**
   - Ao selecionar questão **Discursiva**, o `/admin/` e o Android exibem aviso explícito informando que questões abertas são validadas e preservadas no banco V2 com rubrica estruturada (`expected_responses`, critérios somando `max_score` e `confirmation_policy = "HUMAN_REVIEW"`), mas permanecem fora das filas de prática objetiva padrão (`OPEN_ENDED_EXCLUDED_FROM_OBJECTIVE_PRACTICE`).
3. **Prévia Autoritativa (`POST /api/v1/admin/questions/import/preview`):**
   - Valida cada item por posição (incluindo rejeição estrita de `1.0` em campos inteiros — `NON_INTEGER_NUMBER`, validação de `ObjectId` — `INVALID_OBJECT_ID` e bloqueio de `review_event_id` ou `trust_level` forjados — `REVIEW_ACTOR_UNTRUSTED`).
   - Calcula `content_revision`, `semantic_revision` e `grading_revision` (RFC 8785 JCS SHA-256).
   - Detecta possíveis duplicidades no acervo e dentro do próprio lote, retornando avisos estruturados `POTENTIAL_DUPLICATE` (`EXACT_CONTENT` para `content_revision` idêntico; `SEMANTIC_CORE` para `semantic_revision` equivalente com correção distinta), além de `idempotency_key` e `payload_digest` determinísticos para o lote.
4. **Confirmação Idempotente e Transacional (`POST /api/v1/admin/questions/import/commit`):**
   - Separa explicitamente `reviewed: true` (confirmação operacional de que o operador inspecionou a prévia do lote) das confirmações normativas definitivas (`classification.state = CONFIRMED`, `validity.status = CURRENT | OBSOLETE`, `origins[*].verification = CONFIRMED` em `OFFICIAL_EXAM`), que exigem sessão administrativa autenticada no servidor e geram registros persistentes em `question_review_events` (`trust_level = "SERVER_VERIFIED"`).

---

## 2. Idempotência de Lote, Atomicidade e Prevenção de Duplicações

### Idempotência Real de Lote (`question_import_commits`)
- O `commit` recebe `idempotency_key`, `payload_digest` (opcional, verificado contra o digest JCS real), `reviewed: true`, `acknowledge_duplicates` (quando aplicável) e `commands`.
- **Reenvio idêntico (`idempotency_key` + mesmo `payload_digest`):** retorna `200 OK` com `idempotent_replay: true`, os mesmos `_id`s criados originalmente e `receipt.status = "already_applied"`, sem duplicar documentos nem eventos de auditoria.
- **Reuso indevido da chave (`idempotency_key` com comandos diferentes):** retorna `409 Conflict` com `code = "IDEMPOTENCY_KEY_CONFLICT"`.
- **Falha pré-commit:** se qualquer item falhar na pré-validação (`dry_run` integral executado antes de iniciar escrita), nenhum item do lote é gravado e a chave pode ser reutilizada após correção ou descartada com segurança.

### Atomicidade Transacional e Outbox Idempotente
- Em topologias MongoDB com suporte a transações (*replica set* / `mongos`), todo o lote de `commit` (incluindo `todasQuestoes`, `question_commands`, `question_review_events` e `question_revision_events`) executa dentro de uma única transação `ClientSession` (`commit_transaction` / `abort_transaction`).
- Em instâncias `mongod` *standalone* de desenvolvimento/CI, a pré-validação `dry_run` completa impede falhas estruturais parciais no meio do lote, e cada gravação individual embute `schema_outbox` atomicamente no próprio documento de `todasQuestoes`, drenado de forma idempotente (`$setOnInsert`) para `question_revision_events` e `question_review_events`.
- Inserções concorrentes sobre o mesmo `_id` respeitam semântica *create-only* estrita: apenas uma vence e a outra recebe `409 QUESTION_ALREADY_EXISTS`.

### Tratamento de Duplicidades (`POTENTIAL_DUPLICATE`)
- `semantic_revision` **não** possui índice `unique` global, preservando casos legítimos de reuso editorial ou variantes históricas.
- Contudo, se um item do lote coincidir em `content_revision` com questão já persistida no acervo e o lote for submetido sem `acknowledge_duplicates: true`, o `commit` bloqueia a gravação acidental com `409 POTENTIAL_DUPLICATE`. No `/admin/`, os itens sinalizados exibem o selo **Possível duplicidade** na prévia para ciência explícita do revisor antes da confirmação.

---

## 3. Resiliência contra Bloqueio de Rascunhos

- **Isolamento por Conta e Recuperação:** No Android e no `/admin/`, o rascunho é salvo localmente por conta e pode ser exportado a qualquer momento.
- **Retentativa Segura em Quedas de Rede:** Se a conexão cair durante o `commit`, `idempotency_key`, `payload_digest` e `commands` permanecem congelados para que **Conferir / reenviar os mesmos comandos** recupere `already_applied` com segurança.
- **Destravamento Automático em Erros HTTP `4xx`:** Caso o servidor rejeite a requisição (`400`, `401`, `403`, `409`, `422`), `submitted` volta imediatamente para `false`, permitindo corrigir o rascunho ou iniciar novo cadastro.

---

## 4. Formato Simplificado de Lote (JSON / JSONL)

```json
[
  {
    "question_type": "MULTIPLE_CHOICE",
    "question": "Qual princípio constitucional rege expressamente a Administração Pública no caput do art. 37 da CF/88?",
    "answers": ["Pessoalidade", "Eficiência", "Sigilo absoluto", "Informalidade", "Autotutela irrestrita"],
    "correct_answer_indices": [1],
    "grading": null,
    "origin_kind": "AUTHOR_CREATED",
    "subject_id": "DIR_ADM",
    "topic_id": "DIR_ADM_01",
    "confirm_classification": true,
    "explanation": "O caput do art. 37 prevê legalidade, impessoalidade, moralidade, publicidade e eficiência (LIMPE)."
  },
  {
    "question_type": "TRUE_FALSE",
    "question": "No regime da Lei de Responsabilidade Fiscal, a transparência é assegurada também pelo incentivo à participação popular.",
    "answers": ["Certo", "Errado"],
    "correct_answer_indices": [0],
    "grading": null
  }
]
```

Documentos V2 completos (`schema_version: 2`, `revision: 1` e `_id` exclusivo no formato `{"$oid": "<24-hex>"}`) também são aceitos diretamente no lote, desde que não contenham campos de auditoria exclusivos do servidor (`review_event_id` ou `trust_level = "SERVER_VERIFIED"`).

---

## 5. Assistência Opcional por IA (`POST /api/v1/admin/questions/import/assist`)

- Envia até `40.000` bytes de material ao modelo definido em `XAI_MODEL` apenas mediante clique explícito em **Preparar com IA** (`STRUCTURE` ou `GENERATE`, até 10 itens por chamada).
- Consome 1 unidade do orçamento diário de IA (`XAI_DAILY_USER_LIMIT` e `XAI_DAILY_GLOBAL_LIMIT`), estornando automaticamente a cota (`release_tutor`) se a chamada ao provedor falhar ou retornar JSON inválido.
- Todo item gerado por IA preserva proveniência `AI` (`classification.state = UNCLASSIFIED`, `validity.status = UNKNOWN`) e jamais substitui revisão humana autenticada.

---

## 6. Pacotes Prontos para Estudo e Rastreamento (`scripts/cogniva_import.py`)

No formato simplificado de lote (Seção 4), o endpoint `POST /api/v1/admin/questions/import/preview` aceita estritamente as 18 chaves do rascunho (`question_type`, `question`, `answers`, `correct_answer_indices`, `grading`, `subject_id`, `topic_id`, `confirm_classification`, `origin_kind`, `source_exam_code`, `institution`, `organizer`, `exam_year`, `question_number`, `source_reference`, `confirm_origin`, `explanation`, `level`) e inicializa `validity.status = "UNKNOWN"`.

Para importar pacotes extensos (dezenas ou centenas de questões em uma ou várias subcategorias) já **100% elegíveis para estudo e rastreamento de domínio**:
1. Utilize [`scripts/cogniva_import.py`](../scripts/cogniva_import.py), que valida os pacotes localmente (`--check-local`), registra `TOPIC`s e `SUBTOPIC`s faltantes no catálogo em execução, sincroniza os `SUBTOPIC`s folha na grade do concurso (`exam_templates` + `adopt-template`), fatia automaticamente em blocos `<= 50` itens / `<= 480 KiB`, promove `classification.state = "CONFIRMED"` + `validity.status = "CURRENT"`, confirma idempotência (`already_applied`), grava compatibilidade `DIRECT` e verifica sorteio real em `POST /api/v1/quiz/generate`.
2. Siga a especificação completa de modelagem de edital (`Disciplina → Assunto → Subassunto`) e o checklist editorial em **[`PACOTES-QUESTOES.md`](PACOTES-QUESTOES.md)**.
