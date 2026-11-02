# Recuperação e uso do banco de questões — 1.4.2/build 9

Estado: 25 testes Rust aprovados no Mongo sintético e 47 testes JVM aprovados; build release, lint e compilação dos instrumentados concluídos. Gates de emulador/publicação em acompanhamento. O aceite operacional será registrado após a promoção.

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
