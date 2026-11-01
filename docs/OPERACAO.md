# Operação e recuperação do Cogniva

## Limites e credenciais

A VPS executa somente imagens prontas. Não compilar Rust, Docker, npm ou JVM nela. O Compose deste repositório é um exemplo independente; não substitui o arquivo compartilhado `/opt/apps/docker-compose.yml`.

A promoção usa o override `/opt/apps/cogniva-runtime.override.json`, serviço `api-rust-core`, projeto `apps`, porta local 8001. FIN, Asbuildo e Spamshield são conferidos por ID antes/depois. Limites iniciais: 160 MB, 0,75 CPU, 128 processos, runtime somente leitura, logs 10 MB × 3. São limites, não medições de consumo.

JWT, URI Mongo e chaves de IA ficam no ambiente privado. Não registrar seus valores. A chave Android e senha DPAPI ficam em `%LOCALAPPDATA%/Cogniva/release-signing`, com ACL privada. Preservar essa pasta em backup privado. O CI usa Secrets e apaga credenciais temporárias.

Configurar os modelos explicitamente em `/opt/apps/.env.rust`. Em 01/11/2026, produção usa `GEMINI_MODEL=gemini-2.5-flash-lite`; o fallback histórico Gemini 1.5 do binário não está disponível no provedor. A listagem autenticada confirmou suporte do modelo selecionado, sem testar qualidade/quota por geração. Alterações de ambiente só entram no container após sua recriação controlada.

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

Release operacional atual de 01/11/2026: **1.4.1/build 8**, `22bd6027acfe277ea2d1e425578d4d4709620f1d`, run `41347592566`, todos os jobs aprovados. Aceite repetível:

```powershell
python -X utf8 scripts/verify-production-release.py 22bd6027acfe277ea2d1e425578d4d4709620f1d --version-name 1.4.1 --version-code 8 --baseline docs/remediation-validation/1.4.1/before/production-release.json --evidence-dir docs/remediation-validation/1.4.1
```

Faz somente leituras, baixa o APK para `%LOCALAPPDATA%/Cogniva/releases` e grava evidência sanitizada em `docs/remediation-validation/1.4.1/production-release.json`. As evidências da primeira release, revisão `d8716db8c12653a726c9284b9e28e7ba1c30df64`, permanecem preservadas no diretório pai.

## Recuperação e TLS

`previous.json` da release registra imagem/ponteiro anteriores. Recuperar somente `api-rust-core` com os dois arquivos Compose. Não derrubar o projeto compartilhado. Conferir `ROLLBACK_HEALTHY`, imagem efetiva e atendimento HTTP. Não desinstalar o aplicativo, limpar Room nem remover filas como contorno.

Certificado IP Let's Encrypt short-lived obtido por HTTP-01 com Certbot 5.4 precompilado. Nginx escuta nos IPs públicos, preservando Tailscale Serve. ACME usa `/.well-known/acme-challenge/`; demais acessos HTTP redirecionam para HTTPS. Fonte: [Let's Encrypt](https://letsencrypt.org/2026/03/11/shorter-certs-certbot).

`cogniva-renew-tls.timer` verifica renovação a cada hora. O serviço valida/recarrega Nginx. Inspecionar com `systemctl status cogniva-renew-tls.timer` e `openssl x509 -in /etc/letsencrypt/live/cogniva-ip/fullchain.pem -noout -dates`.

## Revisão canônica

A captura encontrou 618 questões, 612 com gabarito interpretável, zero vínculos canônicos confirmados. Seis questões permanecem preservadas e excluídas da seleção, sem escolher alternativa A por conveniência. Grade insuficiente não autoriza usar questões de outro tópico.

`POST /api/v1/admin/canonical-mapping` recebe vínculos explícitos, justificativa, revisão da questão, revisão do vínculo e `operation_id` UUID. Padrão: `dry_run=true`. O operador revisa assunto/tópico e envia explicitamente `dry_run=false` para aplicar. CAS, proveniência anterior e histórico de operações são preservados. Arrays de aprendizagem não são reescritos. Nenhum vínculo foi aplicado em produção nesta recuperação.

Não remapear por substring ou apenas pelo nome de disciplina. Ausência continua ausência até revisão humana. Templates configuráveis não equivalem a documentos oficiais.
