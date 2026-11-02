# Tutores xAI e cache por questão

Migração em validação para 1.4.3/build 10. Modelo escolhido pelo proprietário: `grok-4.3`; a chave autenticou, mas `grok-4.7` não constava no catálogo e sua consulta direta retornou 404. Uma chamada curta real ao Grok 4.3 concluiu com sucesso. Credencial somente no ambiente privado da VPS, com backup de configuração fora do Git; nenhum segredo é incluído no APK.

Os oito recursos usam a Responses API xAI e os prompts pedagógicos existentes: explicação, dicas contextuais, Dr. Sincero, simplificação pré-resposta, explicação infantil posterior, conceitos extras, mnemônico e plausibilidade. O servidor consulta primeiro o campo próprio do documento `todasQuestoes`; conteúdo existente é reutilizado, inclusive cache legado. Não há geração automática de todos os documentos.

Quando falta o recurso, o servidor valida o gabarito, reserva a cota de geração e chama o modelo. Somente texto de resposta concluída e não vazia é persistido no campo correspondente. Raciocínio interno não entra no cache. Escrita usa confirmação majority/journal, verifica que o enunciado/alternativas/gabarito não mudaram durante a chamada e propaga falhas de armazenamento. Recursos existentes e campos legados são preservados. Plausibilidade usa `plausibilityMarkdown`; dicas usam posições estáveis no array `contextualHint`.

Chamadas concorrentes para a mesma questão/recurso são serializadas no processo e releem o Mongo. Cache hit não consome cota diária de geração. O limite de geração por conta permanece 120/dia. Timeout do provedor: 40 segundos, abaixo dos 45 segundos do cliente Android. Falhas, respostas incompletas ou vazias não são persistidas como conteúdo pedagógico. Nenhum outro provedor é chamado como contingência.

Validação: teste integrado com Mongo isolado e provedor HTTP sintético confirma uma geração em solicitações concorrentes, persistência no documento, reutilização posterior sem nova chamada e preservação de campos não relacionados. Os 26 testes Rust passaram. Publicação e prova real de geração/cache no documento ainda pendentes.
