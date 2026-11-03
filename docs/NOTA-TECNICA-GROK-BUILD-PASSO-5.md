# Nota técnica para Grok Build — etapas 5, 6 e 7

Atualização da implementação: **1.4.6/build 13**, revisão `51fd03cf1f0db334000ec4435f52f8ae62bf2380`, promovida em 02/11/2026 às 21:55:36 (UTC−03:00). Fluxo do passo 5 auditado, seleção/projeção do passo 6 e validação/publicação do passo 7 conferidos. [Estado e evidências](ETAPAS-5-7-CLASSIFICACAO-SELECAO.md). O inventário tem 612 legadas sem classificação e seis gabaritos ambíguos; zero lotes reais confirmados. Publicar o código não conclui a revisão do acervo. Histórico e documentos originais preservados.

## Plano e dependências

Esta é a mesma nota originalmente criada para o passo 5; o nome do arquivo foi mantido para preservar os links. Agora orienta a sequência completa restante:

| Etapa | Entrega | Dependência e portão de conclusão |
| --- | --- | --- |
| 5 — Classificar o acervo existente | Inventário, sugestões xAI com justificativas, revisão/decisão em lotes e ambiguidades pendentes | Etapas 1–4 verificadas; comandos recuperáveis, revisão humana e preservação comprovadas |
| 6 — Atualizar seleção e progresso | Uso de vínculos confirmados no treino, simulado e tronco comum; reaproveitamento entre concursos e cobertura sem crédito indevido | Propostas pendentes isoladas; contrato de projeção e elegibilidade validado com dados sintéticos e legado |
| 7 — Validar e publicar | Provas de migração, sincronização, edição/exclusão, classificação e seleção; backend/APK promovidos e documentação atualizada | Portões da etapa 6 verdes, backup e comparação antes/depois, assinatura e rollback verificados |

A preparação das três etapas pode avançar em paralelo no código, mas uma proposta gerada na etapa 5 não pode passar a afetar a seleção antes de confirmação válida e de a etapa 6 reconhecer corretamente sua revisão. Publicação não equivale a classificação integral do banco: registrar separadamente o tamanho do lote revisado, pendências e abrangência da entrega.

## Objetivo e fronteira

Executar as etapas 5–7 após as etapas 1–4: classificar questões existentes pelo catálogo compartilhado, com propostas revisáveis e aplicação auditável; completar seleção/progresso; validar e publicar a entrega. A origem da questão, o conteúdo cobrado e a compatibilidade com um edital são entidades distintas. Não transformar semelhança de nome, confiança de IA ou participação em um tronco em classificação confirmada. Não iniciar uma migração em massa ao abrir o aplicativo nem incluir chamadas pagas de IA na inicialização ou em testes.

