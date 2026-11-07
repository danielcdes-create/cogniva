# Inserção de questões: individual, lote e IA

Módulo disponível no Android **1.4.13/build 20**, em **Ajustes → Inserir questões · individual, lote ou IA**, e no [painel web](https://198.199.65.103/admin/), usando o contrato v2 existente. [Publicação e aceite](RELEASE-1.4.13.md).

## Fluxo

1. Escolha **Individual** para preencher enunciado, alternativas e gabarito; ou **Lote / IA** para colar/importar JSON, JSONL ou texto.
2. No cadastro individual, indique se o conteúdo é uma transcrição/importação ou autoria própria. Classificação é opcional: selecione disciplina e tópico no catálogo e confirme o vínculo explicitamente.
3. Para discursivas, informe resposta de referência e critérios da rubrica. Os critérios devem somar 100 pontos; o corte é 60 e a confirmação é humana. Para outras políticas, use um documento v2 completo.
4. Opcionalmente, peça à IA para **estruturar texto** existente ou **gerar autorais**. O material é enviado à xAI somente pelo botão “Preparar com IA”. Revise o JSON devolvido antes de prosseguir.
5. Toque em **Validar e revisar questões**. A revisão mostra enunciados, alternativas, gabaritos/rubrica, classificação, origem e validade/contexto temporal. O contrato completo também pode ser inspecionado.
6. Corrija todos os erros. Marque a confirmação de revisão e confirme a inserção. A interface informa o resultado de cada questão.

Os formulários e o lote ficam salvos nos metadados do banco Room da conta atual, sem migração destrutiva. É possível preparar conteúdo offline; validação autoritativa, IA e inserção exigem conexão. O módulo não acrescenta envios automáticos ao worker de tentativas.

**Exportar rascunho** gera um envelope `cogniva-question-import-draft/1` com conta, texto, comandos congelados e recibos. Para restaurá-lo, abra **Lote / IA → Abrir JSON / JSONL / TXT** na mesma conta, antes de iniciar um envio. O envelope exportado é diferente do formato de questões abaixo e não deve ser enviado diretamente à API de prévia. Arquivos de recuperação podem ter até 2 MiB; o material do lote continua limitado a 512 KiB.

## Formato simplificado

Aceita objeto individual, array JSON ou JSONL (um objeto por linha). Até **50 questões / 512 KiB** por lote de entrada. Não há deduplicação por enunciado: dois itens iguais têm identidades distintas.

```json
[
  {
    "question_type": "MULTIPLE_CHOICE",
    "question": "Exemplo sintético: quanto é 2 + 2?",
    "answers": ["1", "2", "3", "4", "5"],
    "correct_answer_indices": [3],
    "grading": null,
    "origin_kind": "AUTHOR_CREATED",
    "subject_id": "MAT",
    "topic_id": "MAT_01_001",
    "confirm_classification": true
  },
  {
    "question_type": "TRUE_FALSE",
    "question": "Exemplo sintético: 2 + 2 = 4.",
    "answers": ["Certo", "Errado"],
    "correct_answer_indices": [0],
    "grading": null
  }
]
```

Índices começam em zero. Não se escolhe A por omissão. Múltipla escolha exige cinco alternativas distintas; certo/errado exige exatamente `Certo`, `Errado`. O gabarito textual é projetado do índice informado e o hash é calculado pelo servidor.

`origin_kind` é opcional: `LEGACY_IMPORT` por omissão, com origem pendente; `AUTHOR_CREATED` é uma declaração explícita de autoria do responsável. Não há atribuição automática a banca/prova. `subject_id`, `topic_id` e `confirm_classification` são opcionais; sem confirmação, o conteúdo permanece `UNCLASSIFIED`. IDs desconhecidos ou de disciplinas incompatíveis são rejeitados.

Discursivas usam `answers: []`, `correct_answer_indices: []` e `grading` completo:

```json
{
  "question_type": "OPEN_ENDED",
  "question": "Explique um mecanismo de coesão textual e dê um exemplo.",
  "answers": [],
  "correct_answer_indices": [],
  "grading": {
    "strategy": "RUBRIC",
    "max_score": 100,
    "pass_score": 60,
    "expected_responses": ["A resposta deve explicar relações entre partes do texto e exemplificar um mecanismo pertinente."],
    "rubric": {
      "revision": 1,
      "criteria": [
        {"criterion_id": "CONCEPT", "description": "Explicar corretamente um mecanismo de coesão.", "max_points": 50, "essential": true},
        {"criterion_id": "EXAMPLE", "description": "Apresentar exemplo coerente com o mecanismo explicado.", "max_points": 50, "essential": false}
      ]
    },
    "engine": null,
    "confirmation_policy": "HUMAN_REVIEW",
    "policy_version": "open-rubric-human-review/1"
  }
}
```

Também são aceitos documentos v2 completos, novos, com `revision=1`, `_id` exclusivo e hash correspondente. Essa opção cobre origem oficial com evidência, múltiplos vínculos, validade revisada e políticas de correção já previstas no schema. Campos adicionais legítimos da raiz são preservados; `schema_outbox` é reservado ao servidor. Importar uma questão aberta não ativa motores semânticos/IA nem muda as capacidades de treino dos clientes existentes.

## Acesso e rotas

As quatro rotas são privadas e administrativas: JWT válido e conta em `ADMIN_USER_IDS`, conforme o middleware já existente. O cadastro não amplia permissões de contas comuns; 401/403 preservam o rascunho local.

| Método / rota sob `/api/v1/admin/questions/import` | Corpo / resultado |
| --- | --- |
| `GET /capabilities` | Limite do lote e disponibilidade de xAI, sem credenciais |
| `POST /preview` | `{operation_id, items, assistance_id?}` → validação por posição e comandos v2; nenhuma questão é inserida |
| `POST /commit` | `{reviewed:true, commands:[...]}` → resultado e recibo individual; gravação parcial explícita |
| `POST /assist` | `{operation_id, mode:"STRUCTURE" ou "GENERATE", text}` → rascunhos, `assistance_id` e exigência de revisão |

`operation_id` deve ser UUID. A prévia gera IDs de questões e comandos a partir de conta, operação e posição, sem usar o texto como identidade. Documentos completos mantêm seu `_id`. IDs já presentes no acervo ou repetidos no lote bloqueiam a prévia. Novas prévias podem ter instantes de proveniência diferentes: depois de iniciar o envio, reutilize **os comandos congelados**, sem regenerar uma prévia.

## Gravação, falhas e preservação

O envio usa a validação v2, hash JCS, majority/journal, comando idempotente e outbox de auditoria existentes. A inserção é **create-only**: nunca migra nem sobrescreve um registro existente, inclusive legado sem revisão. Recebimentos já aplicados são recuperados antes de conferir colisão de ID. Conteúdo diferente com o mesmo identificador de operação gera conflito.

Cada item é uma operação independente: o lote inteiro não é uma transação. A interface confere posição, ID da questão, ID da operação, revisão e status de cada recibo antes de considerar o lote completo. Se um item falhar, os outros recibos continuam preservados.

Antes do acesso à rede, o Android grava os comandos exatos e o sinal de envio iniciado. Depois de timeout, fechamento do app ou resposta parcial, o formulário fica bloqueado para edição e permite **Conferir / reenviar os mesmos comandos**. Itens já gravados retornam `already_applied`. Um novo cadastro só substitui o rascunho depois da conclusão; pendências não são apagadas para contornar falhas. Se uma falha permanente exigir intervenção, exporte o rascunho e inspecione `question_commands`, `question_revision_events` e a questão pelo ID antes de qualquer decisão operacional.

Os validadores e índices devem ter sido preparados pelo processo normal `--prepare-question-storage`. Não se modifica o schema compartilhado, não se migra o acervo em massa e não se altera o histórico de aprendizagem.

## IA

Somente **xAI / Grok 4.3**, com credencial no servidor. Limite de material: **40.000 bytes**, até **10 rascunhos por solicitação**, timeout do provedor de 40 segundos. A chamada reutiliza o orçamento diário de IA da conta (120/dia). Nesta operação, uma reserva feita antes do provedor conta conservadoramente mesmo se a resposta falhar ou o resultado for incerto; não há repetição paga automática.

`question_import_ai_requests` mantém a reserva por conta/operação e a resposta concluída com write concern majority/journal. Repetir uma operação concluída recupera a mesma resposta sem outra chamada; conteúdo divergente gera conflito. Reserva inconclusiva/falha exige nova operação explícita, em vez de reexecutar uma cobrança incerta. A conta também é conferida quando `assistance_id` é usado na prévia.

O prompt separa instruções e material não confiável. Respostas incompletas ou fora do contrato não publicam questões. Em transcrição, gabaritos/rubricas ausentes continuam pendentes e são rejeitados na validação; a IA não deve inventá-los. A proveniência é AI, com modelo e versão de prompt. `GENERATE` usa origem `AI_GENERATED`; `STRUCTURE` usa importação pendente. Nenhuma das opções confirma classificação ou validade. A validade simplificada permanece `UNKNOWN`.

## Verificação local

```powershell
python -m pytest scripts/tests -q
cd backend-rust
cargo test --locked
$env:COGNIVA_TEST_MONGODB_URI='mongodb://127.0.0.1:27048/'
cargo test --locked question_import_review_receipts_and_ai_are_isolated -- --ignored --test-threads=1
cd ../android-app
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug --console=plain
```

O teste integrado exige Mongo dedicado em loopback na porta 27048, cria e remove apenas banco sintético `cogniva_test_*` e usa provedor HTTP falso. Cobre autorização, prévia sem escrita, gabarito ausente, identidades distintas para textos iguais, confirmação, replay, conflito, preservação de legado/cache/histórico, geração única e isolamento da assistência entre contas. Não chama xAI real nem toca produção.

Registro da primeira validação local em 07/11/2026: 47 testes Python e 14 subtestes aprovados; 49 testes Rust aprovados na execução padrão (11 integrações opt-in não executadas nessa chamada). A execução específica `question_import -- --include-ignored --test-threads=1` aprovou os quatro testes unitários do módulo e sua integração real com Mongo isolado. Android: 69 testes JVM aprovados, incluindo seis do módulo, e lint sem erros. A validação não inclui uma chamada paga à xAI, instalação em aparelho físico ou publicação de APK/backend.

A validação conciliada final aprovou 69 testes Rust, 73 JVM, lint e compilação instrumentada. A publicação 1.4.13 aprovou os gates web, Rust, contratos, JVM/golden e emulador API 35; o APK assinado foi baixado integralmente e conferido com a versão 20 e o hash do servidor. Questões/concursos permaneceram idênticos no backup antes/depois; o aceite não chamou a IA nem inseriu material real. [Evidências](RELEASE-1.4.13.md).
