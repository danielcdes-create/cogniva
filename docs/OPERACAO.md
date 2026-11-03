# Operação e recuperação do Cogniva

Automação finita do legado: [shuffle, fila limitada, orçamento persistente, monitoramento e pausa](CLASSIFICACAO-LEGADO-SOB-DEMANDA.md). O aceite autenticado que sorteia questões pode acionar classificação paga quando `COGNIVA_LEGACY_AUTO_CLASSIFY=1`; executar os probes gerais antes da ativação. `--legacy-classification-status` apenas lê contagens e configuração.

## Limites e credenciais

A VPS executa somente imagens prontas. Não compilar Rust, Docker, npm ou JVM nela. O Compose deste repositório é um exemplo independente; não substitui o arquivo compartilhado `/opt/apps/docker-compose.yml`.

A promoção usa o override `/opt/apps/cogniva-runtime.override.json`, serviço `api-rust-core`, projeto `apps`, porta local 8001. FIN, Asbuildo e Spamshield são conferidos por ID antes/depois. Limites iniciais: 160 MB, 0,75 CPU, 128 processos, runtime somente leitura, logs 10 MB × 3. São limites, não medições de consumo.

JWT, URI Mongo e chaves de IA ficam no ambiente privado. Não registrar seus valores. A chave Android e senha DPAPI ficam em `%LOCALAPPDATA%/Cogniva/release-signing`, com ACL privada. Preservar essa pasta em backup privado. O CI usa Secrets e apaga credenciais temporárias.

Configuração atual: `XAI_API_KEY` privada, `XAI_BASE_URL=https://api.x.ai/v1`, `XAI_MODEL=grok-4.3` em `/opt/apps/.env.rust`. Chave autenticada e geração/cache testados. Grok 4.7 não está habilitado nessa conta; o proprietário escolheu 4.3. Variáveis antigas Gemini/OpenAI não são usadas para nova geração. Recriar o container de forma controlada após alterar o ambiente. Cache legado não é regenerado pela troca de provedor.

O operador emite `docker exec api-rust-core /usr/local/bin/app --issue-link-code ID` depois de comprovar o proprietário. O código vale dez minutos e vincula uma instalação comprovada. Repetição serve apenas à mesma instalação para recuperar resposta perdida. Não publicar códigos em issues/logs. ID ou @username não comprovam posse. `ADMIN_USER_IDS` vazio não concede administração.

No Android 1.4.1, usar **Ajustes → Recuperar conta e histórico → Confirmar vínculo**. Os três campos ficam separados e há retorno no próprio formulário. **Salvar nome e metas** é independente. Uma instalação já vinculada não precisa de novo código para atualizar o APK.

## Preservação do banco

`scripts/production-history.py --backup --label before` faz apenas consultas de leitura através de SSH confiável. BSON, índices e definições de views ficam fora do Git, em `%LOCALAPPDATA%/Cogniva/production-backups`. O inventário versionado contém somente contagens e hashes. Uma captura com escritores ativos não é backup transacional: conferir os fingerprints imediatamente antes/depois da promoção.

O hash protegido cobre identidade, proprietário, questão, `pastSessions` e nota. Não apagar/recriar eventos para corrigir sincronização. Agendador pode alterar projeções de revisão/decaimento, sem alterar eventos. Rollback de imagem não restaura banco automaticamente, pois isso apagaria escritas legítimas posteriores.

## Gates e promoção

`deploy.yml`: manual ou tags `v*`, respeitando `[skip ci]`. Domínio/router, JVM/golden, lint e emulador precisam passar na mesma revisão. Build e assinatura acontecem no CI, com JDK 21 e caches. Runtime maior ou igual a 50.000.000 bytes bloqueia promoção. Artifacts expiram em cinco dias; limpeza GHCR conserva cinco versões recentes e digests atual/anterior.

Release Android exige assinatura verificada, ausência de `debuggable`, hash/tamanho e versão medida do output Gradle. Rotação usa chave privada em API 28+. API 26–27 mantém o assinante antigo para permitir atualização sem reinstalar, conservando essa limitação de segurança nesses sistemas.

