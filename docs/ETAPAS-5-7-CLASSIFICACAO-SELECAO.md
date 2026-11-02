# Classificação, seleção e progresso — entrega 1.4.6/build 13

Implementação em validação. A versão operacional anterior é 1.4.5. Estado de publicação e aceite desta entrega: `remediation-validation/1.4.6/`. A [nota para Grok Build](NOTA-TECNICA-GROK-BUILD-PASSO-5.md) continua como roteiro e fronteira de escopo.

## Conferência do passo 5

O trabalho recebido estava no workspace, sem commit. Há inventário seco, classificação xAI, revisão administrativa, decisão humana e compatibilidade por componente, com testes sintéticos. Isso não significa que as questões reais já tenham sido classificadas. O inventário de produção é somente leitura; geração de propostas e aceitação de lotes reais não são executadas pelo deploy.

A revisão acrescentou nomes/aliases e nome da disciplina aos candidatos do prompt (versão 2), reserva concorrente de geração, retomada por lote sem chamada implícita adicional, recuperação de decisão pelo evento atômico, snapshot original completo e proteção CAS dos campos de conteúdo. A proposta original da IA é preservada ao editar/aceitar; os vínculos humanos editados ficam no documento e na auditoria. O inventário valida v2 completo e reconhece abertas. O comparador de preservação protege presença de campos, inclusive os que valem null.

APIs administrativas autenticadas: `POST /api/v1/admin/classification/proposals`, `GET /api/v1/admin/classification/proposals/:question_id`, `POST /api/v1/admin/classification/decisions` e `GET/POST /api/v1/admin/classification/compatibility`. `ADMIN_USER_IDS` é atribuição operacional explícita, sem concessão automática de privilégios. Propostas são pendentes; aceitar, editar/aceitar, rejeitar e adiar exigem decisão e justificativa. Revisões antigas não são aplicadas sobre conteúdo novo. Falhas após o CAS podem ser recuperadas do evento/outbox, inclusive no início do processo. Não há chamada paga na inicialização ou nos testes.

Compatibilidade tem comando idempotente, revisão sequencial por conta/concurso/componente/questão e plano persistido antes da inserção. Repetir operação com outro corpo é conflito. Uma cópia de concurso com revisão zero precisa ser salva antes de receber decisão explícita. Novas decisões não substituem o histórico de decisões anteriores.

## Passo 6: seleção e contexto

Vínculos estruturados são reconhecidos tanto em documentos v2 quanto no legado classificado pelo passo 5. O leitor verifica forma, proveniência humana, catálogo, hash do conteúdo e espelhos. O principal direto é projetado para o DTO; secundários/contextuais não se tornam principais. Tópicos são exatos, sem expansão implícita de pai/filho. A leitura compatível de campos canônicos singulares legados continua disponível; isso não os transforma em nova revisão confirmada.

`component_id` opcional em `POST /api/v1/quiz/generate` delimita treino `STANDARD` a uma ocorrência do edital. O botão da disciplina envia seu ID local; a mesma disciplina em outra etapa conserva sua grade. A busca por disciplina usa o ID canônico confirmado, mesmo que o rótulo textual original da questão seja diferente. General shuffle e filas pessoais continuam independentes da aba arquivada do planner.

Decisões são consideradas apenas para o proprietário e as revisões atuais do conteúdo, edital e catálogo. A de maior revisão prevalece por componente; em empate, exclusão prevalece. `EXCLUDED` bloqueia a questão naquele componente. `ANALOGOUS` fica fora das quotas diretas, sem mistura automática com treino/simulado canônico. Ausência de decisão explícita mantém a interseção normal entre principal e tópico ativo. Exclusão em um edital não elimina aprendizagem global nem impede uso legítimo em outro.

Simulado atende quotas com questões distintas e informa insuficiência. Tronco comum considera somente concursos ativos e permite a questão quando há ao menos um componente atual elegível. Nenhum desses caminhos duplica questões no banco, eventos, XP ou SRS.

## Passo 6: conhecimento e histórico

