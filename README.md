# Cogniva

Plataforma inteligente de preparação de alta performance para concursos públicos, composta por aplicativo nativo Android (**Kotlin / Jetpack Compose / Room v8**), portal web interativo de **Missões** (**`/missoes/`**), painel administrativo web incorporado (**`/admin/`**) e API **Rust / Axum** (`cogniva-api`). O ecossistema transforma evidências auditáveis de aprendizagem em decisões de estudo e estratégia de prova em arquitetura *offline-first* sobre **MongoDB (`cogniva`)**, com conformidade integral **LGPD (Lei 13.709/2018)** e promoção atômica conteinerizada.

---

## 🚀 Versão Atual: `2.4.6` (`build 36`)

- **Arquitetura Disruptiva em 3 Camadas (`contract_version: 5`):**
  1. **Escassez — Diagnóstico Metacognitivo Pré-Exposição & Verificação Conceitual (`CTV`):** Ficha Editorial Rastreável (`editorial_card` com dispositivos legais, regra cobrada, exceção, trecho decisivo e erros conceituais plausíveis), captura pré-gabarito de confiança em 5 níveis e estratégia cognitiva em cartões uniformes (com desambiguação contextual de chutes), **Leitura Metacognitiva em Duas Camadas**, restrição explícita **`< 15` questões humanas** (`MIN_QUESTIONS_FOR_MASTERY = 15`) e **Transferência Cross-Banca (`TRANSFER_PROBE`) / `CTV`** com registro explícito de `TRANSFER_UNMEASURED` quando não há item válido de outra banca.
  2. **Fadiga e Pontuação Líquida — Simulador de Decisão sob Incerteza:** Correção matemática $VE = 2p - 1$ em itens $+1/-1/0$, complementaridade binária ($48\% \leftrightarrow 52\%$), maximização de $P(S_{\text{final}} \ge \max(S_{\text{meta}}, S_{\text{min}}))$ considerando estado atual da prova e incerteza Beta-Binomial, e modelo individual logístico de **300 minutos** $p(t) = \sigma(\operatorname{logit}(p_0) - \lambda_i \frac{t}{300})$ com controle de dificuldade (`FATIGUE_DETECTED`, `STABLE_ENDURANCE`, `WARMUP_GAIN_DETECTED`).
  3. **Protocolo de Entrada de 60 Segundos ("Terça · 22h") & Consulta em Camada:** Tela inicial livre de metas atrasadas, gráficos punitivos ou cobrança de sequência — compromisso reversível (**`Resolver 1 questão`** / **`Sem estudar hoje`**) e seleção contextual de qualquer trecho na questão com **Camada de Consulta Normativa + IA + Pesquisa Google sobreposta** (Partial Custom Tab). Consulte [`docs/MOTORES-DECISAO-CORE-LOOP.md`](docs/MOTORES-DECISAO-CORE-LOOP.md).
- **Autenticação Nativa e LGPD (Art. 18):** Modo convidado imediato + contas nativas Cogniva com e-mail, senha `Argon2id`, sessões revogáveis e exclusão física imediata de dados pessoais (`DELETE /api/v1/auth/account` / app Android) preservando 100% do acervo compartilhado ([`PRIVACY.md`](PRIVACY.md)).
- **Saúde e Prontidão:** `GET /api/v1/health` (processo) e `GET /api/v1/ready` (validação MongoDB com retorno `503` em falha, catálogo ativo e revisão).
- **Distribuição OTA Android:** `GET /api/v1/apk/info` e streaming binário direto em `GET /api/v1/apk/download` (`ETag` / `304`, `Cache-Control: no-store, no-transform`, `proxy_buffering off`), preservando `applicationId = "br.com.concursoprep.app"` para atualização contínua.
- **Políticas, Auditoria e Histórico:** Consulte [`CHANGELOG.md`](CHANGELOG.md), [`AUDIT_REPORT_2_4_6.md`](AUDIT_REPORT_2_4_6.md), [`PRIVACY.md`](PRIVACY.md) e [`SECURITY.md`](SECURITY.md).

---

## 📚 Mapa da Documentação Viva

Toda a documentação técnica e operacional ativa está centralizada em [`docs/`](docs/README.md):

