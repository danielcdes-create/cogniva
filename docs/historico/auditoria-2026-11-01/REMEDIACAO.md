# Recuperação da auditoria de 2026-11-01

Escopo autorizado: C01–C39 de `auditoria/2026-11-01/AUDITORIA-COGNIVA.md`.
As notas renomeadas pelo usuário e o histórico de produção são preservados.

## Execução

- [x] Identidade autenticada, isolamento e preservação de filas por proprietário.
- [x] Tentativas duráveis, validação autoritativa, idempotência e ACK por evento.
- [x] Concurso persistido, versionado e sincronizado nos dois sentidos.
- [x] Contratos únicos de domínio, cobertura, métricas, simulado e SRS.
- [x] Sessão persistente, operações assíncronas e reporte durável.
- [x] TLS/distribuição, CI com gates, promoção/rollback e documentação.
- [x] Suítes atuais, cenários adversariais e build Android verificados.

Resultado atual: **1.4.7/build 14**, revisão `41d976ee7bf0bab77ea216dc53980c08d01c1ac6`, promovida em 02/11/2026 às 22:59:25 (UTC−03:00), com [pipeline e aceite externo](https://github.com/danielcdes-create/cogniva/actions/runs/41561247262) aprovados. [Automação finita do legado](CLASSIFICACAO-LEGADO-SOB-DEMANDA.md) ativa por decisão do proprietário, sem revisão humana fictícia. Os originais, concursos e histórico protegido permanecem preservados. As seções seguintes são cronológicas; estados intermediários não representam a release atual.

Não executar os antigos probes como testes de aceite: eles afirmam o comportamento defeituoso. Os cenários serão incorporados à suíte com os resultados corrigidos. Testes de banco usam apenas uma instância isolada/sintética.

## Registro histórico: estado intermediário

- Branch de trabalho: `codex/auditoria-convergencia`; sem publicação de código nem escrita no Atlas até este ponto.
- Backend 1.4.0: sessão verificada, vínculo histórico por código de uso único, identidade nativa própria; escrita de evento/projeção/recibo no mesmo documento, compare-and-swap e preservação de campos legados; concurso persistido e versionado por usuário. A suíte de 22 testes passou, incluindo MongoDB real isolado em loopback 27048, falha de escrita injetada, retry, concorrência e restart.
- Android 1.4.0/build 7: migração Room 2→3 aditiva, pendências legadas em revisão, banco separado por conta; nova sincronização, cache semântico, histórico local verdadeiro e sessão persistida. Compilação e novos testes ainda em validação; não declarar esta fase concluída.
- Catálogo compartilhado em `shared/canonical-catalog.json`, com modelos explicitamente não oficiais e sem datas/cortes inventados. Não se remapeiam questões antigas por substring.
- Notas de arquitetura e de screenshot lidas integralmente após orientação do usuário. Preservar Paparazzi 1.3.5/compileSdk 35/DeviceConfig.PIXEL: geometrias arbitrárias quebram o renderer. C36 será resolvido pela descrição honesta da cobertura e prova no emulador, não por repetir esse caminho quebrado.
- Temurin 21 portátil verificado por SHA-256 em `%TEMP%/cogniva-temurin21`; caminho/versão em `remediation-validation/jdk-runtime.json`. JBR 25 não é usado nos gates.
- Mongo de teste portátil, sem serviço instalado e sem conexão com Atlas, metadados em `remediation-validation/mongo-runtime.json`; PID em `mongo.pid`. Encerrar somente esse processo ao concluir.
- A auditoria histórica possui cópia dos 74 arquivos auditados em `auditoria/2026-11-01/source-snapshot`, todos conferidos com os hashes da captura original.
- SSH existente para root@198.199.65.103 funciona com verificação de host. Somente inspeção realizada: projeto Compose `apps`, `/opt/apps/docker-compose.yml`, outros serviços FIN/Asbuildo/Spamshield presentes. Não sobrescrever esse Compose global com placeholders do repositório.
- GitHub CLI autenticado no repositório; há segredos DO_HOST/DO_USER/DO_SSH_KEY. Não executado push/deploy ainda.
- HTTPS por IP é suportado pelo Let's Encrypt/Certbot >=5.4 com `--ip-address` e perfil `shortlived`; usuário forneceu IP público. Preparar renovação automática e bootstrap sem build na VPS. Não há certbot instalado no servidor na inspeção.
- Restam integração Android, isolamento/edição de sessões e pendências, política completa de nota/grade, rate limits/tutores, CI/promoção/rollback/assinatura/TLS e documentação final por C01–C39.

## Validação local antes do CI

- Rust: 23 testes passaram no checkout atual, inclusive router real com Mongo sintético dedicado: ACK após falha, retry/concorrrência, ocorrência, isolamento, restart, CAS/revisões de concurso, publicação idempotente, seleção pela grade, métricas de 90 dias, dry-run/aplicação canônica, vínculo e renovação.
- Android: 24 testes JVM de repositório/domínio passaram; build release e compilação dos instrumentados concluídos; lint sem erros (avisos de versões/target preservados conforme as notas).
- Visual: sete variantes passaram em duas execuções reais; controle negativo com PNG 1×1 válido falhou na comparação; golden restaurado ao SHA-256 registrado em `remediation-validation/visual-gate.json`. Só Font Scale 2.0 recebeu atualização revisada: diferença de dois pixels na altura/posição do CTA, sem mudar geometria do renderer. O golden deve permanecer limpo após commit.
- Backup privado somente leitura concluído: 618 questões, 15 documentos/27 eventos históricos, quatro perfis. Hash protegido em `production-before.json`. Parser atual validou todos os documentos tipados; 612 questões têm gabarito interpretável. Seis inválidas e zero vínculos canônicos confirmados são preservados, sem preencher respostas/tópicos por inferência.
- HTTPS real instalado, testado da máquina local e na VPS, com timer horário de renovação. Nginx usa IPs públicos para preservar o listener 443 do Tailscale.
- Chave privada de release e prova de rotação preservadas fora do repositório, com Secrets de CI configurados. API 26–27 mantém limitação do assinante antigo; API 28+ usa rotação.
- Pipeline preparado com gates, bundle imutável, imagem por digest/tamanho medido, ensaio de rollback, gate de manutenção durante migração de protocolo e alteração somente do serviço Cogniva. Actionlint passou. CI/emulador/promoção ainda pendentes; não declarar entrega integral.

## Primeiro CI e fechamento dos casos de conflito

- Run `41339873013`, revisão `3be5bfb`: Android/JVM/lint/visuais passaram e o emulador API 35/Android 15 passou três testes reais (insets, migração Room 2→3 e sessão/reabertura). Log: `ci-emulator-first.log`; geometria 1440×3088/500dpi, cutout ativo; top bar 136 ≥ inset 136, CTA 2975 ≤ 3088−75.
- O gate backend falhou por conectividade ao Mongo sintético do job. Corrigidos bind explícito, cache de memória e espera de ping; nenhuma imagem foi promovida nesse run.
- Complementos C15/C26/C30: tronco comum offline considera todos os concursos ativos e seus tópicos; grade arquivada não volta à fila; bancas desconhecidas não são tratadas como filtro confirmado. Exportação inclui edições de concurso e cópias de conflito. Há ação explícita para preservar a edição antes de carregar o servidor; ACK atrasado mantém a edição feita durante o envio. Mudanças em fila/erro/concursos atualizam a UI.
- Suíte local posterior: 27 testes JVM de domínio/repositório e 23 Rust passaram. Emulador de CI agora aponta explicitamente a API para loopback indisponível, para não criar contas nem sincronizar dados contra produção. A assinatura rotacionada conserva somente a capacidade necessária de atualizar dados instalados; privilégios do assinante antigo foram revogados.
- Novo CI na revisão seguinte ainda precisa passar antes da promoção. Build e promoção continuam bloqueados por qualquer gate vermelho.

## Revisão final dos gates e do legado

O log do segundo CI (`41341270181`) confirmou que o Mongo 8.0 falha na inicialização por incompatibilidade da checagem de kernel do runner Ubuntu ([SERVER-136848](https://jira.mongodb.org/browse/SERVER-136848)). Android/lint/golden e emulador passaram; build/promoção permaneceram bloqueados. O CI agora fixa Mongo 7.0.41, além da validação local com Mongo 8.0.34, sem contornar a proteção do Mongo. O run seguinte enfileirado foi cancelado antes de trabalho útil para incorporar a revisão final.

A revisão local final tem 24 testes Rust e 30 testes JVM de domínio/repositório aprovados. Foram acrescentados os contraexemplos de acurácia 99/101 = 98,02%, agenda antes/depois do vencimento com contadores de mutações reais, erro de escrita, revisão máxima/negativa, quantidade com overflow e data UTF-8. O agendador aplica CAS sobre a base lida e não sobrescreve uma tentativa concorrente.

No cache anterior ao DTO completo, os vínculos inferidos deixam de alimentar domínio, mantendo suas colunas originais preservadas. Dicas pré-resposta em falha de rede foram testadas contra marcador de gabarito. O alias legado exato `Sociologia e Psicologia Aplicadas` foi incluído para seleção de treino livre, sem atribuir tópico/identidade canônica à questão. A revisão dos seis gabaritos divergentes foi preservada em arquivo privado ao lado do backup; nenhum gabarito foi alterado em produção.

## Gates completos e alinhamento de calendário

Na revisão `762b31b`, o CI com Mongo 7.0.41 passou o backend (24 testes), Android/JVM/lint/golden e emulador (três testes). Os logs completos dessa revisão estão preservados em `remediation-validation/ci-*-762b31b.log`. A promoção foi cancelada antes de ocorrer para alinhar também o cálculo de urgência do tronco comum ao dia UTC−03:00 já usado pelo cliente. A mesma fixture de limite de dia agora exercita diretamente o helper de produção. Os 24 testes Rust passaram novamente. O código do cliente permaneceu igual nesta correção de calendário; a nova revisão repetirá todos os gates. Caches Gradle têm escrita habilitada nos workflows manuais/de release confiáveis.

## Entrega operacional concluída

A revisão `d8716db` repetiu e aprovou todos os gates. O CI compilou/assinou o APK release, construiu a imagem fora da VPS, mediu 25.808.311 bytes de runtime e promoveu por digest. O ensaio falhou deliberadamente apenas após os smokes e comprovou a recuperação da imagem/ponteiro anteriores antes da promoção efetiva. O Compose global e os outros três containers mantiveram seus hashes/IDs.

O aceite externo verificou HTTPS, versão/revisão/contrato 2, readiness/Mongo, duas leituras iguais de `/api/app/latest-apk`, download completo/hash/tamanho e 401 anônimo na rota protegida. APK de 12.708.853 bytes, SHA-256 `1abfb764909dd4274e351a2c30e3471ba41ab3b11842adf2dd1dc3389bf35c35`; assinatura privada rotacionada verificada em API 28–35 e ausência de `debuggable` confirmada.

O inventário posterior manteve 15 documentos/27 eventos e o mesmo hash protegido das duas capturas anteriores. Os seis gabaritos ambíguos e zero vínculos canônicos confirmados permanecem preservados para revisão humana; não houve remapeamento corretivo por inferência. Nenhum aparelho ADB foi conectado, portanto instalação/uso físico continuam sem prova. API 26–27 conserva a limitação do assinante antigo.

As chaves existentes de IA autenticaram em consultas de modelos. Gemini 1.5 não estava disponível; a configuração privada foi copiada para backup e ajustada explicitamente para `gemini-2.5-flash-lite`, cuja disponibilidade e suporte a geração foram confirmados. O container promovido recebeu essa configuração; não foi enviada geração paga. Renovação TLS ativa e serviço com resultado de sucesso. O Mongo sintético local foi encerrado com conferência de identidade do processo.

## Correção de Ajustes — 1.4.1/build 8

A captura do aparelho revelou compressão de ID/username pela linha ocupada pelo código, além da confirmação genérica distante, depois das metas. Os campos agora são empilhados e **Confirmar vínculo** tem envio/erro/sucesso próprios. O vínculo não grava rascunhos de nome/metas/concursos na conta de destino; **Salvar nome e metas** não consome o código.

Build/lint locais, dois verifies reais com nove imagens e controle negativo restaurado por hash passaram. O CI aprovou backend, Android e cinco testes instrumentados, incluindo os dois cenários de Ajustes com fonte normal/200%. Ensaio de rollback e promoção concluídos; o APK baixado externamente corresponde ao bundle assinado. Inventários antes/depois preservaram os mesmos 15 documentos/27 eventos e hash protegido, Compose e outros containers. Evidências e limites em `CORRECAO-VINCULO-1.4.1.md`.

## Recuperação das questões — 1.4.2/build 9

Pedidos reais reproduziram o bloqueio: Android herdava concurso arquivado até no shuffle e nas filas pessoais; HTTP 400 era convertido em mensagem genérica de grade insuficiente. O escopo foi corrigido em cliente/servidor, a seleção obsoleta é substituída no planner e os erros específicos são preservados. Aprendizagem passou a informar revisão pendente e disponibilidade da questão, alinhando contadores e seleção sem apagar referências.

25 testes Rust, 47 JVM, lint/nove imagens e seis instrumentados aprovados. O novo teste abre a tela real com uma questão de fixture e grava resposta/fila Room com contexto arquivado inicial. Na API de produção: shuffle 10, SRS 3, erros 3, desafio 4, também recebendo o contexto arquivado antigo. Rollback/promoção, download/hash/assinatura e prontidão aprovados. Acervo de 618 documentos e histórico global de 15 documentos/27 eventos mantiveram seus hashes antes/depois.

Quatro referências históricas sem documento no Mongo foram reconciliadas com IDs dos exemplos locais do código anterior; sete eventos preservados, sem reinserir esses exemplos como acervo de banca. A grade fiscal atual continua sem questões classificadas/vinculadas correspondentes; não se substitui matéria nem se infere tópico para preencher simulado/tronco. Relatório e evidências em `CORRECAO-QUESTOES-1.4.2.md` e `remediation-validation/1.4.2/`.

## Tutores xAI — 1.4.3/build 10

Modelo Grok 4.3 escolhido pelo proprietário após autenticação válida e ausência de acesso ao Grok 4.7 (catálogo e consulta direta 404). Todos os recursos preservam seus prompts e consultam primeiro o campo Mongo; geração ausente usa somente xAI Responses, confirma gravação majority/journal e guarda contra edição concorrente de enunciado/alternativas/gabarito. Cache hit deixa de consumir cota diária de geração.

26 testes Rust, 47 JVM, nove imagens/lint e seis instrumentados aprovados. Prova real: primeira solicitação gerou e gravou um mnemônico; segunda reutilizou conteúdo idêntico do Mongo. Só esse campo mudou entre os backups; todos os eventos históricos foram preservados. Configuração/chave privadas na VPS, com backup; nenhuma chave no Git/APK. Detalhes em [IA-XAI.md](IA-XAI.md).


## Gestão de concursos e componentes — 1.4.4/build 11

Disciplinas de todo o catálogo e personalizadas, etapa/grupo de conhecimentos, edição de nomes, pesos, quantidades e tópicos. Remoções deixam de ser reinseridas na grade pelo merge local/servidor; revisões anteriores já sincronizadas continuam preservadas. Em Ajustes, gestão própria com arquivados, restauração, exclusão confirmada e revisão de pedidos pendentes. Marcadores sincronizados impedem reaparecimento de concursos excluídos. Migração Room 3→4 sem descarte de dados.

27 Rust, 48 JVM, lint/nove imagens e nove instrumentados aprovados. Promoção em 02/11/2026 às 12:52 (UTC−03:00), rollback ensaiado, download/hash/assinatura e prontidão conferidos. Backups mantiveram 618 questões, quatro cadastros de concursos e todos os 20 documentos/32 eventos históricos iguais. [Detalhes e limites](GESTAO-CONCURSOS-1.4.4.md).

## Catálogo, Mongo v2 e componentes por etapa — 1.4.5/build 12

Etapas 1–4 concluídas: catálogo e contratos versionados, validação/escrita Rust, instalação aditiva de quatro validadores e oito índices com snapshot de catálogo, e associação Android de componentes/assuntos/subassuntos. A mesma disciplina pode aparecer em etapas distintas com IDs locais, pesos e quotas independentes. Personalizados aguardam associação sem perda de texto. Simulados evitam repetir questões e levam a identidade do componente para o resultado ponderado.

32 Rust, 36 contratos Python, 50 JVM, lint/nove imagens e dez instrumentados aprovados. Todos os seis jobs do run `41512550219` passaram. Promoção às 17:02:53 de 02/11/2026 (UTC−03:00), com rollback controlado verificado. API pública, catálogo autenticado normalizado/projetado e modos pessoais foram conferidos, sem enviar tentativas nem chamar IA paga.

Comparação privada antes/depois: 618 questões e quatro cadastros idênticos em bytes; 20 documentos/32 eventos e hash protegido idêntico. Recibos/validadores, oito índices e snapshot versão 1 comprovados. Compose e demais containers preservados. APK servido de 12.796.722 bytes corresponde ao CI e à assinatura/lineage existente; runtime de 29.011.594 bytes. Evidências em `remediation-validation/1.4.5/`. O Mongo sintético local foi encerrado após conferir executável, PID e porta.

O acervo continua sem reclassificação automática: o inventário de aceite ainda tem zero questões com vínculo canônico singular. A [nota técnica para Grok Build](NOTA-TECNICA-GROK-BUILD-PASSO-5.md) especifica as etapas 5–7: classificação assistida, seleção/progresso e validação/publicação. Respostas abertas continuam armazenáveis, com execução/correção no cliente ainda não habilitada. Prova de aparelho físico permanece pendente; emulador não a substitui.


## Catálogo e contratos — etapas 1 e 2

Definição versionada em 02/11/2026: sete troncos, 35 disciplinas, 49 assuntos, 174 subassuntos e 41 aliases de disciplinas. Preservados os 24 IDs de disciplinas e 26 de tópicos existentes, com registro de 265 identidades e vínculos herdados identificados. Fiscalização do Trabalho recebe um perfil próprio; AFO/Finanças Públicas e demais sobreposições não são fundidas por rótulo.

Contratos JSON Schema e validação semântica para documento de questão, sugestão de classificação, compatibilidade com edital e avaliação de tentativa. Incluem MC de cinco alternativas, certo/errado e abertas; status de validade atual/obsoleta/revisão/desconhecida, contexto histórico, proveniência, revisões, rubricas e avaliação semântica/xAI provisória. Gabarito, nota ou classificação não são confirmados por confiança do modelo.

Esta entrega prepara a integração da etapa 3. O catálogo usado em execução e os fontes de Android/Rust permanecem sem alteração; não houve migração do Mongo, nova publicação de APK ou inferência paga. [Catálogo](CATALOGO-CONHECIMENTOS.md), [contratos e regras](SCHEMA-QUESTOES-V2.md), [consulta completa](catalogo/CONHECIMENTOS.md).

## Classificação, seleção e progresso — 1.4.6/build 13

O passo 5 recebido foi auditado antes da publicação: prompt com candidatos legíveis, retomada idempotente, lease concorrente, recuperação do evento atômico, preservação da proposta original e comparação estrita. O passo 6 separa evidência canônica do histórico, aplica compatibilidade por componente e alinha servidor/cache Android. O editor de concursos mantém ações acessíveis com teclado e fonte ampliada. 37 testes Rust, 42 contratos Python, JVM/lint, nove imagens golden e 11 testes instrumentados aprovados; seis jobs verdes, rollback e promoção conferidos em 02/11/2026 às 21:55:36 (UTC−03:00).

Questões e concursos idênticos em bytes; histórico 20/32 e fingerprint preservados, assim como Compose e containers vizinhos. Não houve chamada paga nem lote aplicado no acervo. APK de 12.813.192 bytes, runtime de 30.272.160 bytes. [Registro completo e evidências](ETAPAS-5-7-CLASSIFICACAO-SELECAO.md). Sem aparelho ADB, instalação física permanece não verificada.

## Automação finita do legado — 1.4.7/build 14

Shuffle prioriza legadas não processadas; demandas de edital/tronco alimentam fila global de oito, até duas por pedido. Teto de 20 reservas/dia, uma oportunidade automática por ID, sem retry de falha/timeout. Lista congelada de 618, seis bloqueadas e inserções novas sujeitas ao schema v2, inclusive no Mongo. Proveniência AI separada da confirmação humana, CAS/snapshot original e recibos duráveis. 40 testes Rust, 47 contratos Python, JVM/lint, nove imagens golden e 11 testes instrumentados aprovados. A única chamada real gerou e persistiu uma proposta com confiança 0,80, abaixo do limiar 0,90: ficou PENDING_REVIEW. A observação posterior manteve uma única reserva, sem repetição automática; não foi forçado um novo sorteio para obter aplicação favorável. Inventário do aceite: 0 automáticas, 612 sem classificação e 6 ambíguas. [Política, operação e evidências](CLASSIFICACAO-LEGADO-SOB-DEMANDA.md).