O bundle é movido para `/opt/apps/cogniva-releases/SHA`; bytes diferentes sob uma revisão existente são recusados. Imagem é referenciada por digest. Um symlink `/opt/apps/apk/current` troca APK e metadados juntos; o router resolve a release uma vez por leitura.

Durante a troca e o ensaio de rollback, Nginx bloqueia rotas de dados com 503 e mantém health disponível. Isso impede envio do protocolo novo ao backend antigo. O candidato precisa passar readiness/Mongo, revisão, APK e HTTPS. Ao abrir o gate, uma rota protegida deve rejeitar acesso anônimo com 401.

O pipeline provoca uma falha de smoke controlada, confirma imagem, ponteiro, Compose e saúde anteriores, e depois promove efetivamente. Falhas reais não contam como sucesso do ensaio. Entre protocolos incompatíveis, rollback mantém as rotas de dados fechadas até nova promoção válida; as filas ficam preservadas.

Release operacional atual: **1.4.7/build 14**, revisão `41d976ee7bf0bab77ea216dc53980c08d01c1ac6`, promovida em 02/11/2026 às 22:59:25 (UTC−03:00); run `41561247262`, seis jobs aprovados. Aceite repetível:

```powershell
python -X utf8 scripts/verify-production-release.py 41d976ee7bf0bab77ea216dc53980c08d01c1ac6 --version-name 1.4.7 --version-code 14 --xai-model grok-4.3 --baseline docs/remediation-validation/1.4.7/before-final/production-release.json --evidence-dir docs/remediation-validation/1.4.7
```

Faz somente leituras, baixa o APK para `%LOCALAPPDATA%/Cogniva/releases` e grava evidência sanitizada. As evidências anteriores permanecem preservadas. A entrega 1.4.7 tem 40 testes Rust, 47 contratos Python, JVM/lint, nove imagens golden e 11 testes instrumentados aprovados, quatro validadores/oito índices/catálogo conferidos e guard adicional restringindo documentos legados aos 618 IDs. [Aceite e automação ativa](CLASSIFICACAO-LEGADO-SOB-DEMANDA.md). Probes de seleção podem gerar custo com a automação ativa; a verificação de release acima não seleciona questões.

Diagnóstico de questões para uma conta cujo proprietário foi comprovado: `python -X utf8 scripts/probe-question-selection.py ID --output caminho-do-relatorio.json`. O operador assina sessão diagnóstica temporária em memória na VPS; o relatório contém somente contagens, disciplinas e estados HTTP. Não envia tentativas, tutores pagos ou comandos de edição. `--assert-fixed` verifica os casos conhecidos da conta investigada nesta recuperação. Referências históricas indisponíveis devem ser revisadas por identidade/proveniência, sem apagá-las nem criar enunciados para preencher IDs. Quatro referências foram identificadas como exemplos locais antigos, com evidência na reconciliação da 1.4.2.

## Recuperação e TLS

`previous.json` da release registra imagem/ponteiro anteriores. Recuperar somente `api-rust-core` com os dois arquivos Compose. Não derrubar o projeto compartilhado. Conferir `ROLLBACK_HEALTHY`, imagem efetiva e atendimento HTTP. Não desinstalar o aplicativo, limpar Room nem remover filas como contorno.