Este roteiro foi usado para implementar a entrega 1.4.6; consultar o [registro executado](ETAPAS-5-7-CLASSIFICACAO-SELECAO.md) antes de repetir trabalho. Endpoints de geração, decisão e compatibilidade já estão publicados. A base histórica das etapas 3–4 permanece `9dee93673f2e170e06f2d77ca378df973449414d`; a implementação operacional aceita das etapas 5–7 é `51fd03cf1f0db334000ec4435f52f8ae62bf2380`, [pipeline 41553999544](https://github.com/danielcdes-create/cogniva/actions/runs/41553999544). Commits posteriores de documentação não alteram esse runtime. Os lotes reais continuam exigindo revisão humana e não devem ser aplicados automaticamente pelo deploy.

## Leitura obrigatória do repositório

| Fonte | Uso |
| --- | --- |
| `docs/CATALOGO-CONHECIMENTOS.md`, `docs/catalogo/CONHECIMENTOS.md` | Escopo editorial, aliases, relações e consulta legível |
| `shared/knowledge-catalog.json`, `shared/knowledge-id-registry.json` | IDs e versão imutáveis, disciplina/assunto/subassunto |
| `shared/knowledge-catalog.schema.json`, `scripts/knowledge_catalog.py` | Validação estrutural, semântica e exportação |
| `docs/SCHEMA-QUESTOES-V2.md`, `shared/schemas/question-contracts.schema.json` | Autoridade dos contratos de questão, sugestão, compatibilidade e avaliação |
| `shared/examples/question-contracts/`, `scripts/question_contracts.py` | Exemplos sintéticos e regras executáveis |
| `docs/ETAPA-3-BACKEND-MONGO.md` | Validações, escrita por revisão, recibos, outbox e índices |
| `docs/ETAPA-4-CADASTRO-CATALOGO.md`, `shared/schemas/exam-component.schema.json` | Identidade local do componente, etapas repetidas e associação pendente |
| `backend-rust/src/services/question_contracts.rs` | Validação de questão v2, taxonomia e hash JCS |
| `backend-rust/src/services/question_storage.rs` | Escrita durável, CAS, idempotência, auditoria e preparação do Mongo |
| `backend-rust/src/services/question_compatibility.rs` | Leitura legada, filtros canônicos e exclusões de prática |
| `backend-rust/src/services/ai_tutor.rs`, `backend-rust/src/config.rs` | Integração xAI existente; recursos pedagógicos e configuração |
| `backend-rust/src/routes/questions_v2.rs`, `backend-rust/src/security.rs` | Sessão, autorização administrativa e rotas existentes |
| `backend-rust/src/integration_tests.rs`, `scripts/tests/` | Provas de durabilidade, compatibilidade e casos adversariais |
| `.github/workflows/deploy.yml`, `.github/workflows/validate.yml`, `deploy/promote.py` | Portões e promoção controlada |

Ler também as duas notas em `Notas Conversao Kotlin_Rust/` sobre arquitetura/CI na VPS e screenshots JVM/emulador/hash do APK. Não compilar na VPS, não acrescentar JVM ao servidor, não alterar o Compose compartilhado nem os outros serviços. Exemplos e estimativas históricos nessas notas não substituem a configuração e as medições atuais do repositório.

## Estado entregue nas etapas anteriores

- Catálogo versão 1: 7 troncos, 35 disciplinas, 49 assuntos e 174 subassuntos, com 265 identidades reservadas e 41 aliases de disciplina. Templates legados permanecem no arquivo original; aliases não são prova de classificação.
- Mongo: `todasQuestoes`, `question_classification_suggestions`, `question_exam_compatibility`, `answer_evaluations`, mais recibos, snapshot de catálogo, comandos e eventos de revisão. A preparação acrescenta validadores/índices; não reescreve questões antigas.
- API existente: `GET /api/v1/catalog/knowledge`; `GET /api/v1/admin/questions/v2/:id`; `POST /api/v1/admin/questions/v2` com `operation_id`, `expected_revision`, `dry_run` e `document`. O POST começa em dry-run e exige permissão administrativa para aplicar.
- O escritor genérico rejeita vínculos com `source_suggestion_id` preenchido: a aceitação precisa de um fluxo específico, não de um atalho que simule revisão.
- Android associa componentes ao catálogo; `local_subject_id` identifica a ocorrência, inclusive disciplina repetida em etapas diferentes. Componentes e tópicos `custom-UUID` permanecem pendentes sem perda de texto ou identidade.
- Questões abertas têm contrato de armazenamento/rubrica, mas não há execução de correção aberta no cliente atual. Não ampliar o protocolo de respostas como efeito colateral desta classificação.

Não confundir identidades de revisão: `question.revision` é o contador inteiro usado no CAS do documento; `question.content_revision` é o SHA-256/JCS do conteúdo definido pelo contrato; `classification.revision` é o contador da classificação. `suggestion.question_content_revision` deve corresponder a `question.content_revision`. O DTO objetivo legado usa `question_revision` calculado pelo protocolo de tentativas, com outro algoritmo: não copiá-lo como se fosse o hash JCS v2 nem reescrever tentativas para uniformizar os hashes. Usar os validadores e helpers existentes.

### Aceite verificado da base 1.4.5

Etapas 1–4 concluídas e publicadas em 02/11/2026 às 17:02:53 (UTC−03:00). O run acima concluiu todos os seis jobs, com 32 Rust/Mongo sintético, 36 contratos Python, 50 JVM, lint/nove imagens e dez testes no emulador API 35. O rollback controlado passou antes da promoção efetiva.

O aceite externo confirmou health/ready e contrato 2; catálogo normalizado/projetado com 35 disciplinas e sete troncos, 223 assuntos/subassuntos e três templates originais; quatro validadores/recibos, oito índices e snapshot imutável versão 1. Os 618 documentos de questão e quatro cadastros ficaram idênticos em bytes; histórico protegido preservou 20 documentos/32 eventos e SHA-256 `b52793dfe1eb3fb77a9e7aad1047ae78e5c9b19cd829791e8fa0ee80acf8da90`. Nenhuma questão foi reclassificada; o inventário ainda aponta zero vínculos canônicos singulares no acervo. Essa é a pendência de dados que a etapa 5 deve tratar, sem confundi-la com ausência do catálogo.

APK 1.4.5/build 12: 12.796.722 bytes, SHA-256 `0727c9a4dc2044e3599a43affe13a0766a9e59a924c13d29c40cc3ef537d4cc4`, bytes servidos iguais ao bundle assinado; assinatura privada/lineage conservada. Runtime medido: 29.011.594 bytes. Compose e containers vizinhos preservados. A prova nativa foi em emulador; não há comprovação de instalação no telefone físico.

Consultar `docs/remediation-validation/1.4.5/actions.json`, `production-release.json`, `mongo-preservation.json`, `question-selection.json`, `release-bundle.json`, `emulator.xml`, `jvm-results/` e logs do pipeline. Backups BSON ficam privados fora do Git; os inventários públicos contêm apenas metadados, contagens e hashes. A futura aplicação de classificações deverá alterar explicitamente os campos aprovados, mantendo os originais auditáveis; não exigir igualdade total do documento depois de uma alteração intencional nem deixar de verificar a preservação dos campos restantes.

## Etapa 5 — classificar o acervo existente

### 1. Inventariar sem alterar

Criar ferramenta de dry-run que leia documentos brutos e produza contagens por estado: legado não classificado, singular canônico existente, v2 confirmado/parcial, inválido/ambíguo, obsoleto/em revisão e sem metadados suficientes. Distinguir nome textual de referência canônica válida. Verificar que os IDs existentes ainda pertencem ao mesmo conhecimento; não presumir que todo vínculo singular legado já recebeu revisão humana. Relatório compartilhável não deve conter credenciais, conteúdo privado de usuários ou backup BSON.

Antes de aplicar alterações reais, usar o inventário/backup privado existente (`scripts/production-history.py`) e registrar hashes, contagens, opções e índices. O backup fica fora do Git; testar a recuperação em banco isolado. Não apagar o acervo, recriar coleções nem reconstruir eventos de aprendizagem para facilitar a migração.

O verificador `scripts/verify-question-storage-backup.py` comprova a instalação aditiva das etapas 3–4 a partir de dois inventários privados. Para o passo 5, a comparação de questões deve passar a conferir o conjunto explícito de campos/revisões autorizados e a preservação dos demais, pois classificar altera intencionalmente o documento; não desativar a comparação do histórico nem tomar hashes diferentes como permissão para qualquer alteração.

### 2. Gerar propostas, não confirmações

Implementar serviço e rotas administrativos para criar/listar propostas em `question_classification_suggestions`. O contrato `suggestion` já define estados, conjunto proposto, justificativas, confiança, hash/revisão do conteúdo, revisão esperada da classificação e versão do catálogo. Usar seus nomes exatos; não criar um segundo schema paralelo.

Restringir os candidatos ao catálogo e enviar somente os dados necessários da questão. A entrada da IA é conteúdo não confiável: enunciados e alternativas não podem dar instruções ao sistema nem executar ferramentas. A resposta deve ser JSON validado, com IDs existentes e relações disciplina/tópico consistentes. Rejeitar texto livre fora do contrato, IDs inventados, principal contextual, múltiplos principais, referências a outra disciplina e confiança fora de faixa. A confiança declarada não é probabilidade calibrada nem autorização de publicação.

Usar xAI, modelo configurado `grok-4.3`, pela infraestrutura existente. A chave já está configurada privadamente; nunca incluí-la em código, documentação, APK, testes ou logs. Não substituir o modelo por `grok-4.7` nem trocar provedor silenciosamente. Versionar o prompt de classificação e seu hash. O prompt de recursos pedagógicos não equivale automaticamente a um prompt de classificação: conferir o que já existe e criar um prompt específico versionado somente se necessário.

Reutilizar proposta compatível com a mesma questão, hash/revisão de conteúdo, revisão de classificação, catálogo e versão de prompt/modelo. Fazer reserva durável por essa identidade antes da chamada e limitar concorrência. Timeout, 429 e falha do provedor não devem produzir proposta confirmada, cache vazio como sucesso ou uma chamada paga a cada clique. Reprocessamento precisa de justificativa explícita. Estabelecer lote, orçamento máximo e retomada antes de qualquer execução real; não lançar um lote pago com tamanho indefinido.

### 3. Revisar e resolver com CAS e auditoria

Disponibilizar decisão humana explícita: aceitar, editar e aceitar, rejeitar ou adiar. Mostrar questão, gabarito, origem disponível, estado de validade, proposta, principal/secundários, nível de cobertura, justificativas e versão do catálogo. Principal/secundário descreve o conteúdo; não informa em quais concursos a questão foi aplicada.

Aceitar exige sessão administrativa, atribuição do revisor no servidor, conteúdo/hash ainda correspondentes e revisão de classificação exatamente esperada. Atualizar a classificação em uma unidade e manter os espelhos `canonical_subject_id`/`canonical_topic_id` coerentes com o principal. Vínculos aplicados precisam referenciar `source_suggestion_id`. `CONFIRMED` exige exatamente um principal direto; uma classificação parcial não pode fingir confirmação integral.

Como o Atlas pode limitar transações, desenhar a operação para falha entre documentos: comando durável idempotente, CAS na questão, evento/outbox na mesma escrita e resolução recuperável da sugestão. Não marcar `ACCEPTED` antes de poder comprovar os vínculos aplicados; não responder sucesso enquanto há estado irresoluto sem recibo recuperável. A solução deve demonstrar recuperação de falha após o CAS e antes da atualização da sugestão, bem como replay após perda do ACK. A outbox da etapa 3 já registra revisões; estendê-la de forma versionada em vez de descartá-la ou competir por seu único campo.

Mudança do conteúdo, revisão de classificação ou versão aplicável deve tornar a proposta inaplicável (`STALE`/`SUPERSEDED` conforme o contrato), exigindo nova revisão. Rejeição mantém a classificação. Revisor/ator nunca vem da resposta do modelo ou de um ID arbitrário no corpo. Não dar permissão de administrador automaticamente a um usuário.

### 4. Compatibilidade com editais

A classificação confirmada não insere a questão em todo concurso do tronco. Quando houver decisão explícita de compatibilidade, usar `question_exam_compatibility`, com proprietário, concurso, revisão, componente (`local_subject_id`), versão do catálogo e hash da questão. Não usar só `canonical_subject_id` como identidade do componente: a mesma disciplina pode existir em etapas diferentes.

`DIRECT` exige correspondência com conteúdo principal direto confirmado e tópico ativo do componente. `ANALOGOUS` e `EXCLUDED` precisam de decisão explícita. Pai e filho não são equivalentes automáticos; expansão exige especificação e opção explícita, fora do comportamento padrão. Mudanças do edital ou da questão invalidam decisões antigas para a seleção atual, preservando o histórico.

### 5. Aplicar gradualmente e medir

Começar por exemplos sintéticos, depois dry-run do inventário e revisão de um lote pequeno com limite explícito. O plano de aplicação precisa mostrar quais documentos/campos serão alterados e quais comandos poderão ser retomados. Armazenar classificação no documento, preservando campos extras, origem, conteúdo, gabarito, recursos de IA e revisões existentes. Não executar `replace_one` com um DTO parcial.

Não transformar `UNKNOWN` em `CURRENT`, não certificar vigência por confiança do modelo e não remover exclusão legada sem evidência revisada. Questões obsoletas/em revisão/arquivadas não entram na prática corrente. Alterar conteúdo ou gabarito exige o fluxo de revisão de conteúdo, com invalidação e histórico de cache conforme a etapa 3. Classificação sozinha não deve regenerar recursos pedagógicos que permanecem compatíveis com o mesmo conteúdo.

## Critérios de aceite da etapa 5

1. Propostas pendentes nunca alteram a seleção, cobertura, XP ou SRS.
2. Aceite/rejeição têm autorização, CAS, idempotência, proveniência humana e auditoria recuperável; dois revisores concorrentes não podem aplicar decisões incompatíveis.
3. Entrada maliciosa, saída inválida da IA, catálogo desconhecido, proposta antiga e troca de proprietário são rejeitados.
4. Repetir a geração reutiliza proposta aplicável; repetir decisão não duplica revisão, chamada paga ou evento. Falha entre escritas e perda de ACK têm testes de recuperação.
5. Principal/secundário, assuntos/subassuntos e validade seguem os contratos existentes; não há falsa equivalência entre conteúdo, origem e edital.
6. Uma disciplina em duas etapas conserva os componentes, pesos e decisões de compatibilidade separados.
7. Antes/depois comprovam preservação de conteúdo, caches compatíveis, documentos legados extras, respostas, histórico de aprendizagem e serviços vizinhos.
8. Relatório final separa código testado, aplicação real, quantidade revisada e pendências. Atualizar README, operação, specs e exemplos. Não declarar o acervo inteiro classificado com base em um lote.

## Etapa 6 — atualizar seleção e progresso

### Pontos de entrada

Auditar e adaptar os caminhos online e offline em conjunto:

- `backend-rust/src/services/selection_v2.rs`, `quiz_selector.rs` e `question_compatibility.rs`: filtros, elegibilidade, recuperação e DTO objetivo.
- `backend-rust/src/services/exam_engine.rs`, `routes/exams.rs` e `routes/contest_projection.rs`: tronco comum, quotas, nota, prontidão e projeção do edital.
- `backend-rust/src/services/attempts.rs` e `backend-rust/src/routes/learning.rs`: identidade, ACK e projeção de eventos.
- `android-app/app/src/main/java/br/com/concursoprep/app/domain/exam/ExamEngine.kt`, `domain/model/Learning.kt` e `data/repository/ConcursoRepository.kt` (os dois últimos relativos ao mesmo pacote `app`): cache, sorteio, projeções e filas.
- `android-app/app/src/main/java/br/com/concursoprep/app/data/local/QuestionCacheMapping.kt`, `domain/model/SessionSnapshot.kt`, `ui/screens/ContestsScreen.kt` e `PracticeAndQuestionScreen.kt`: identidade de sessão, snapshot da grade, cards e resultado ponderado.

Não começar removendo a compatibilidade legada já comprovada. A etapa 3 reconhece o principal direto em `classification.links` e os espelhos singulares; a etapa 4 já identifica componentes e evita repetir questões entre quotas. A etapa 6 deve completar e testar o uso desses contratos nas projeções, sem criar uma segunda implementação divergente para Android ou para modos especiais.

### Elegibilidade e seleção

1. Resolver as referências pela versão do catálogo e pelo estado aplicável da questão. Usar vínculos individualmente confirmados/revisados; `PENDING`, `STALE`, propostas rejeitadas e vínculos apenas sugeridos não participam. Uma classificação global `PARTIAL` pode conter um principal já revisado, conforme o contrato atual; não tratar campos ainda pendentes como confirmados.
2. Adotar o principal `DIRECT` como autoridade da seleção canônica padrão. Vínculos secundários/contextuais não devem virar principal nem autorizar automaticamente `DIRECT` em outro edital. Compatibilidade análoga exige decisão explícita e identificação visual; não misturar silenciosamente com as quotas diretas.
3. Respeitar tópicos ativos do componente, etapa, grupo, banca e quantidade quando o modo exige a grade. Mesmo `canonical_subject_id` em etapas distintas conserva `local_subject_id` e suas quotas. `exam_component_id` pertence à sessão, não à questão; não persistir esse contexto como classificação do banco.
4. Expandir assunto para subassuntos somente quando a opção estiver explicitamente cadastrada e versionada. O comportamento entregue é correspondência exata. Não considerar um subassunto como domínio integral do pai, nem selecionar todos os irmãos pela semelhança de prefixo do ID.
5. Aplicar as mesmas exclusões de conteúdo inválido, arquivado, obsoleto, em revisão, com prazo vencido ou de escopo histórico nos modos geral, single, treino, simulado, SRS, caderno de erros e desafio. Preservar a distinção entre questões legadas admitidas com validade desconhecida e questões efetivamente revisadas como atuais; não rotular a primeira situação como revisão jurídica concluída.
6. Preservar o acesso geral: shuffle e filas pessoais não herdam um concurso arquivado apenas porque sua aba estava selecionada. Ausência de classificação nova não deve tornar todo o acervo legado inacessível novamente. Caminhos de treino por rótulo legado devem continuar explicitamente distintos de cobertura canônica confirmada.
7. Compartilhar a questão por identidade, sem copiá-la para cada concurso. Excluir IDs já sorteados dentro da mesma sessão, inclusive entre componentes. Se as quotas não puderem ser atendidas com questões distintas válidas, informar insuficiência e a causa; não repetir IDs, reduzir a grade silenciosamente ou inventar conteúdo.

### Tentativas, cobertura e domínio

Uma tentativa é identificada por `attempt_id` e proprietário. A mesma questão pode ter várias tentativas reais em sessões diferentes; elas não devem ser deduplicadas só por `question_id`. Por outro lado, projetar uma tentativa em dois concursos não cria dois eventos, dois ACKs, XP adicional nem duas entradas SRS. Agregados globais contam cada tentativa aceita uma vez; visões por edital mostram projeções do mesmo histórico com contexto explícito.

Preservar o snapshot original da tentativa: conteúdo/revisão, alternativa ou resposta, instante, confiança, concurso e componente quando disponíveis. Não reescrever o evento antigo para apontar à classificação atual. Uma projeção nova pode reutilizar aprendizagem em outro concurso apenas quando consegue demonstrar correspondência do conteúdo estudado com o vínculo confirmado usado na projeção. Se só existir um ID antigo, sem revisão verificável, manter a evidência histórica e a ambiguidade; não assumir conteúdo idêntico depois de uma edição de enunciado/gabarito.

Para documentos legados, usar a revisão do protocolo de tentativas quando ela estiver disponível e comparar pelo algoritmo correto. Não comparar diretamente seu hash com o JCS v2. Ausência de evidência de tópico não elimina tentativas/acertos globais: apresentar cobertura canônica desconhecida separadamente dos totais preservados.

Definir uma política versionada de contribuição para cobertura antes de alterar os cálculos. O padrão conservador deve conceder evidência ao tópico principal direto efetivamente cobrado. Vínculo secundário/contextual concede zero de domínio direto por padrão; uma contribuição parcial futura exige regra explícita, limitada e testada, nunca crédito integral em todos os tópicos vinculados. Acerto em um subassunto não completa automaticamente seu assunto pai. Ausência de cobertura permanece desconhecida/zero evidência, sem inventar uma resposta errada.

Reutilizar o mesmo domínio de conhecimento entre concursos quando os vínculos de conteúdo forem equivalentes e verificados. Cada componente calcula sua cobertura somente sobre seus próprios tópicos ativos; notas e prontidão respeitam os pesos configurados. Contar a mesma disciplina em duas etapas pode ser correto na ponderação da prova, mas não transforma uma tentativa em duas nem dois componentes em dois concursos para o tronco comum. Progresso global e cobertura por edital são métricas diferentes e precisam manter denominadores claros.

Resultados de IA/semânticos provisórios para questões abertas não viram acerto confirmado, domínio ou evento SRS definitivo. Uma revisão de avaliação mantém o mesmo `attempt_id` e referencia a anterior; não cria uma tentativa adicional. Não mudar os intervalos históricos nem o dia civil de estudo como efeito colateral da taxonomia.

### Sincronização e mudanças de classificação

Propagar alterações de classificação, validade e disponibilidade para o cache de forma versionada. Uma questão que ficou obsoleta ou deixou de ser elegível deve sair das ações atuais sem apagar o conteúdo preservado, a sessão original ou o histórico. Contadores e ações precisam usar o mesmo critério de `practice_available`; um card não pode anunciar questões praticáveis que o seletor sempre rejeita.

Classificação nova, edição de componente ou mudança de revisão do edital invalida decisões de compatibilidade antigas para o contexto atual. Cache offline deve reconhecer essa incerteza, não usar uma aprovação vencida como se fosse recente. Preservar comandos e ACKs de tentativas já feitas, inclusive quando a questão mudou depois: replay de uma tentativa aceita não pode falhar só porque a versão atual da questão deixou de ser selecionável.

Se incluir treino de respostas abertas na entrega, implementar antes o portão de capacidades e o protocolo tipado de submissão, texto livre persistente, rubrica/snapshot, avaliação por critério, estados provisório/confirmado e recuperação de falhas. O campo `OPEN_ENDED` no schema não implementa esses recursos sozinho. Enquanto esse fluxo não estiver validado, manter abertas fora do cliente objetivo e declarar claramente essa pendência; não convertê-las em múltipla escolha fictícia nem anunciar suporte completo.

### Critérios de aceite da etapa 6

- Uma questão principal direta confirmada é reutilizável em dois concursos, sem duplicar evento, XP ou SRS.
- Uma proposta pendente e um vínculo secundário/contextual não produzem o mesmo crédito do principal direto.
- Duas etapas da mesma disciplina preservam quotas, pesos, tópicos, cards e identidade de sessão; sorteio e nota online/offline concordam.
- Alterar classificação ou validade não reescreve tentativas, não elimina acertos históricos e não reutiliza aprovação de compatibilidade vencida.
- Treino, shuffle, single, simulado, tronco comum e filas pessoais têm testes de elegibilidade, insuficiência e legado; contadores acompanham as ações.
- Assunto, subassunto e pai/filho não recebem cobertura integral por associação implícita; denominadores e política de contribuição são documentados.

## Etapa 7 — validar e publicar

### Matriz mínima de validação

| Área | Cenários obrigatórios |
| --- | --- |
| Migração e schemas | Legado intacto, v2 válido/inválido, extras desconhecidos preservados, preparação repetida, catálogo imutável, índices e validadores efetivos |
| Classificação | Geração reutilizada, saída xAI inválida, ambiguidade mantida, aceite/rejeição humana, proposta antiga, revisão concorrente e recuperação entre escritas |
| Conteúdo e validade | Cinco alternativas, certo/errado, abertas armazenáveis, gabarito ambíguo, obsoleta/em revisão, prazo vencido, contexto histórico e cache compatível/incompatível |
| Cadastro | Criar/editar/vincular/desvincular quando suportado, mesma disciplina em etapas diferentes, tópico fora da disciplina rejeitado, custom pendente, arquivar/restaurar/excluir |
| Preservação | Não apagar questão global ao excluir componente/concurso; revisões, conteúdo, origem, campos extras, respostas e histórico mantidos |
| Sincronização | Offline/reabertura, troca de conta, conflito de revisão, perda de ACK, replay, lote parcial e alteração remota enquanto existe tentativa pendente |
| Seleção | Todos os modos, filtro por componente, quotas insuficientes, nenhuma repetição na sessão, conta correta, concurso arquivado e compatibilidade vencida |
| Progresso | Tentativa única projetada em dois concursos, duas tentativas reais da mesma questão, secundário sem crédito integral, pai/filho e denominadores claros |
| Interface | Fluxo real completo, fonte 2.0, tema claro/escuro, campos/CTA alcançáveis, cutout e navegação; sem sucesso visual antes de persistência/ACK |
| Operação | Build externo, tamanho medido, assinatura/lineage, APK servido idêntico ao artefato, saúde/readiness, rollback, gate e serviços vizinhos preservados |

Usar dados sintéticos para provar os cenários destrutivos e de concorrência. Não disparar uma IA paga em CI, não executar `failCommand` no Atlas e não apagar dados reais para simular falhas. Lotes reais devem ter inventário, orçamento e seleção explícitos; acompanhar documentos efetivamente revisados e pendentes, não apenas chamadas bem-sucedidas ao modelo.

### Sequência de publicação e aceite

1. Atualizar schemas, validadores, exemplos, tipos Android/Rust e política de projeção na mesma alteração. Rodar a matriz e conferir os caminhos reais, além de testes unitários que apenas espelhem a implementação.
2. Capturar uma referência atual do servidor com `scripts/capture-production-baseline.py` e um backup privado com `scripts/production-history.py`. Guardar a definição de índices/views e testar restauração em instância isolada. Para alterações intencionais de classificação, comparar os campos autorizados; para históricos e serviços vizinhos, exigir preservação.
3. Validar migração em banco sintético com cópia compatível da estrutura, e fazer dry-run do lote real. Mostrar plano de aplicação, revisões esperadas, comandos retomáveis e estratégia de recuperação. Não tratar rollback do container como rollback automático dos documentos já classificados.
4. Compilar/assinar em ambiente local ou GitHub Actions e promover pelo fluxo existente, com imagem por digest e artefatos por SHA. A migração aditiva deve terminar antes de abrir tráfego. Não construir na VPS nem substituir o Compose compartilhado.
5. Verificar o rollback controlado e a promoção efetiva nos logs. Conferir health/ready, revisão, Mongo, gate aberto, rejeição de acesso anônimo e preservação de FIN/Asbuildo/Spamshield. Usar comparação com a referência capturada antes desta promoção, não com containers de uma release antiga.
6. Baixar o APK servido, comparar tamanho/SHA-256 com `release.json` e o bundle do CI, conferir pacote, versionName/versionCode, ausência de `debuggable` e a assinatura/lineage existente. Testar atualização com dados persistidos quando houver dispositivo disponível; registrar o limite quando a prova for apenas de emulador.
7. Executar probes autenticados somente de leitura para catálogo e modos de questões. `scripts/probe-question-selection.py` aceita `--expected-version` e `--assert-catalog` para a base atual; se o catálogo mudar de versão, atualizar as contagens esperadas de forma revisada, sem remover as asserções. Não enviar tentativas ou criar textos pagos para comprovar saúde.
8. Repetir o backup de inventário e produzir evidência antes/depois. Conferir validadores/índices/snapshot, documentos alterados por comando aprovado, histórico protegido, contagens de pendências e compatibilidade do legado. Nenhuma classificação pode desaparecer silenciosamente entre um ACK e a persistência.
9. Atualizar README, `docs/OPERACAO.md`, documentos de catálogo/schema/etapas, exemplos, registro de execução e PR com o resultado final. Registrar revisão publicada, run, hashes, contagens, lotes revisados, pendências e limites físicos. Código em uma branch, artefato gerado e serviço promovido são estados distintos; afirmar apenas os estados verificados.

### Critérios de conclusão da etapa 7

Todos os testes e os gates exigidos passam no mesmo código publicado. Backend/APK atendem à revisão/versionamento anunciados. Dados e histórico têm comparação verificável, e alterações intencionais são rastreáveis a decisões autorizadas. O aplicativo continua abrindo questões nos modos gerais e respeitando a grade nos modos específicos. Não há pendência escondida de migração, fila, assinatura, rollback ou publicação. A documentação permite reproduzir o aceite e retomar um lote interrompido.

## Comandos de verificação e limites da entrega

```powershell
python scripts/knowledge_catalog.py --check
python scripts/question_contracts.py --examples
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
$env:COGNIVA_TEST_MONGODB_URI='mongodb://127.0.0.1:27048/'
cargo test --locked --manifest-path backend-rust/Cargo.toml -- --include-ignored --test-threads=1
```

O Mongo acima deve ser uma instância sintética dedicada, com comandos de teste habilitados, nunca Atlas/produção. `failCommand` é global ao servidor, por isso a serialização. Acrescentar testes positivos/adversariais de sugestões, decisões e recuperação. Usar provedor falso nos testes: não consumir a chave real. Executar testes JVM, lint, snapshots existentes e emulador se houver interface nova; não substituir a matriz pela imagem de um componente isolado.

Compilar e assinar fora da VPS. Só promover depois dos portões existentes, verificar health/ready, migrações aditivas, rollback, versão e SHA do APK servido contra o artefato de CI. Não alterar o esquema de assinatura/lineage nem mesclar PR sem instrução. Se o trabalho for exclusivamente backend/ferramenta, não publicar um APK novo sem necessidade; documentar claramente o que foi promovido. A ausência de um aparelho físico conectado deve continuar explícita, sem transformar teste de emulador em prova de instalação no telefone.
