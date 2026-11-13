# Inserção de Questões: Individual, em Lote e Assistida por IA

O módulo de inserção revisada de questões está disponível tanto no painel web [`/admin/`](ADMIN-WEB.md) quanto no aplicativo Android (**Ajustes → Inserir questões**, visível para contas com `role = "admin"`), operando integralmente sobre o [contrato de questões V2](SCHEMA-QUESTOES-V2.md).

---

## 1. Fluxo Operacional

1. **Escolha do Modo:**
   - **Individual:** Formulário guiado para enunciado, alternativas/gabarito (ou rubrica em `OPEN_ENDED`), origem documental, classificação canônica (`Disciplina → Assunto/Subassunto`) e explicação do gabarito.
   - **Lote / IA:** Entrada direta em JSON ou JSONL (até **50 questões / 512 KiB**) ou texto bruto para estruturação/geração assistida pela IA configurada em `XAI_MODEL`.
2. **Aviso de Modo Preparatório (`OPEN_ENDED`):**
   - Ao selecionar questão **Discursiva**, tanto o `/admin/` quanto o Android exibem aviso destacado informando que questões abertas são validadas e preservadas no banco V2 com rubrica estruturada (critérios somando `100` pontos e revisão humana), mas não entram nas filas de prática objetiva nem ativam correção automática.
3. **Prévia Autoritativa (`POST /api/v1/admin/questions/import/preview`):**
   - O servidor valida cada item por posição, calcula o `content_revision` JCS SHA-256 e devolve os comandos V2 prontos para inspeção sem gravar no acervo.
4. **Confirmação Idempotente (`POST /api/v1/admin/questions/import/commit`):**
   - Após marcar a confirmação de revisão, o cliente envia os comandos congelados. A escrita é *create-only* com `write_concern: majority` + `journal` e recibo individual por questão.

---

## 2. Resiliência contra Bloqueio de Rascunhos

- **Isolamento por Conta e Recuperação:** No Android e no `/admin/`, o rascunho é salvo localmente por conta e pode ser exportado (`cogniva-question-import-draft/1`) a qualquer momento.
- **Retentativa Segura em Quedas de Rede:** Se a conexão cair durante o `commit`, os comandos permanecem congelados para que **Conferir / reenviar os mesmos comandos** recupere `already_applied` sem duplicar questões.
- **Destravamento Automático em Erros HTTP `4xx`:** Caso o servidor rejeite definitivamente a requisição (`400`, `401`, `403`, `409`, `422`), `submitted` volta imediatamente para `false`, permitindo corrigir o rascunho ou iniciar um novo cadastro sem travar a interface.

---

## 3. Formato Simplificado de Lote (JSON / JSONL)

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

Documentos V2 completos (com `schema_version: 2`, `revision: 1` e `_id` exclusivo) também são aceitos diretamente no lote.

---

## 4. Assistência Opcional por IA (`POST /api/v1/admin/questions/import/assist`)

- Envia até `40.000` bytes de material ao modelo definido em `XAI_MODEL` apenas mediante clique explícito em **Preparar com IA** (`STRUCTURE` para estruturar texto existente ou `GENERATE` para criar rascunhos autorais, até 10 itens por chamada).
- Consome 1 unidade do orçamento diário de IA (`XAI_DAILY_USER_LIMIT` e `XAI_DAILY_GLOBAL_LIMIT`), mas **estorna automaticamente a cota** (`release_tutor`) se a chamada ao provedor falhar ou retornar JSON inválido.
- Todo item gerado por IA preserva proveniência `AI` e exige conferência humana antes da inserção.