Certificado IP Let's Encrypt short-lived obtido por HTTP-01 com Certbot 5.4 precompilado. Nginx escuta nos IPs públicos, preservando Tailscale Serve. ACME usa `/.well-known/acme-challenge/`; demais acessos HTTP redirecionam para HTTPS. Fonte: [Let's Encrypt](https://letsencrypt.org/2026/03/11/shorter-certs-certbot).

`cogniva-renew-tls.timer` verifica renovação a cada hora. O serviço valida/recarrega Nginx. Inspecionar com `systemctl status cogniva-renew-tls.timer` e `openssl x509 -in /etc/letsencrypt/live/cogniva-ip/fullchain.pem -noout -dates`.

## Revisão canônica

A captura encontrou 618 questões, 612 com gabarito interpretável, zero vínculos canônicos confirmados. Seis questões permanecem preservadas e excluídas da seleção, sem escolher alternativa A por conveniência. Grade insuficiente não autoriza usar questões de outro tópico.

`POST /api/v1/admin/canonical-mapping` recebe vínculos explícitos, justificativa, revisão da questão, revisão do vínculo e `operation_id` UUID. Padrão: `dry_run=true`. O operador revisa assunto/tópico e envia explicitamente `dry_run=false` para aplicar. CAS, proveniência anterior e histórico de operações são preservados. Arrays de aprendizagem não são reescritos. Nenhum vínculo foi aplicado em produção nesta recuperação.

Não remapear por substring ou apenas pelo nome de disciplina. Ausência continua ausência até revisão humana. Templates configuráveis não equivalem a documentos oficiais.

A prova `scripts/verify-xai-cache.py ID` faz **uma geração real paga**, somente se encontrar mnemônico ausente em questão válida; em seguida confirma cache e persistência. Usar apenas com autorização de geração, nunca apagar conteúdo para forçar teste. Evidência existente em `remediation-validation/1.4.3/xai-live-cache.json` dispensa repetir a chamada em verificações rotineiras. [Contratos do cache xAI](IA-XAI.md).


## Gestão de concursos

A partir de 1.4.4/build 11, Ajustes → Gerenciar concursos e registros reúne edição, arquivamento, restauração, exclusão e registros preservados. `EXCLUIDO` é um marcador por proprietário, sincronizado e oculto da prática; não executar `deleteMany` para reproduzir a ação. Remover componentes altera a grade atual sem reintroduzi-los no pull; `revision_history` guarda revisões anteriores já sincronizadas. A migração Room 3→4 adiciona somente a etapa da disciplina. Provas e preservação: [GESTAO-CONCURSOS-1.4.4.md](GESTAO-CONCURSOS-1.4.4.md).

## Catálogo e preparação de questões v2

As etapas [3](ETAPA-3-BACKEND-MONGO.md) e [4](ETAPA-4-CADASTRO-CATALOGO.md) acrescentam o catálogo compartilhado, componentes em etapas distintas, validação v2 e escrita administrativa com dry-run por padrão. Questões abertas podem ser armazenadas, mas continuam fora do protocolo objetivo atual. A [nota para Grok Build — etapas 5–7](NOTA-TECNICA-GROK-BUILD-PASSO-5.md) registra os requisitos de classificação assistida, seleção/progresso e validação/publicação, com estado executado na [entrega 1.4.6](ETAPAS-5-7-CLASSIFICACAO-SELECAO.md); não executar classificação como migração implícita do deploy.

A etapa 5 expõe, para administrador, `POST /api/v1/admin/classification/proposals`, `POST /api/v1/admin/classification/decisions` e `POST /api/v1/admin/classification/compatibility`. O modelo de classificação é o `grok-4.3` já configurado, com o prompt `classify-question` versão 2, incluindo nomes e aliases dos candidatos; a chave não entra no repositório. Toda geração exige `limit` e `max_paid_calls`. Proposta pendente não muda treino nem SRS. O inventário `scripts/inventory_questions.py --dry-run` só conta estados e não grava o banco. A comparação `scripts/classification_preservation.py` permite diferença apenas nos campos de classificação autorizados e continua exigindo histórico idêntico. Nesta entrega não houve lote real: quantidade revisada em produção é 0 e o acervo não está classificado.

Antes de cada promoção, capturar as identidades atuais do servidor compartilhado, sem comparar eternamente com a primeira instalação:

```powershell
python -X utf8 scripts/capture-production-baseline.py docs/remediation-validation/NOVA-VERSAO/before/server-baseline.json
python -X utf8 scripts/production-history.py --backup --label pre-NOVA-VERSAO
```

A captura recusa sobrescrever uma referência existente. O aceite `verify-production-release.py` deve receber `--baseline` apontando para essa captura ou para o aceite realizado imediatamente antes da troca. Outro aplicativo pode ter sido atualizado legitimamente desde uma release antiga; isso não autoriza substituir seu container na promoção do Cogniva.

O promotor executa o binário já compilado com `--prepare-question-storage` antes de abrir o gate. Esse modo instala os quatro validadores, oito índices, recibos e snapshot imutável do catálogo; não converte documentos de questão. Falha na preparação impede liberar tráfego. A instalação recusa documentos incompatíveis ou um validador alterado fora do recibo; não contornar com `bypassDocumentValidation`, remoção de índices ou exclusão de registros. Mudança de contrato instalado exige migração versionada explícita.

Backups privados de antes/depois podem ser confrontados com `scripts/verify-question-storage-backup.py INVENTARIO_ANTES INVENTARIO_DEPOIS --output RELATORIO.json`. O verificador lê apenas os arquivos privados, compara bytes de questões e concursos, fingerprint do histórico, quatro validadores/recibos, oito índices e snapshot do catálogo. Emite hashes e contagens. Uma diferença legítima de atividade exige investigação e relato; não sobrescrever a referência para produzir um resultado verde.

Não reexecutar classificação nem gerar textos de IA para verificar o deploy. Usar testes sintéticos com provedor falso. A chave xAI continua privada no servidor; nunca copiá-la para ferramentas de migração, exemplos, APK ou evidência pública.

## Seleção e progresso confirmado — 1.4.6

O aceite autenticado pode verificar os contratos novos sem gravar tentativas:

```powershell
python -X utf8 scripts/probe-question-selection.py ID --expected-version 1.4.6 --assert-fixed --assert-catalog --assert-knowledge --output caminho-do-relatorio.json
```

`--assert-fixed` aplica os casos conhecidos da conta investigada; outras contas podem legitimamente não possuir filas de erro/SRS. `--assert-knowledge` verifica projeção versão 1 e a resposta de `POST /api/v1/questions/cache-state`, operação de leitura. O cache aceita até 200 IDs distintos por página. Uma questão indisponível retorna null e fica preservada, sem entrar em ações. Nenhum tutor é chamado.

`knowledge_evidence` é uma projeção separada dos totais globais: exige tentativa identificada, revisão de conteúdo correspondente e principal direto revisado. IDs singulares antigos precisam de recibo verificável do operador. Secundários não concedem domínio integral. Decisões de compatibilidade são vinculadas a proprietário, componente e revisões; exclusão não apaga aprendizagem global.

Room 4→5 acrescenta apenas `pending_attempts.examComponentId` nullable; o protocolo de tentativa permanece 2. Não limpar filas ou banco para atualizar. Uma tentativa da mesma versão de conteúdo permanece válida quando só a classificação muda; o servidor calcula a taxonomia vigente. Instruções completas e limites em [ETAPAS-5-7-CLASSIFICACAO-SELECAO.md](ETAPAS-5-7-CLASSIFICACAO-SELECAO.md).

## Aceite da automação finita

`python scripts/probe-legacy-classification.py ID --output RELATORIO` lê somente contagens. `--trigger-one` envia um único pedido shuffle e pode reservar uma chamada paga; não repetir para produzir um resultado favorável. `--check-reuse ID_DA_QUESTAO` verifica reaproveitamento de um vínculo já aplicado. Essas opções são exclusivas e requerem uma conta cujo proprietário foi comprovado. Não enviam tentativas, tutores ou conteúdo de questão para o relatório.

A preparação `--prepare-question-storage` instala também `legacy-cohort-guard/1`, recuperável e sem reescrita das questões. `verify-question-storage-backup.py --authorized-legacy-auto-classification` é o modo específico de preservação após aplicações autorizadas; não amplia a permissão do comparador de revisão humana. Não zerar recibos ou orçamento para repetir a prova. Estado e evidências reais na [nota de operação](CLASSIFICACAO-LEGADO-SOB-DEMANDA.md).
