# Recuperação e uso do banco de questões — 1.4.2/build 9

Estado: **1.4.2/build 9 em produção**, revisão `7d623102e17723e4f2eeff82f1e191f08348b189`, promovida em 01/11/2026 às 21:26 (UTC−03:00). [Run 41365834469](https://github.com/danielcdes-create/cogniva/actions/runs/41365834469) com todos os cinco jobs aprovados. Aceite externo e consultas de seleção na API real concluídos.

## Diagnóstico confirmado

A API em produção devolvia dez questões para shuffle geral, mas rejeitava a mesma modalidade quando recebia o concurso arquivado selecionado no planner. O Android acrescentava esse concurso a todos os modos e substituía qualquer HTTP 400/409/422 por “não há questões suficientes para esta grade”. Isso impedia shuffle, SRS, erros e desafio, apesar de existirem questões elegíveis no MongoDB.

As grades CPNU, RFB e BNDES da conta investigada estavam arquivadas, mas continuavam nas abas de treino; a seleção persistida podia permanecer em uma delas. A grade fiscal criada pelo usuário estava ativa. O acervo principal contém 618 documentos classificados em sete disciplinas do CPNU/trabalho/conhecimentos gerais, sem rótulos de Finanças Públicas, Contabilidade, Tributário ou Português. `user_questions` contém referências e histórico de acertos; `dataLogger` contém sessões, não um segundo acervo fiscal. Não há base para misturar essas questões com os tópicos da grade fiscal.

Também havia divergência entre a seleção e os contadores: o servidor considerava `revisao_necessaria`, mas o pull Android não recebia essa informação. O cliente contava referências históricas cujo enunciado/gabarito não estava disponível para praticar. Histórico e disponibilidade para ação são informações distintas e devem ser preservados.

## Tratamento

- Shuffle geral e filas pessoais ignoram a aba do planner, inclusive em solicitações de versões anteriores do Android. Questões de outros usuários não entram nas filas pessoais.
- Treino explicitamente filtrado por concurso/disciplina e simulado continuam respeitando a grade e o arquivamento. Simulado exige suas quantidades e vínculos válidos; o tronco comum usa as grades ativas. Ausência de conteúdo não autoriza inferir vínculos nem usar questões de outra disciplina.
- O planner lista concursos não arquivados e substitui a seleção obsoleta por uma seleção disponível. Concursos arquivados continuam editáveis em Ajustes; não são reativados automaticamente.
- O servidor conserva todos os registros de aprendizagem e acrescenta `review_required` e `practice_available` ao pull. A disponibilidade usa o mesmo filtro de validade, gabarito e reportes críticos da seleção. O contador Android e a seleção offline consideram esses campos; respostas novas limpam a marcação anterior de revisão.
- Erros de autenticação, concurso arquivado, contrato ou grade deixam de ser convertidos indiscriminadamente em insuficiência. Cache é contingência para conexão/servidor indisponível, mantendo a semântica do modo; questões sabidamente indisponíveis não são reutilizadas no cache. Registros sem enunciado local orientam a recuperar o cache pela conexão.

## Validação

O teste Rust usa Mongo real isolado: documentos legados sem tópico canônico, um gabarito inválido, dois proprietários e concurso arquivado. Exercita quatro modos, DTO/revisão, leitura de aprendizagem, rejeição correta de treino/simulado arquivado e igualdade dos documentos antes/depois das consultas. Os 25 testes passaram.

Os testes Android verificam escopo global, preservação do erro real, revisão marcada antes do vencimento, exclusão de referências indisponíveis das ações e retenção das métricas históricas. O teste instrumentado percorre busca → cache Room → sessão persistida → tela real da questão → resposta na fila, com concurso arquivado na seleção original e questão sem tópico canônico. Usa somente fixtures locais.

`scripts/probe-question-selection.py` consulta a API real com credencial diagnóstica temporária mantida em memória na VPS. Não envia tentativas nem publica tokens, enunciados ou eventos. O relatório anterior está em `remediation-validation/1.4.2/selection-before.json`; o inventário sanitizado das coleções em `mongo-question-inventory.json`. Backups BSON privados ficam fora do Git; fingerprints em `production-before-1.4.2.json`.

## Resultado operacional

25 testes Rust com Mongo isolado, 47 testes JVM, lint e nove screenshots aprovados. Emulador API 35/Android 15: seis testes aprovados. `QuestionPracticeTest` abriu a questão legada na tela real e persistiu uma resposta na fila Room, com concurso arquivado no contexto original e retorno menor que a quantidade solicitada. Não houve instalação física por ADB.

Consultas reais para a conta investigada, sem enviar respostas:

| Modalidade | Sem filtro de concurso | Contexto arquivado enviado pelo app antigo |
|---|---|---|
| Shuffle | HTTP 200, 10 questões | HTTP 200, 10 questões (antes: 400) |
| SRS | HTTP 200, 3 questões | HTTP 200, 3 questões (antes: 400) |
| Caderno de erros | HTTP 200, 3 questões | HTTP 200, 3 questões (antes: 400) |
| Desafio | HTTP 200, 4 questões | HTTP 200, 4 questões (antes: 400) |

Os oito registros de aprendizagem da conta continuam disponíveis no pull; quatro têm questão disponível no acervo. Os contadores acionáveis SRS/erros agora coincidem com as três questões que a seleção oferece. Os registros indisponíveis permanecem nas métricas e no histórico.

Quatro IDs sem documento em `todasQuestoes` correspondem exatamente aos quatro exemplos de `buildOfflineSeedQuestions()` na fonte histórica congelada (linhas 890–961). Seus sete eventos foram preservados. `dataLogger.questoes_detalhadas` contém somente referências/acertos, sem enunciados; a origem desses quatro exemplos foi encontrada no código, não em um acervo Mongo perdido. Não foram importados como questões de banca nem receberam vínculos inferidos. Evidência: `legacy-example-reconciliation.json`.

As quatro disciplinas da grade fiscal ativa retornam zero questões. Simulado retorna insuficiência específica, por exemplo Tributário 0/15; tronco comum retorna conjunto vazio por ausência de questões com vínculo confirmado para as grades ativas. Esses limites são de acervo/classificação, não indisponibilidade geral do banco. Os seis gabaritos ambíguos do acervo continuam preservados para revisão.

O ensaio registrou `CONTROLLED_ROLLBACK_VERIFIED` e promoção efetiva. Runtime: 25.814.263 bytes, dentro do limite de 50 MB. HTTPS/readiness/Mongo/revisão conferidos, rota anônima protegida retorna 401, metadados repetíveis e APK baixado idêntico ao bundle assinado. Pacote/versão/assinatura conferidos; sem `debuggable`. APK: 12.725.237 bytes, SHA-256 `7f89a88d56d1790bab06274e539b59336c94586fafbdd889520fc4e5c2ff209a`. A inspeção do DEX confirma o novo escopo e os campos de aprendizagem no artefato servido; testes nativos não entram no APK release.

Antes/depois: mesmos 618 documentos do banco de questões, BSON SHA-256 `5fdf9ef5772a1a50d7501b30dd5158e0f441d8efb69e41af6ae56c2af36f4d0a`. Os 15 documentos/27 eventos globais mantiveram o hash protegido `a34574d29433a312c494026a3c9fc84291bde7fbe05783675caf79a10caf50c7`. Compose e demais containers preservados; renovação TLS ativa. Teste Mongo local encerrado após verificar executável/porta. Evidências adicionais em `selection-after.json`, `native-summary.json`, `production-release.json`, `question-bank-preservation.json`, logs `ci-*.log` e inventários `production-pre-promotion-1.4.2.json`/`production-after-1.4.2.json` no diretório pai.
