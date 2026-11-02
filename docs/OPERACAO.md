# Operação e recuperação do Cogniva

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

Release operacional atual de 02/11/2026 às 17:02:53 (UTC−03:00): **1.4.5/build 12**, `9dee93673f2e170e06f2d77ca378df973449414d`, run `41512550219`, todos os jobs aprovados. Aceite repetível:

```powershell
python -X utf8 scripts/verify-production-release.py 9dee93673f2e170e06f2d77ca378df973449414d --version-name 1.4.5 --version-code 12 --xai-model grok-4.3 --baseline docs/remediation-validation/1.4.5/before/production-release.json --evidence-dir docs/remediation-validation/1.4.5
```

Faz somente leituras, baixa o APK para `%LOCALAPPDATA%/Cogniva/releases` e grava evidência sanitizada no diretório solicitado. As evidências das releases anteriores permanecem preservadas. A entrega 1.4.5 tem 32 testes Rust, 36 contratos Python, 50 JVM, lint/nove imagens e dez instrumentados aprovados; instalação de quatro validadores, oito índices e catálogo verificada, com questões/concursos e histórico preservados. [Detalhes da etapa 4](ETAPA-4-CADASTRO-CATALOGO.md).

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

As etapas [3](ETAPA-3-BACKEND-MONGO.md) e [4](ETAPA-4-CADASTRO-CATALOGO.md) acrescentam o catálogo compartilhado, componentes em etapas distintas, validação v2 e escrita administrativa com dry-run por padrão. Questões abertas podem ser armazenadas, mas continuam fora do protocolo objetivo atual. A [nota para Grok Build — etapas 5–7](NOTA-TECNICA-GROK-BUILD-PASSO-5.md) orienta classificação assistida, seleção/progresso e validação/publicação posteriores; não executar classificação como migração implícita do deploy.

Antes de cada promoção, capturar as identidades atuais do servidor compartilhado, sem comparar eternamente com a primeira instalação:

```powershell
python -X utf8 scripts/capture-production-baseline.py docs/remediation-validation/NOVA-VERSAO/before/server-baseline.json
python -X utf8 scripts/production-history.py --backup --label pre-NOVA-VERSAO
```

A captura recusa sobrescrever uma referência existente. O aceite `verify-production-release.py` deve receber `--baseline` apontando para essa captura ou para o aceite realizado imediatamente antes da troca. Outro aplicativo pode ter sido atualizado legitimamente desde uma release antiga; isso não autoriza substituir seu container na promoção do Cogniva.

O promotor executa o binário já compilado com `--prepare-question-storage` antes de abrir o gate. Esse modo instala os quatro validadores, oito índices, recibos e snapshot imutável do catálogo; não converte documentos de questão. Falha na preparação impede liberar tráfego. A instalação recusa documentos incompatíveis ou um validador alterado fora do recibo; não contornar com `bypassDocumentValidation`, remoção de índices ou exclusão de registros. Mudança de contrato instalado exige migração versionada explícita.

Backups privados de antes/depois podem ser confrontados com `scripts/verify-question-storage-backup.py INVENTARIO_ANTES INVENTARIO_DEPOIS --output RELATORIO.json`. O verificador lê apenas os arquivos privados, compara bytes de questões e concursos, fingerprint do histórico, quatro validadores/recibos, oito índices e snapshot do catálogo. Emite hashes e contagens. Uma diferença legítima de atividade exige investigação e relato; não sobrescrever a referência para produzir um resultado verde.

Não reexecutar classificação nem gerar textos de IA para verificar o deploy. Usar testes sintéticos com provedor falso. A chave xAI continua privada no servidor; nunca copiá-la para ferramentas de migração, exemplos, APK ou evidência pública.
