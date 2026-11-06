# Etapa 4 — componentes de edital e catálogo compartilhado

Concluída e publicada em 1.4.5/build 12, revisão `9dee93673f2e170e06f2d77ca378df973449414d`, [pipeline 41512550219](https://github.com/danielcdes-create/cogniva/actions/runs/41512550219), em 02/11/2026 às 17:02:53 (UTC−03:00). Publicação e aceite estão registrados em `remediation-validation/1.4.5/`. A etapa 3 está descrita em [ETAPA-3-BACKEND-MONGO.md](ETAPA-3-BACKEND-MONGO.md).

## Identidades e contrato

O componente é uma ocorrência de disciplina no edital. Seu identificador persistente é `local_subject_id`, usado como `component_id` nas decisões de compatibilidade. `canonical_subject_id` referencia conhecimento compartilhado e pode repetir em componentes do mesmo concurso. `stage`, `category`, `weight` e `question_count` pertencem a cada ocorrência; não pertencem à disciplina do catálogo nem ao documento da questão.

O tópico possui `local_topic_id` próprio e `canonical_topic_id` compartilhado. IDs locais são únicos no concurso. IDs canônicos de tópico são únicos dentro do componente, podendo repetir em outros componentes. O backend verifica que um tópico canônico pertence à disciplina escolhida. O contrato de transporte está em `shared/schemas/exam-component.schema.json`; as regras do agregado, incluindo soma de até 500 questões, continuam em `exam_store::validate`.

Um ID `custom-<UUID>` registra uma disciplina ou tópico personalizado aguardando associação. A ausência de associação não apaga nem impede salvar o componente. Seu nome não cria equivalência com um ID conhecido, e não promete questões disponíveis para aquela grade. O estado pendente é derivado da identidade personalizada; não há um segundo campo de status sujeito a divergência. `stage` ausente no legado equivale a string vazia, apresentada como “Etapa não definida”. Não foi necessária migração destrutiva de Room ou Mongo para essa identidade.

## Fluxo Android

1. Criar a partir de template, clone ou grade vazia; editar etapa e grupo por disciplina.
2. Buscar nas 35 disciplinas, incluindo nomes alternativos; adicionar a mesma disciplina mais de uma vez, com novos IDs locais.
3. Abrir o editor do componente e escolher explicitamente assuntos ou subassuntos. São exibidos nível e assunto pai. Não selecionar automaticamente todos os filhos nem inferir cobertura do pai.
4. Para um componente personalizado, usar “Associar disciplina ao catálogo”. Preservar nome, etapa, grupo, peso, quantidade e IDs locais. Tópicos que não pertencem à disciplina de destino mantêm seu texto e ficam com identidade personalizada pendente.
5. Usar “Associar tópico” para vincular um tópico local ao assunto/subassunto escolhido, mantendo nome e ID local. Também é possível acrescentar tópicos personalizados e arquivar, restaurar ou excluir os componentes da grade atual.

O catálogo normalizado é incluído no APK e atualizado pelo endpoint autenticado `GET /api/v1/catalog/knowledge`, com cache persistente por conta. Os templates legados permanecem preservados no arquivo original; a API de catálogo legado projeta as disciplinas/tópicos ativos do catálogo ampliado. O sétimo tronco é selecionável, sem inventar um edital oficial para ele. Seus vínculos editoriais de carreira não são uma grade obrigatória.

## Seleção e cálculo

Simulados atendem à quota de cada componente e excluem IDs de questões já sorteadas nos componentes anteriores. Se não houver questões distintas suficientes, retornam insuficiência; não repetem a questão para preencher a grade. O DTO recebe `exam_component_id` apenas no contexto da sessão; esse campo não classifica o documento do banco. O Android usa esse ID e o snapshot do concurso para aplicar o peso correto no resultado. O sorteio offline reconstitui a mesma associação por componente e rejeita cache insuficiente.

Prioridade de uma disciplina reutilizada soma os pesos normalizados de suas ocorrências; presença em duas etapas de um mesmo concurso não passa a contar como presença em dois concursos. Projeção de cobertura de um componente usa somente seus tópicos ativos e referências exatas, sem herança automática de assuntos pais.

O endpoint de prévia de nota ponderada aceita `local_subject_id` por resultado. Clientes antigos podem omiti-lo quando existe uma única ocorrência da disciplina; uma ocorrência ambígua é rejeitada. O endpoint legado de publicação que recebe somente tópicos não consegue identificar etapas repetidas: nesse caso exige atualização completa do concurso (`PUT /api/v1/exams/:id`). Essa atualização preserva todos os componentes e suas identidades.

## Preservação e limites

Alterações de grade são versionadas em `user_exams.revision_history`, com proprietário e comparação de revisão. Não alteram eventos de aprendizagem, respostas, origem de questões nem classificações do banco. Excluir um componente da configuração não elimina o histórico. Arquivar/excluir um concurso continua no gerenciamento dentro de Ajustes, com as confirmações existentes.

Classificar o acervo e aceitar propostas de IA pertencem ao passo 5. Execução de respostas abertas e sua correção semântica/por IA não são habilitadas por esta etapa; o backend já aceita e valida seu armazenamento, mantendo-as fora do protocolo objetivo atual.

## Validação

- Contratos Python, incluindo formas legadas, personalizadas e repetição canônica entre etapas.
- Rust com Mongo sintético: validação de identidade, associação ao catálogo completo, IDs locais duplicados, referências cruzadas inválidas, persistência/reabertura de duas etapas, quotas e questões distintas, isolamento e histórico preservado.
- JVM: associação preservando IDs/textos, catálogo completo offline, sorteio repetido sem duplicação e mapeamentos Room/API.
- Emulador: criar duas ocorrências da mesma disciplina pela tela, além dos testes existentes de etapa/grupo personalizados, ciclo de vida e durabilidade.

Os testes Mongo com `failCommand` devem executar com `--test-threads=1`, pois a injeção de falhas pertence ao servidor inteiro, mesmo com bancos sintéticos distintos. Não usar `MONGODB_URI` de produção para testes.

Resultado: 32 Rust com Mongo sintético, 36 contratos Python, 50 JVM, lint/nove imagens e dez instrumentados aprovados. Catálogo autenticado real: 35 disciplinas, 223 assuntos/subassuntos e sete troncos; projeção legada com as mesmas disciplinas/troncos e três templates preservados. Os quatro cadastros existentes e os 618 documentos de questão permaneceram idênticos em bytes; histórico protegido inalterado. APK release: 12.796.722 bytes, SHA-256 `0727c9a4dc2044e3599a43affe13a0766a9e59a924c13d29c40cc3ef537d4cc4`; assinatura privada/lineage conferida. Runtime medido: 29.011.594 bytes. Prova nativa em emulador API 35/Android 15; instalação no aparelho físico não foi verificada.