- **[Arquitetura do Ecossistema 2.0](docs/ARQUITETURA.md):** Visão ponta a ponta de autenticação nativa, banco `cogniva` 100% V2, conformidade LGPD, separação `exam_templates` vs. `user_exams`, simetria Rust ↔ Kotlin e governança de IA.
- **[Motores de Decisão, Grafo de Conceitos e Core Loop Diário](docs/MOTORES-DECISAO-CORE-LOOP.md):** Especificação matemática (`contract_version: 5`), regra de escassez `< 15`, Ficha Editorial e 3 sinais, Transferência Cross-Banca, Simulador de Decisão sob Incerteza ($p(t)$ em 300 min) e Protocolo de Entrada de 60 Segundos.
- **[Política de Privacidade e LGPD](PRIVACY.md) & [Política de Segurança](SECURITY.md):** Governança de dados pessoais, exclusão Art. 18, invariantes de segurança e relato responsável de vulnerabilidades.
- **[Manual de Operação e Produção](docs/OPERACAO.md):** Variáveis de ambiente, migração *read-only* `CNU → cogniva`, preparação do MongoDB (`--prepare-storage`), TLS + HSTS + rate limits IPv6 `/64`, promoção atômica, retenção de releases e rollback.
- **[Módulo Missões (`/missoes/` e Android)](docs/MODULO-MISSOES.md):** Aprendizagem experiencial audiovisual interativa declarativa (`shared/missions-catalog.json`), streaming `HTTP Range` sem recompressão, gráficos financeiros nativos e separação epistemológica estrita entre percurso narrativo, decisões formativas e avaliação formal em questões humanas.
- **[Administração Web (`/admin/`)](docs/ADMIN-WEB.md):** Login nativo com cookie `HttpOnly` + `Sec-Fetch-Site` + CSRF, acessibilidade WCAG 2.1 AA, gestão de templates globais, edição V2 de questões, **Reportes**, **Classificação IA** e ativação versionada do catálogo.
- **[Guia de Administração (Central de Ajuda)](docs/GUIA-ADMINISTRACAO.md):** Guia operacional gerado diretamente de [`web-admin/help.js`](web-admin/help.js) e verificado em CI.
- **[Estrutura de Estudos](docs/ESTRUTURA-ESTUDOS.md):** Relações entre troncos, templates globais, grades pessoais, componentes, disciplinas, assuntos, subassuntos e questões.
- **[Catálogo Compartilhado de Conhecimentos](docs/CATALOGO-CONHECIMENTOS.md):** Regras normativas de [`shared/knowledge-catalog.json`](shared/knowledge-catalog.json) e [`shared/exam-templates.json`](shared/exam-templates.json). Tabela completa em [`docs/catalogo/CONHECIMENTOS.md`](docs/catalogo/CONHECIMENTOS.md).
- **[Contratos e Schema de Questões v2](docs/SCHEMA-QUESTOES-V2.md):** Especificação 100% V2, hash canônico JCS/SHA-256 (RFC 8785), validação estrita de URIs `http(s)://`, validade temporal, modo preparatório de discursivas (`OPEN_ENDED`) e compatibilidade por edital.
- **[Inserção de Questões](docs/INSERCAO-QUESTOES.md) & [Pacotes Prontos](docs/PACOTES-QUESTOES.md):** Cadastro individual e em lote (JSON/JSONL) no `/admin/`, no Android e via `scripts/cogniva_import.py`, recibos idempotentes e guia passo a passo para modelar `Disciplina → Assunto → Subassunto`.
- **[Integração com IA (xAI)](docs/IA-XAI.md):** Governança via `XAI_MODEL`, delimitação XML contra injeção de prompt, privacidade de cache para notas pessoais, orçamento diário em `UTC-3` e estorno automático em falhas.
- **[Lições Aprendidas — Pipeline OTA de APK](docs/LICAO-APRENDIDA-OTA-APK.md):** Invariantes de entrega binária sem recompressão gzip intermediária, validação SHA-256 + tamanho no instalador e checklist pós-promoção.
- **Guias de Módulos:** [`backend-rust/README.md`](backend-rust/README.md) e [`android-app/README.md`](android-app/README.md).

---

## 🏛️ Invariantes de Engenharia

1. **Autenticação, Blindagem CSRF e Isolamento de Sessão:**
   - Senhas derivadas com `Argon2id`; sessões revogáveis e com TTL na coleção `sessions`; tokens mobile protegidos no Android Keystore (AES-GCM, `allowBackup="false"`) e sessões web protegidas por cookie `HttpOnly` + `SameSite=Strict` + `Sec-Fetch-Site` + `X-Forwarded-Proto` + `X-Cogniva-CSRF: 1`.
