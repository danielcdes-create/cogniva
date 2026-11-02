# Etapa 3 — backend e MongoDB

Implementação em validação para 1.4.5/build 12. Acrescenta suporte persistente ao contrato v2 sem converter automaticamente o acervo. O cliente atual continua no protocolo de tentativas 2; questões abertas são armazenáveis e validáveis, mas não são entregues a esse cliente como objetivas.

## APIs e escrita

- `GET /api/v1/catalog/knowledge`: catálogo normalizado versionado, com sessão autenticada.
- `GET /api/v1/admin/questions/v2/:id`: documento completo em Extended JSON, com autorização administrativa.
- `POST /api/v1/admin/questions/v2`: criação/atualização validada. Corpo: `operation_id` UUID, `expected_revision`, `dry_run` (padrão `true`) e `document` conforme o schema.

A revisão do documento deve avançar exatamente uma unidade. Classificação e validade têm revisões próprias. O backend valida JSON Schema com formatos, hash JCS/SHA-256, gabarito, referências ao catálogo, principal/secundário, proveniência humana, validade normativa, prazos, rubricas e motores de correção. Armazenar um schema de correção aberta não executa uma correção nem credita XP/SRS.

Os campos canônicos singulares são espelhos do principal confirmado e permanecem utilizáveis pelos seletores antigos. Vínculos secundários não são promovidos a principais. Atualizações v2 preservam campos legados desconhecidos, origem já registrada e recursos de IA quando o conteúdo permanece igual. Conteúdo alterado arquiva o estado anterior na auditoria e retira caches antigos do documento corrente, impedindo sua reutilização para a versão nova. Alteração concorrente de cache também participa da guarda dessa operação.

Criação aceita os campos adicionais do documento; na atualização, o endpoint gerencia o núcleo v2 e preserva extras existentes. Recursos pedagógicos continuam geridos pelo fluxo de IA. Proveniência humana alterada recebe ator e instante do servidor. O endpoint legado de vínculo canônico continua disponível para documentos legados; para v2 ele exige o endpoint versionado, evitando divergência entre espelhos e classificação.

Idempotência reserva `operation_id` por administrador em `question_commands`, com hash do comando. CAS compara revisão e campos relevantes. O documento guarda uma outbox de um evento antes do ACK; a auditoria é persistida com majority/journal e a outbox é removida somente depois. Falha deixa o evento recuperável. Repetir o comando não duplica a gravação; reutilizar seu ID com outro conteúdo é conflito.

## Preparação do Mongo

O binário pronto executa `--prepare-question-storage`. Não há compilação na VPS. A promoção chama essa operação antes de abrir as rotas de dados, após o ensaio de rollback.

A operação:

1. Confere os documentos existentes contra o validador proposto, sem reescrevê-los.
2. Instala validadores BSON com `validationAction=error`; preserva regras anteriores ao compor a restrição.
3. Mantém o ramo de documentos legados e aplica o schema novo a `schema_version=2`.
4. Cria oito índices, sem excluir os índices existentes.
5. Persiste a versão editorial imutável do catálogo e recibos da instalação.
6. Recupera eventos pendentes de auditoria.

Coleções validadas: `todasQuestoes`, `question_classification_suggestions`, `question_exam_compatibility` e `answer_evaluations`. Suporte operacional: `cogniva_schema_versions`, `knowledge_catalog_versions`, `question_revision_events` e `question_commands`.

Os índices abrangem tópico legado; disciplina/tópico classificados e validade; estado/prazo/tipo; sugestões por questão/estado; compatibilidade por proprietário/concurso/componente/questão; avaliações por proprietário/tentativa/data; auditoria por questão/revisão; catálogo por identidade/versão, este último único. Índices de avaliações não são únicos por tentativa, pois uma tentativa admite eventos de revisão distintos.

JSON Schema 2020-12 é convertido para o subconjunto BSON: referências locais são expandidas, constantes e condicionais são traduzidas, tipos numéricos/booleanos e `_id` são adaptados. O backend Rust continua responsável por formatos e regras semânticas entre campos; Mongo não é apresentado como verificador automático de vigência jurídica. Recibos detectam mudanças de validador fora da migração e impedem substituir silenciosamente outra versão do schema.

## Leitura compatível

Documentos antigos continuam legíveis, sem presumir que ausência de classificação ou revisão normativa signifique confirmação. Documentos v2 são validados antes da projeção para o DTO legado. Consultas canônicas reconhecem os espelhos e os vínculos estruturados com `$elemMatch`. Questões arquivadas, obsoletas, em revisão, de contexto histórico ou com prazo vencido ficam fora da prática corrente. Uma validade nova `UNKNOWN` não remove uma restrição normativa legada.

Questões abertas ficam fora do protocolo objetivo atual. Seu gabarito não recebe índice fictício. A origem desconhecida não é rotulada automaticamente como IA. A leitura e o envio de respostas usam as mesmas regras de compatibilidade; recibos de tentativas já confirmadas continuam recuperáveis antes da nova verificação.

## Limites de escopo

Não há classificação automática nem migração em massa de questões. Aprovar sugestões de IA exige o fluxo de revisão da etapa de classificação; o endpoint genérico não simula essa aprovação. O cadastro Android associado é tratado na [etapa 4](ETAPA-4-CADASTRO-CATALOGO.md); execução de correção de abertas continua posterior. A versão de catálogo fica em snapshot imutável. Os templates originais são preservados; a etapa 4 amplia a projeção de disciplinas/tópicos do endpoint legado usando o catálogo normalizado.

## Validação

Testes Rust com Mongo isolado verificam preparação repetível, índices, rejeição de documento inválido pelo próprio Mongo, leitura legada, espelhos e vínculo estruturado, preservação de campos extras/cache/histórico, criação e edição versionadas, replay, CAS concorrente, cache retirado com cópia histórica e recuperação da outbox. Fixtures Python e Rust compartilham hashes JCS de múltipla escolha, certo/errado e abertas.

O aceite de produção deve comparar backups antes/depois, conferir os validadores/índices e catálogo efetivamente instalados, saúde, versão, assinatura e bytes do APK. Nenhuma questão real precisa ser alterada para comprovar a instalação.
