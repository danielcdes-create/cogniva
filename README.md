# Cogniva

Aplicativo Android de estudo para concursos, com cliente Kotlin/Compose e API Rust/Axum. O projeto está em desenvolvimento. As notas de produto descrevem objetivos; a implementação e as evidências de validação estão discriminadas abaixo.

## Estado da recuperação de 2026-11-01

As etapas de [catálogo compartilhado de conhecimentos](docs/CATALOGO-CONHECIMENTOS.md) e [formalização dos contratos de questões](docs/SCHEMA-QUESTOES-V2.md) estão definidas e validadas em arquivos versionados. Incluem validade atual/obsoleta, classificações com proveniência e questões abertas com rubrica/correção semântica ou IA. São contratos preparados para a próxima integração, sem migração do Mongo ou habilitação de questões abertas no APK atual. [Consulta do catálogo](docs/catalogo/CONHECIMENTOS.md).

Versão em produção: **1.4.4 / Android build 11 / contrato de sincronização 2**, revisão `dfbcd0fd22c50b89229274b0d73e0bd24b9b695a`, promovida em 02/11/2026 às 12:52 (UTC−03:00). O [pipeline completo](https://github.com/danielcdes-create/cogniva/actions/runs/41472251563) passou gates, assinatura, ensaio de rollback e promoção. HTTPS, prontidão com Mongo, download público e preservação do acervo/histórico foram conferidos externamente. A [gestão de concursos](docs/GESTAO-CONCURSOS-1.4.4.md) permite disciplinas de todo o catálogo ou personalizadas, etapa/grupo, edição e exclusão de componentes e gestão de arquivados numa área própria de Ajustes. A [correção de questões](docs/CORRECAO-QUESTOES-1.4.2.md) recupera o acesso ao acervo sem herdar concursos arquivados; a [correção de Ajustes](docs/CORRECAO-VINCULO-1.4.1.md) mantém confirmação junto ao código. Consulte [FECHAMENTO-AUDITORIA.md](docs/FECHAMENTO-AUDITORIA.md) para C01–C39 e os limites de aceite; [REMEDIACAO.md](docs/REMEDIACAO.md) preserva o registro da execução.

- API: `https://198.199.65.103/api/v1/`.
- Metadados públicos medidos do APK: `GET /api/app/latest-apk`.
- APK: `GET /api/v1/apk/download`. O arquivo servido só muda durante promoção validada.
- [Baixar APK 1.4.4/build 11](https://198.199.65.103/api/v1/apk/download): 12.741.621 bytes; SHA-256 `7c96fc6e3c79d3bb4f9b353e00337ed3e97aca3d0b25f92f2be7d4a6d8686f25`.
- Saúde do processo: `/api/v1/health`; prontidão com MongoDB e revisão: `/api/v1/ready` no backend novo.
- Não se afirma instalação nem validação física no S23 Ultra sem evidência ADB.

## Contratos implementados

**Identidade.** As rotas de dados exigem sessão HS256 com finalidade própria. `user_id` diferente da sessão é rejeitado. Uma instalação possui UUID e segredo aleatório persistidos; contas históricas exigem código de vínculo assinado, temporário e de uso único. Digitar um ID ou @username não comprova posse. Administração exige `ADMIN_USER_IDS` configurado no servidor.

**Histórico.** Cada tentativa possui `attempt_id`, revisão da questão e instante de ocorrência com offset. O servidor calcula o acerto pelo gabarito validado. Evento, projeção e recibo são gravados atomicamente no mesmo documento MongoDB, com compare-and-swap e write concern majority/journal. O cliente só remove identidades confirmadas, depois de persistir a projeção. Reenvios idênticos devolvem o mesmo recibo; conteúdos diferentes com o mesmo ID são rejeitados. Campos legados desconhecidos e arrays históricos são preservados.

**Offline.** Room 3 recebe migrações aditivas. Bancos e filas são separados por proprietário; trocar de conta não reatribui eventos. Pendências anteriores ao protocolo novo são preservadas para revisão, pois a entrega antiga pode ter ocorrido sem ACK confiável. Há revisão/exportação explícita. Não há números de demonstração quando faltam dados locais. Um ACK parcial não permite reconstruir como zero um histórico ainda não baixado.

**Acesso ao acervo.** Shuffle geral e filas pessoais de SRS/erros/desafio independem da aba aberta no planner. Treino explicitamente filtrado e simulado respeitam a grade. Concursos arquivados saem das abas de treino e continuam preservados em Ajustes. O pull conserva referências históricas indisponíveis, mas informa quais questões podem entrar nas ações; contadores e seleção usam a mesma marcação de revisão pendente. O acervo atual não contém questões classificadas nos quatro componentes da grade fiscal investigada. Quatro referências antigas foram reconciliadas com exemplos locais do código anterior; não foram inseridas no Mongo nem apresentadas como acervo recuperado.

**Concurso e catálogo.** `shared/canonical-catalog.json` é o catálogo comum aos dois runtimes. Templates são configuráveis pelo usuário e não constituem edital oficial: datas indefinidas, corte zero e banca não confirmada não são preenchidos por adivinhação. Concursos do usuário são persistidos por proprietário e revisão, com pull/push e conflito explícito. Omissões de tópicos arquivam suas identidades; histórico e revisões anteriores continuam preservados. Nome de disciplina não é prova de tópico canônico.

**Estudo e métricas.** Simulado respeita tópicos ativos, quantidades e pesos da grade, e falha explicitamente quando faltam questões válidas. SRS, caderno de erros e desafio usam seus conjuntos elegíveis, inclusive no cache. Prática inclui registros com domínio zero. Acurácia é calculada por soma de acertos / soma de tentativas. O dia de estudo usa UTC−03:00; heatmap e matriz usam os últimos 90 dias civis. Novos eventos usam SRS 1/7/30 dias; intervalos históricos permanecem armazenados. Pontuação de simulado: `100 × Σ[peso × (acertos − desconto × erros)] / Σ[peso × quantidade]`, limitada a 0–100. Desconto pertence à regra configurada da prova; corte ausente não produz aprovação simulada. Domínio considera acurácia, confiança e evidência SRS; confiança histórica desconhecida não é inventada.

**Sessões e tutores.** Questões, contexto, respostas e prazo absoluto são persistidos. Restauração não reinicia o cronômetro e saves atrasados não ressuscitam sessão concluída. Reportes são enfileirados antes da confirmação local. Falha de tutor não transforma comentário com gabarito em dica pré-resposta. Plausibilidade tem campo de cache próprio; geração possui exclusão mútua e orçamento diário por conta.

Detalhes normativos e casos compartilhados: [shared/domain-contracts.json](shared/domain-contracts.json).

## Desenvolvimento e validação

Rust 1.93.1, JDK Temurin 21, Kotlin 2.1.20, AGP 8.12, Gradle 9.3.1, compileSdk/targetSdk 35. As dependências Android existentes foram mantidas.

```powershell
cd backend-rust
cargo test --locked
# Integração: exclusivamente o Mongo sintético em loopback 27048, enableTestCommands=1.
$env:COGNIVA_TEST_MONGODB_URI='mongodb://127.0.0.1:27048/'
cargo test --locked -- --include-ignored
```

```powershell
cd android-app
# Definir JAVA_HOME para Temurin 21 e ANDROID_HOME para o SDK local.
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
.\gradlew.bat :app:verifyPaparazziDebug --tests br.com.concursoprep.app.QuestionPreviewMatrixScreenshotTest --rerun-tasks
# --tests pertence a testes JVM; instrumentados usam argumento de runner.
```

Paparazzi 1.3.5 usa `DeviceConfig.PIXEL` de estoque em todas as variantes JVM, variando fonte e modo noturno. As dimensões nomeadas em `@PreviewMatrix` pertencem às previews do Android Studio; não são prova JVM de geometrias diferentes. A geometria real 1440×3088/500dpi, API 35 (**Android 15**) e cutout é verificada no workflow `emulator-insets.yml`. Não alterar dimensões arbitrárias do renderer nem remover `buildSrc/NoopTestReporter`: ver a [nota de screenshot](<Notas Conversao Kotlin_Rust/Nota Tecnica - Screenshot JVM, emulador headless e hash de APK - Guidelines_Arquitetura.md>).

Helpers de teste portáteis: `scripts/prepare_test_mongo.py`, `scripts/prepare_test_jdk.py`. Nenhum instala serviço de banco ou muda o Java global. O relatório original permanece congelado em [AUDITORIA-COGNIVA.md](docs/auditoria/2026-11-01/AUDITORIA-COGNIVA.md), com fontes e hashes históricos.

## Release e operação

Todo build é local ou no GitHub Actions. **Nunca compilar Rust, Docker, npm ou JVM na VPS.** `android-app/build-android.ps1` gera somente artefatos locais versionados; não publica automaticamente.

O workflow `deploy.yml` executa validação de domínio/router, JVM/golden e emulador antes de construir a imagem e assinar o APK release. Gatilhos: manual e tags `v*`, respeitando `[skip ci]`. Promoção usa imagem por digest e bundle por SHA, valida tamanho de runtime inferior a 50 MB, atualiza somente `api-rust-core` no projeto Compose existente e verifica readiness, revisão, APK e rejeição de acesso anônimo. Uma falha aciona recuperação da imagem e do ponteiro anteriores. O Compose compartilhado da VPS não é substituído pelo exemplo deste repositório.

Segredos de Actions: `DO_HOST`, `DO_USER`, `DO_SSH_KEY`, `DO_SSH_KNOWN_HOSTS`, `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_PASSWORD`, `ANDROID_SIGNING_LINEAGE_BASE64`. Chaves privadas não entram no Git. A assinatura usa chave privada rotacionada em API 28+; API 26–27 conserva o assinante antigo para compatibilidade de atualização e mantém essa limitação de segurança.

Produção usa **xAI / Grok 4.3**, conforme a escolha do proprietário após a conta não disponibilizar Grok 4.7. Os oito recursos consultam o campo próprio no Mongo antes de gerar; os prompts existentes são preservados. Resultado concluído é gravado com majority/journal e reutilizado em solicitações futuras, sem cota de geração para cache hit. Chave somente no ambiente privado da VPS. [Operação e prova real de cache](docs/IA-XAI.md).

Nginx atende HTTPS nos IPs públicos sem disputar a porta de Tailscale Serve. Certificado IP short-lived do Let's Encrypt, renovação verificada por timer horário e reload do Nginx. Inspeção: `systemctl status cogniva-renew-tls.timer`; prontidão: `curl --fail https://198.199.65.103/api/v1/ready`. Detalhes e recuperação: [docs/OPERACAO.md](docs/OPERACAO.md).

A diretriz de runtime inferior a 50 MB é um **gate mensurável**. Os percentuais de RAM/CPU e reduções citados nas notas históricas são hipóteses/baselines daquela data, não garantias atuais. Antes/depois de cada promoção, medir recursos e conferir hashes de histórico. O banco de produção nunca é usado para testes sintéticos.
