# Contratos de questões, classificação, validade e correção

Etapa 2 formalizada em `shared/schemas/question-contracts.schema.json`, com exemplos em `shared/examples/question-contracts/` e regras executáveis em `scripts/question_contracts.py`. Estes contratos ainda não estão instalados no Mongo nem habilitados na API/Android 1.4.4. Não houve migração do acervo nem chamada paga de IA.

O arquivo usa JSON Schema draft 2020-12 e Extended JSON para `_id: {"$oid":"..."}`. Ele não pode ser aplicado diretamente como `collMod`: a etapa 3 precisa adaptar tipos BSON e o subconjunto de validação do Mongo, além de implementar no Rust as regras entre documentos e a autenticação. [Validação no MongoDB](https://www.mongodb.com/docs/manual/core/schema-validation/), [condicionais em JSON Schema](https://json-schema.org/understanding-json-schema/reference/conditionals).

## Separação de responsabilidades

| Contrato / coleção planejada | Autoridade |
| --- | --- |
| `question` / `todasQuestoes` | Enunciado, alternativas, gabarito/rubrica, classificação confirmada e validade |
| `suggestion` / `question_classification_suggestions` | Propostas pendentes e sua resolução, sem participação automática na seleção |
| `compatibility` / `question_exam_compatibility` | Decisão explícita para questão/componente/revisão de edital de um proprietário |
| `evaluation` / `answer_evaluations` | Resultado de correção de uma tentativa, com referência à questão, rubrica e motor utilizados |

`origins` registra de onde a questão veio, não onde ela pode ser utilizada. `classification` registra o conteúdo cobrado, não o concurso de origem. A compatibilidade resulta da interseção com o edital e, quando registrada explicitamente, pertence ao proprietário e à revisão do concurso. Etapa, grupo de conhecimentos, peso e quantidade continuam no componente do edital.

Cada ocorrência de disciplina no edital precisa de `component_id` próprio. A mesma disciplina canônica poderá aparecer em várias etapas. O atual bloqueio de disciplina canônica repetida deverá ser adaptado na etapa 3; esta definição não afirma que essa mudança já foi publicada.

## Tipos e gabarito

| `question_type` | Alternativas | Gabarito | Correção |
| --- | --- | --- | --- |
| `MULTIPLE_CHOICE` | Exatamente cinco textos não vazios e sem duplicação após trim | Um índice entre 0 e 4 | `EXACT_OPTION` |
| `TRUE_FALSE` | Exatamente `["Certo", "Errado"]` | Um índice: 0 ou 1 | `EXACT_OPTION` |
| `OPEN_ENDED` | Array vazio | Sem índice nem alternativa fictícia; referências e rubrica em `grading` | `RUBRIC`, `SEMANTIC` ou `AI` |

`correct_answer_indices` é a autoridade objetiva; `correct_answers` deve ser sua projeção textual exata. Não há fallback para alternativa A. O contrato v2 exige um gabarito utilizável para documentos objetivos validados. Registros legados com gabarito ambíguo permanecem preservados como legado/em revisão até a conferência; não recebem conteúdo inventado para passar na validação.

O backup validado de 02/11/2026, `20261102T155257Z`, tinha 618 questões com cinco alternativas cada. Isso é evidência daquele inventário, não uma afirmação de validade pedagógica dos gabaritos.

## Classificação por conteúdo

`classification.state` aceita `UNCLASSIFIED`, `PARTIAL` e `CONFIRMED`. A primeira tem lista vazia; as demais têm vínculos confirmados individualmente. `CONFIRMED` exige exatamente um vínculo `PRIMARY`; `PARTIAL` permite no máximo um. O primário sempre tem cobertura `DIRECT`. Vínculos `SECONDARY` representam conteúdo complementar e podem ser diretos ou contextuais.

Cada vínculo contém identidade própria, disciplina, tópico, papel, cobertura e proveniência. Referências precisam existir na versão do catálogo e ter a disciplina correspondente. Um tópico não pode aparecer repetido com papéis contraditórios. Vínculos secundários não transferem domínio integral nem bastam para estabelecer compatibilidade direta com outro edital.

Os campos canônicos singulares atuais, quando presentes, devem refletir o vínculo principal. Eles são projeções de compatibilidade temporária, não uma segunda fonte editável de classificação. Aliases e semelhança textual não confirmam vínculos.

## Sugestões, confirmação e proveniência

Sugestões têm `PENDING`, `ACCEPTED`, `REJECTED`, `STALE` ou `SUPERSEDED`. Registram a revisão do conteúdo, a revisão esperada da classificação, a versão do catálogo, o conjunto proposto, confiança declarada e justificativas. Confiança do modelo não equivale a confirmação.

Uma proposta pendente não pode ter uma resolução aplicada. Aceitar exige revisor humano, conteúdo ainda correspondente, revisão de classificação incrementada em um e vínculos efetivamente gravados. Esses vínculos apontam para `source_suggestion_id`. Rejeitar não incrementa a classificação. Mudança de conteúdo ou de revisão exige marcar a proposta antiga como `STALE` antes de nova decisão.

Proveniência registra ator, método, justificativa, instante UTC, evidências e, quando aplicável, modelo e versão do prompt. O servidor deve preencher a identidade autenticada; aceitar um objeto enviado pelo cliente dizendo `HUMAN` não comprova autorização. Os revisores `HUMAN` dos exemplos são fixtures sintéticas.

## Questão atual, obsoleta ou em revisão

O campo obrigatório é `validity.status`:

| Valor | Significado / regra de uso |
| --- | --- |
| `CURRENT` | Revisada para o conteúdo e contexto indicados |
| `OBSOLETE` | Conteúdo ou gabarito superado; permanece preservado, fora do treino corrente |
| `UNDER_REVIEW` | Existe dúvida a resolver; fora da seleção corrente |
| `UNKNOWN` | Não há revisão suficiente; não deve receber rótulo de “atual” por omissão |

`basis` distingue fundamento jurídico, técnico, factual e atemporal. Há data de revisão, prazo para nova conferência, motivos, evidências, revisão do conteúdo avaliado e a decisão responsável. Revogação e alteração normativa são motivos explícitos, não decisões automáticas derivadas de uma menção textual à lei.

Uma decisão `CURRENT` ou `OBSOLETE` precisa de revisão humana correspondente ao hash do conteúdo. Decisões sensíveis ao tempo exigem evidência; `CURRENT` jurídico exige prazo de revisão. Depois desse prazo, a elegibilidade corrente fica bloqueada até nova conferência, sem apagar ou reescrever resultados históricos. Uma alteração normativa pode primeiro gerar revisão pendente; IA não publica sozinha uma decisão definitiva de vigência.

`scope=CURRENT_KNOWLEDGE` é diferente de `EXPLICIT_HISTORICAL_DATE`, que exige `context_date`. Uma questão válida para uma data histórica não entra automaticamente no treino de legislação atual. A interface futura precisa exibir status e contexto temporal juntos.

Na regra definida, o modo geral pode aceitar `UNKNOWN` com identificação explícita; o modo somente atuais aceita apenas `CURRENT` não vencido e sem contexto histórico. Consulta histórica permanece possível; exercício histórico tem escopo explícito. Estes filtros são definições testadas para a futura seleção, não alterações já implantadas.

## Questões abertas e correção

Todas as abertas exigem respostas de referência e uma rubrica versionada. Cada critério tem ID, descrição, pontuação máxima e indicação de essencialidade. Os pontos dos critérios devem somar `max_score`; `pass_score` não pode exceder esse máximo. O resultado deve avaliar todos os critérios, sem duplicação, respeitar seus limites e apresentar justificativas. Um critério essencial com zero impede acerto confirmado, mesmo se a soma superar o corte.

`RUBRIC` é correção manual. `SEMANTIC` exige motor identificado, versão e referência de calibração; similaridade isolada ou presença de palavras-chave não constitui correção. `AI` exige provedor xAI, modelo e versão de prompt, além da mesma rubrica. O modelo de texto definido é Grok 4.3. O prompt de correção aberta e o motor semântico ainda precisam ser implementados/avaliados nas etapas seguintes; identificadores semânticos e calibrações dos exemplos são fictícios, não certificações de qualidade.

Nesta primeira política, toda correção aberta precisa de confirmação humana. IA ou motor semântico podem gerar `PROVISIONAL`, com nota e feedback, mas `is_correct=null`: não credita automaticamente acerto, XP ou SRS. Automatização definitiva futura requer uma política específica validada; não é ativada por um campo de confiança do modelo.

Uma avaliação aponta para tentativa, proprietário, hash da questão, revisão da rubrica, política e motor usados. Nota fora dos limites, critério ausente, resultado incompatível com a rubrica ou modelo/prompt divergentes são rejeitados. Falha ou pendência não recebe nota zero como se fosse resposta errada.

Avaliações são eventos imutáveis. Nova correção, revisão humana ou resultado posterior usa novo `_id` e `supersedes_evaluation_id`, mantendo o mesmo `attempt_id`; isso não cria outra tentativa do estudante. A futura projeção considera o resultado confirmado aplicável, sem apagar o provisório nem reescrever respostas antigas. A avaliação deve corresponder ao proprietário, ao texto/alternativa realmente submetidos e ao snapshot da tentativa salvo no servidor; esses campos não são fornecidos como autoridade pelo modelo. A substituição só pode apontar para resultado da mesma tentativa e proprietário. Reavaliar usa o snapshot original; mudar a rubrica não migra tentativas passadas automaticamente.

O texto do estudante é entrada não confiável para o corretor: não pode substituir o prompt ou a rubrica, e a saída deve passar pela validação estruturada. Rubricas/gabaritos privados e credenciais permanecem no backend. Nenhuma resposta real foi enviada a um provedor nesta etapa.

## Revisões e gravação atômica

`revision` é a revisão do documento para CAS. `content_revision` é SHA-256 do JSON canonizado pelo [JCS/RFC 8785](https://www.rfc-editor.org/info/rfc8785/) contendo exatamente `question_type`, `question`, `answers`, `correct_answer_indices` e `grading`. Strings são preservadas, sem normalização Unicode implícita. Mudanças em rubrica, respostas de referência, política ou motor de correção mudam essa revisão; mudar aliases ou caches de IA não a muda.

Classificação e validade têm revisões próprias. Não se confunde `schema_version=2` da questão com o contrato de sincronização atualmente publicado, também numerado 2. Questões abertas exigirão negociação de capacidades/novo contrato de tentativa: clientes antigos não podem receber abertas como se fossem objetivas.

Uma atualização deve conferir proprietário/permissão, revisão esperada, hash de conteúdo e revisão do bloco alterado. Confirmação de sugestão e criação do evento de auditoria precisam ser recuperáveis como uma operação: transação ou outbox no próprio documento, com `event_id`/`operation_id` idempotentes, antes de confirmar o recebimento. A etapa 3 implementará essa garantia; ainda não existe um endpoint v2 que a execute.

Eventos de auditoria precisam guardar questão, ator, instante, tipo da alteração, revisões anterior/nova, motivo e referência ao estado anterior. Uma fila de outbox não pode descartar eventos ao atingir o limite; aplica-se retentativa/contrapressão. Históricos longos ficam em coleção própria, em vez de arrays ilimitados no documento de questão.

## Compatibilidade e preservação

Compatibilidade explícita contém proprietário, concurso, revisão do concurso, componente, questão, hash do conteúdo, versão do catálogo, revisão da decisão e proveniência. `DIRECT` exige interseção com um tópico principal confirmado e presente no componente. `ANALOGOUS` e `EXCLUDED` são decisões explícitas; origem, nome parecido e vínculo secundário não autorizam promoção automática a direto.

Estados pendentes não selecionam questões como vínculos confirmados. Mudança de questão, componente, edital ou revisão do catálogo exige reavaliação da compatibilidade. Expansão de assuntos pais para subassuntos deve ser opção explícita no edital; não se presume equivalência de irmãos ou cobertura integral do pai.

Os campos existentes de IA permanecem no documento, com seus nomes atuais. Metadados opcionais registram modelo, versão de prompt, hash de entrada e data de geração; dicas progressivas têm metadados por posição. Cache cuja revisão de entrada não corresponde ao conteúdo não deve ser reutilizado para o conteúdo novo. Ausência de metadados antigos não autoriza regeneração em massa nem descarte dos textos.

Campos legados desconhecidos são preservados. O schema permite propriedades adicionais na raiz para isso e valida estritamente os novos blocos conhecidos. A migração deve usar atualizações seletivas e manter inventários/backups; não substituir todo o documento a partir de um DTO que ignora campos extras.

`legal_validity` legado não é uma segunda autoridade no contrato novo: ausência de evidência não vira `CURRENT`. A etapa 3 deve definir sua projeção para clientes antigos, preservando o original. Também não se deduplicam questões apenas por texto parecido nem se reconstroem eventos de aprendizagem para adaptar a taxonomia.

## Verificação

```powershell
python -m pip install -r scripts/requirements-contracts.txt
python scripts/knowledge_catalog.py --check
python scripts/question_contracts.py --examples
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
```

Há sete documentos de questão de exemplo, uma sugestão, uma compatibilidade e quatro avaliações, todos sintéticos. Testes positivos e adversariais cobrem formato, ambiguidades, referência ao catálogo, confirmação indevida por IA, validade/obsolescência, contexto histórico, prazo vencido, rubrica, revisão, proveniência, limites de nota e isolamento de proprietário.