`LearningRecord` mantém os totais e eventos históricos. Acrescenta `knowledge_projection_version=1` e `knowledge_evidence` separado. `services/knowledge.rs` produz a mesma projeção usada pelo endpoint de aprendizagem, cobertura do edital, prontidão e tronco comum. O Android usa o bloco confirmado, sem reinterpretar todos os acertos históricos como acertos de um tópico recém-vinculado. Contrato do DTO: `shared/schemas/knowledge-projection.schema.json`.

A evidência exige revisão do conteúdo correspondente à tentativa, ID de tentativa distinto e principal direto revisado. Um evento sem revisão verificável preserva os totais globais, mas não concede domínio canônico. No legado singular, a contribuição exige recibo de revisão do operador compatível com o conteúdo e vínculo atuais. Reutilizar uma tentativa em dois concursos é uma projeção; duas tentativas reais da mesma questão continuam sendo dois eventos. Não há crédito integral por vínculo secundário, por assunto pai ou por prefixo do ID.

A projeção calcula acurácia e confiança somente sobre eventos compatíveis, com a regra de domínio existente. O indicador SRS de domínio só é reaproveitado quando todo o conjunto de tentativas que o originou corresponde ao conteúdo verificado; evidências parciais não herdam o domínio de versões desconhecidas. Não são alterados intervalos ou resultados históricos. O gráfico de acertos por disciplina continua explicitamente histórico; cobertura/prontidão usa conhecimento confirmado.

Uma resposta pendente da mesma versão de conteúdo não é recusada apenas porque a classificação mudou enquanto o aparelho estava offline. O servidor mantém a autoridade do gabarito e registra separadamente os IDs canônicos enviados pelo cliente. `exam_component_id` da sessão é preservado na fila e no evento. Esse contexto adicional não modifica o hash histórico de idempotência/ACK. Reenvios de eventos aceitos continuam recebendo o recibo original, sem nova tentativa.

## Cache e migração Android

`POST /api/v1/questions/cache-state` é somente leitura e aceita até 200 IDs por página. Retorna o DTO atual para cada questão elegível ou null para indisponível, e decisões do proprietário vinculadas à revisão. A sincronização atualiza também questões nunca praticadas que estejam em cache. IDs indisponíveis são preservados localmente, mas retirados das ações. Metadados por conta guardam restrições; nenhuma fila é apagada.

O offline respeita esses metadados, contexto, revisão e prazo de revisão conhecido. Sem conexão, alterações remotas posteriores à última sincronização não podem ser conhecidas; a tela mantém a data da última sincronização. Sessões já iniciadas conservam seu snapshot, sem trocar enunciado ou resposta sob o usuário. Respostas pendentes incompatíveis permanecem revisáveis, sem exclusão como contorno.

Room 4→5 acrescenta apenas `pending_attempts.examComponentId` nullable. Migrações 1→2→3→4→5 permanecem encadeadas. Não há recriação de banco, limpeza de filas ou mudança de assinatura. O protocolo de tentativas continua 2, com campo contextual opcional. Abertas são armazenáveis/classificáveis, mas sua execução/correção ainda não é habilitada no cliente objetivo.

## Passo 7: validação e publicação

Testes cobrem IA falsa, limites e reutilização de geração, aceitação/rejeição/adiamento, recuperação e concorrência; seleção por componente, exclusão e analogia; uma tentativa reutilizada em dois concursos; histórico sem revisão; edição de conteúdo, cache obsoleto e reenvio/ACK. JVM cobre projeção, contexto, prazo e deduplicação. Emulador cobre migração desde Room 2, reabertura e persistência do componente, além dos fluxos já existentes.

Antes/depois de produção devem comparar questões e concursos em bytes, pois esta publicação não classifica lotes reais, e comparar o histórico protegido. Validadores/índices/snapshot permanecem conferidos. O pipeline existente executa gates, build e assinatura fora da VPS, ensaio de rollback e promoção somente do Cogniva. Saúde, revisão, catálogo, modos pessoais, APK servido versus bundle e serviços vizinhos precisam passar no aceite externo. Evidências finais serão registradas após a conclusão; testes em emulador não comprovam instalação no telefone físico.
