# Cogniva

Plataforma de estudo de alta performance para concursos públicos, composta por aplicativo nativo Android (**Kotlin / Jetpack Compose / Room v6**), painel administrativo web incorporado (**`/admin/`**) e API **Rust / Axum** (`cogniva-api`). O ecossistema opera em arquitetura *offline-first* com sincronização bidirecional idempotente sobre **MongoDB (`cogniva`)** e promoção atômica conteinerizada.

---

## 🚀 Versão Atual: `2.0.0` (`build 25`)

- **Contrato de Domínio:** `contract_version: 3` ([`shared/domain-contracts.json`](shared/domain-contracts.json)) e catálogo canônico unificado `catalog_version: 2` ([`shared/knowledge-catalog.json`](shared/knowledge-catalog.json)).
- **Autenticação Nativa:** Modo convidado imediato + contas nativas Cogniva com e-mail, senha `Argon2id` e sessões revogáveis (zero dependência de Telegram).
- **Saúde e Prontidão:** `GET /api/v1/health` (processo) e `GET /api/v1/ready` (validação MongoDB, catálogo ativo e revisão).
- **Distribuição OTA Android:** `GET /api/v1/apk/info`, `GET /api/app/latest-apk` e streaming direto binário em `GET /api/v1/apk/download` (`Cache-Control: no-store, no-transform`, `proxy_buffering off`), preservando `applicationId = "br.com.concursoprep.app"` para atualização contínua.
- **Histórico de Mudanças:** Consulte o [`CHANGELOG.md`](CHANGELOG.md).

---

## 📚 Mapa da Documentação Viva

Toda a documentação técnica e operacional ativa está centralizada em [`docs/`](docs/README.md):

- **[Arquitetura do Ecossistema 2.0](docs/ARQUITETURA.md):** Visão ponta a ponta de autenticação nativa, banco `cogniva` 100% V2, separação `exam_templates` vs. `user_exams`, simetria Rust ↔ Kotlin e governança de IA.
- **[Manual de Operação e Produção](docs/OPERACAO.md):** Variáveis de ambiente, migração *read-only* `CNU → cogniva`, preparação do MongoDB, TLS IPv4/IPv6, promoção atômica e rollback.
- **[Administração Web (`/admin/`)](docs/ADMIN-WEB.md):** Login nativo com cookie `HttpOnly` + CSRF, gestão de templates globais, edição V2 de questões, curadoria de **Reportes**, **Classificação IA** e ativação versionada do catálogo.
- **[Guia de Administração (Central de Ajuda)](docs/GUIA-ADMINISTRACAO.md):** Guia operacional gerado diretamente de [`web-admin/help.js`](web-admin/help.js) e verificado em CI.
- **[Estrutura de Estudos](docs/ESTRUTURA-ESTUDOS.md):** Relações entre troncos, templates globais, grades pessoais, componentes, disciplinas, assuntos, subassuntos e questões.
- **[Catálogo Compartilhado de Conhecimentos](docs/CATALOGO-CONHECIMENTOS.md):** Regras normativas de [`shared/knowledge-catalog.json`](shared/knowledge-catalog.json) e [`shared/exam-templates.json`](shared/exam-templates.json). Tabela completa em [`docs/catalogo/CONHECIMENTOS.md`](docs/catalogo/CONHECIMENTOS.md).
- **[Contratos e Schema de Questões v2](docs/SCHEMA-QUESTOES-V2.md):** Especificação 100% V2, hash canônico JCS/SHA-256 (RFC 8785), validade temporal, modo preparatório de discursivas (`OPEN_ENDED`) e compatibilidade por edital.
- **[Inserção de Questões](docs/INSERCAO-QUESTOES.md):** Cadastro individual e em lote (JSON/JSONL) no `/admin/` e no Android, recibos idempotentes e estruturação assistida por IA.
- **[Integração com IA (xAI)](docs/IA-XAI.md):** Governança explícita via `XAI_MODEL`, orçamento diário global e por usuário, estorno de cota em falhas e metadados `ai_resource_meta`.
- **[Lições Aprendidas — Pipeline OTA de APK](docs/LICAO-APRENDIDA-OTA-APK.md):** Invariantes de entrega binária sem recompressão gzip intermediária e checklist pós-promoção.
- **Guias de Módulos:** [`backend-rust/README.md`](backend-rust/README.md) e [`android-app/README.md`](android-app/README.md).

---

## 🏛️ Invariantes de Engenharia

1. **Autenticação e Segurança de Sessão:**
   - Senhas hashadas com `Argon2id`; sessões persistidas e revogáveis na coleção `sessions`; tokens mobile protegidos no Android Keystore (AES-GCM) e sessões web protegidas por cookie `HttpOnly` + `SameSite=Strict` + `X-Cogniva-CSRF: 1`.
2. **Acervo 100% V2 e Integridade Transacional:**
   - Todas as questões em `cogniva.todasQuestoes` obedecem ao `schema_version = 2` com `content_revision` JCS SHA-256 idêntico entre Python, Rust e Kotlin.
   - Tentativas e comandos administrativos usam `write_concern: majority` com `journal: true` e recibos idempotentes.
3. **Offline-First e Isolamento por Conta:**
   - O cliente Android isola dados em bancos Room v6 separados por conta (`cogniva-<userId>.db`), preservando integralmente o progresso ao converter uma conta convidada em conta registrada.
   - Reportes abertos de questões ficam isolados ao estudante que reportou até deliberação administrativa na aba **Reportes**.
4. **Simetria Matemática (Rust ↔ Kotlin):**
   - Pontuação líquida ponderada com penalidade configurável por prova, domínio sobre tópicos ativos da grade e calendário unificado em `America/Sao_Paulo` (`UTC-3`).

---

## 🛠️ Validação Local

```bash
# 1. Contratos, catálogo, schemas de questões e links Markdown (Python)
python3 scripts/knowledge_catalog.py --check
python3 scripts/question_contracts.py --examples
python3 scripts/check_markdown_links.py
pytest scripts/tests -v

# 2. Painel Web Admin e sincronização da Ajuda (Node.js 20+)
node scripts/export-admin-help.mjs --check
npm --prefix web-admin run check
npm --prefix web-admin test

# 3. Backend Rust (unitários e contratos)
cargo test --manifest-path backend-rust/Cargo.toml --locked

# 4. Aplicativo Android (JVM, Lint e snapshots Paparazzi — requer JDK 17 e Android SDK)
cd android-app
./gradlew :app:testDebugUnitTest :app:lintDebug :app:verifyPaparazziDebug
```

---

## 🚢 CI/CD e Promoção Atômica

- **Validação Contínua ([`.github/workflows/validate.yml`](.github/workflows/validate.yml) e [`knowledge-contracts.yml`](.github/workflows/knowledge-contracts.yml)):** Executada automaticamente em todo `push` e `pull_request`, validando contratos Python, links Markdown, paridade da Ajuda, testes do `/admin/`, suíte Rust (incluindo integração com MongoDB isolado), testes JVM/Paparazzi Android e ensaio real de rollback Docker.
- **Release e Deploy ([`.github/workflows/deploy.yml`](.github/workflows/deploy.yml)):** Disparado por tags `v*` ou execução manual, compila a imagem `cogniva-api`, assina o APK release minificado com R8 e executa [`deploy/promote.py`](deploy/promote.py) com gate Nginx `503`, `--prepare-question-storage`, ensaio de rollback verificado e troca atômica de symlink.