2. **Acervo 100% V2 e Integridade Transacional:**
   - Todas as questões em `cogniva.todasQuestoes` obedecem ao `schema_version = 2` com `content_revision` JCS SHA-256 idêntico entre Python, Rust e Kotlin e validação estrita de URIs `^https?://`.
   - Tentativas e comandos administrativos usam `write_concern: majority` com `journal: true`, recibos idempotentes e replay cronológico de respostas offline fora de ordem.
3. **Offline-First, Simulados Duráveis e Conformidade LGPD:**
   - O cliente Android isola dados em bancos Room v8 separados por conta (`cogniva-<userId>.db`), persiste simulados, omissões estratégicas (`isBlank`), ciclo de vida CTV (`LocalCtvLedger`) e telemetria de sessão contra morte de processo e permite exclusão física imediata dos dados pessoais do titular sem afetar o acervo público de questões.
4. **Simetria Matemática (`v5`), Honestidade Epistemológica e Acessibilidade WCAG 2.1 AA:**
   - Pontuação líquida ponderada, decisão sob incerteza maximizando $P(S_{\text{final}} \ge S_{\text{meta}})$ sob $VE = (1+\lambda)p(t) - \lambda$, curva contínua $R(t,S)$, restrição estrita de escassez (`< 15` questões humanas distintas nunca afirmam domínio pleno; `TRANSFER_UNMEASURED` quando não há questão de outra banca), curva logística de 300 minutos controlada por dificuldade, calendário/cotas em `America/Sao_Paulo` (`UTC-3`) e contraste mínimo `>= 4.5:1` auditado automaticamente no Android e no Web Admin.

---

## 🛠️ Validação Local

```bash
# 1. Contratos, catálogo, schemas de questões e links Markdown (Python)
python3 scripts/knowledge_catalog.py --check
python3 scripts/question_contracts.py --examples
python3 scripts/missions_contracts.py --check
python3 scripts/check_markdown_links.py
pytest scripts/tests -v

# 2. Portais Web (Missões e Admin) e sincronização da Ajuda (Node.js 20+)
node scripts/export-admin-help.mjs --check
npm --prefix web-admin run check
npm --prefix web-admin test
npm --prefix web-missions run check
npm --prefix web-missions test

# 3. Backend Rust (Clippy zero-warnings + testes unitários e contratos)
cargo clippy --manifest-path backend-rust/Cargo.toml --all-targets --locked -- -D warnings
cargo test --manifest-path backend-rust/Cargo.toml --locked

# 4. Aplicativo Android (JVM, Lint e snapshots Paparazzi com daemon/build-cache — requer JDK 17+ e Android SDK 35)
cd android-app
./gradlew :app:testDebugUnitTest :app:lintDebug :app:verifyPaparazziDebug --build-cache
```

---

## 🚢 CI/CD e Promoção Atômica

- **Validação Contínua ([`.github/workflows/validate.yml`](.github/workflows/validate.yml) e [`knowledge-contracts.yml`](.github/workflows/knowledge-contracts.yml)):** Executada automaticamente em todo `push` em `main`, `pull_request` e `workflow_dispatch`, validando contratos Python, links Markdown, paridade da Ajuda, testes do `/admin/`, `cargo clippy -D warnings`, suíte Rust (incluindo integração em MongoDB sintético) e testes JVM/Lint/Paparazzi Android com daemon e build-cache aquecidos.
- **Release e Deploy Paralelizado ([`.github/workflows/deploy.yml`](.github/workflows/deploy.yml)):** Disparado por tags `v*` ou execução manual, dispara em paralelo `verify`, `emulator` (com pré-compilação dos APKs antes do boot QEMU API 35), `build-apk` (R8 + assinatura privada v2/v3) e `build-image` (`cogniva-api` com camada cacheada de dependências `musl`), empacota e valida `SHA256SUMS` + `rollback-drill` isolado e, somente após 100% dos gates verdes, executa [`deploy/promote.py`](deploy/promote.py) com gate Nginx `503` (`Retry-After: 15`), `--prepare-storage`, troca atômica de symlink e poda automática de releases antigas.
