# Nota técnica para Grok Build — passo 5: classificação assistida do acervo

## Objetivo e fronteira

Executar o próximo passo após as etapas 1–4: classificar questões existentes pelo catálogo compartilhado, com propostas revisáveis e aplicação auditável. A origem da questão, o conteúdo cobrado e a compatibilidade com um edital são entidades distintas. Não transformar semelhança de nome, confiança de IA ou participação em um tronco em classificação confirmada. Não iniciar uma migração em massa ao abrir o aplicativo nem incluir chamadas pagas de IA na inicialização ou em testes.

Esta nota define o trabalho seguinte; não afirma que endpoints de geração, decisão ou filas de revisão de sugestões já existam. Conferir o estado de publicação e as evidências da entrega 1.4.5 em `docs/remediation-validation/1.4.5/` antes de partir para produção. O banco real não foi reclassificado pelas etapas anteriores.

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

## Sequência de implementação

### 1. Inventariar sem alterar

Criar ferramenta de dry-run que leia documentos brutos e produza contagens por estado: legado não classificado, singular canônico existente, v2 confirmado/parcial, inválido/ambíguo, obsoleto/em revisão e sem metadados suficientes. Distinguir nome textual de referência canônica válida. Verificar que os IDs existentes ainda pertencem ao mesmo conhecimento; não presumir que todo vínculo singular legado já recebeu revisão humana. Relatório compartilhável não deve conter credenciais, conteúdo privado de usuários ou backup BSON.

Antes de aplicar alterações reais, usar o inventário/backup privado existente (`scripts/production-history.py`) e registrar hashes, contagens, opções e índices. O backup fica fora do Git; testar a recuperação em banco isolado. Não apagar o acervo, recriar coleções nem reconstruir eventos de aprendizagem para facilitar a migração.

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

## Critérios de aceite

1. Propostas pendentes nunca alteram a seleção, cobertura, XP ou SRS.
2. Aceite/rejeição têm autorização, CAS, idempotência, proveniência humana e auditoria recuperável; dois revisores concorrentes não podem aplicar decisões incompatíveis.
3. Entrada maliciosa, saída inválida da IA, catálogo desconhecido, proposta antiga e troca de proprietário são rejeitados.
4. Repetir a geração reutiliza proposta aplicável; repetir decisão não duplica revisão, chamada paga ou evento. Falha entre escritas e perda de ACK têm testes de recuperação.
5. Principal/secundário, assuntos/subassuntos e validade seguem os contratos existentes; não há falsa equivalência entre conteúdo, origem e edital.
6. Uma disciplina em duas etapas conserva os componentes, pesos e decisões de compatibilidade separados.
7. Antes/depois comprovam preservação de conteúdo, caches compatíveis, documentos legados extras, respostas, histórico de aprendizagem e serviços vizinhos.
8. Relatório final separa código testado, aplicação real, quantidade revisada e pendências. Atualizar README, operação, specs e exemplos. Não declarar o acervo inteiro classificado com base em um lote.

## Verificação e entrega

```powershell
python scripts/knowledge_catalog.py --check
python scripts/question_contracts.py --examples
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
$env:COGNIVA_TEST_MONGODB_URI='mongodb://127.0.0.1:27048/'
cargo test --locked --manifest-path backend-rust/Cargo.toml -- --include-ignored --test-threads=1
```

O Mongo acima deve ser uma instância sintética dedicada, com comandos de teste habilitados, nunca Atlas/produção. `failCommand` é global ao servidor, por isso a serialização. Acrescentar testes positivos/adversariais de sugestões, decisões e recuperação. Usar provedor falso nos testes: não consumir a chave real. Executar testes JVM, lint, snapshots existentes e emulador se houver interface nova; não substituir a matriz pela imagem de um componente isolado.

Compilar e assinar fora da VPS. Só promover depois dos portões existentes, verificar health/ready, migrações aditivas, rollback, versão e SHA do APK servido contra o artefato de CI. Não alterar o esquema de assinatura/lineage nem mesclar PR sem instrução. Se o trabalho for exclusivamente backend/ferramenta, não publicar um APK novo sem necessidade; documentar claramente o que foi promovido. A ausência de um aparelho físico conectado deve continuar explícita, sem transformar teste de emulador em prova de instalação no telefone.
