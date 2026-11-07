# Cogniva

Aplicativo Android de estudo para concursos, com cliente Kotlin/Compose e API Rust/Axum. O projeto adota arquitetura offline-first com persistência local em Room (SQLite), sincronização bidirecional idempotente com MongoDB Atlas e infraestrutura conteinerizada e promovida atomicamente no DigitalOcean.

---

## 🚀 Versão em Produção

Versão atual: **1.4.13 / build 20**, revisão `5f77a4adb900bed386f28df2268b40f4dbc123cc`, promovida e conferida em 07/11/2026; contrato de sincronização 2. [Conteúdo e aceite da release](docs/RELEASE-1.4.13.md).

- **Pipeline CI/CD:** [Run #42371065615](https://github.com/danielcdes-create/cogniva/actions/runs/42371065615) — todos os jobs aprovados: web, Rust, Android/JVM/golden, contratos, emulador, build e promoção com ensaio de rollback.
- **Endpoint da API:** `https://198.199.65.103/api/v1/`.
- **Saúde e Prontidão:** `/api/v1/health` (processo) e `/api/v1/ready` (validação com MongoDB e revisão ativa).
- **Metadados Públicos do APK:** `GET /api/app/latest-apk` e `GET /api/v1/apk/info`.
- **Download do APK de Produção:** [Baixar APK v1.4.13 / build 20](https://198.199.65.103/api/v1/apk/download)
  - **Tamanho:** 46.363.697 bytes
  - **SHA-256:** `87c7e36e0b2661c5027c8b37326dbfc7fad4e6d0fcc6f1d8287c9f95b32c114f`
  - **Assinatura:** Chave de release privada com APK Signature Scheme v3 (API 28+).
  - **Streaming:** Entrega direta sem compressão dinâmica intermediária (`no-transform`, `proxy_buffering off`). Detalhes em [docs/LICAO-APRENDIDA-OTA-APK.md](docs/LICAO-APRENDIDA-OTA-APK.md).

---

## 📚 Documentação Técnica e Operacional

A documentação do ecossistema está organizada no diretório [`docs/`](docs/README.md):

- **[Mapa Completo da Documentação](docs/README.md):** Índice mestre e catálogo de referências ativas.
- **[Manual de Operação e Produção](docs/OPERACAO.md):** Guia de deployment na VPS, monitoramento, rollback atômico, gates de manutenção e renovação de certificados TLS.
- **[Lições Aprendidas — Pipeline OTA de APK](docs/LICAO-APRENDIDA-OTA-APK.md):** Diagnóstico de falha de download truncado, incompatibilidade de gzip em binários pré-comprimidos e novas invariantes de engenharia.
- **[Catálogo Compartilhado de Conhecimentos](docs/CATALOGO-CONHECIMENTOS.md):** Hierarquia de disciplinas, tópicos, troncos de carreira e aliases editoriais (`shared/knowledge-catalog.json`). [Visualização formatada](docs/catalogo/CONHECIMENTOS.md).
- **[Contratos e Schema de Questões v2](docs/SCHEMA-QUESTOES-V2.md):** Validação JSON Schema 2020-12, regras BSON no Mongo, outbox transacional e suporte a questões objetivas e discursivas.
- **[Inserção de Questões](docs/INSERCAO-QUESTOES.md):** Módulo Android individual e em lote, JSON/JSONL, rascunhos locais, revisão, recibos idempotentes e preparação opcional com Grok 4.3. Disponível desde 1.4.13/build 20.
- **[Administração Web](docs/ADMIN-WEB.md):** Painel para computador em `/admin/`, com concursos, editais/grades, questões e edição/ativação versionada de troncos, disciplinas e assuntos globais. Mesma API do Android, login administrativo e recuperação de rascunhos. Disponível em [198.199.65.103/admin/](https://198.199.65.103/admin/), com acesso administrativo por código temporário.
- **[Integração com IA (xAI / Grok)](docs/IA-XAI.md):** Contratos de tutoria, plausibilidade, prompt caching no Mongo e rate-limiting com exclusão mútua.
- **[Automação do Acervo Legado](docs/CLASSIFICACAO-LEGADO-SOB-DEMANDA.md):** Classificação assistida sob demanda com cotas diárias estritas e salvaguardas de proveniência.
- **[Arquivo Histórico](docs/historico/README.md):** Relatórios de auditorias passadas, etapas de desenvolvimento e notas de patches anteriores.

---

## 🏛️ Invariantes de Arquitetura e Contratos

1. **Identidade e Autenticação:**
   - Rotas de dados privadas exigem token JWT HS256 assinado com chave privada de pelo menos 32 caracteres.
   - Contas históricas exigem prova temporária emitida exclusivamente após comprovação do operador (`--issue-link-code`). Não há autenticação por mero envio de `@username` ou ID numérico.
2. **Histórico e Integridade Transacional:**
   - Tentativas gravadas com `attempt_id` determinístico por sessão/questão, revisão de conteúdo e instante de ocorrência.
   - Recibo e projeções são persistidos atomicamente no MongoDB com write concern `majority/journal`. Reenvios idênticos devolvem o mesmo recibo; reenvios com payload divergente são rejeitados com conflito.
3. **Offline-First e Migrações Aditivas:**
   - O cliente Android mantém banco Room 5 com migrações aditivas (1→2→3→4→5).
   - Filas de envio e dados locais são isolados estritamente por `ownerUserId`.
   - Pendências não confirmadas são preservadas localmente e nunca apagadas em lote sem ACK confiável do servidor.
4. **Simetria Matemática (Rust ↔ Kotlin):**
   - Cálculo de pontuação líquida unificado: penalidade explícita de erro por banca (Cebraspe fator 1.0 ou múltipla escolha fator 0.0), normalizado na escala 0–100.
   - Projeção de domínio (`masteryBySubject`) computada sobre o total de tópicos ativos do edital selecionado, refletindo fielmente a cobertura do concurso.
   - Fuso horário de estudo unificado em `UTC-3` (`America/Sao_Paulo`) para agregação de metas diárias, streak e heatmap.
5. **Streaming Binário Seguro:**
   - Downloads de binários `.apk` operam com `Content-Length` exato, sem recompressão gzip dinâmica intermediária e com cabeçalho `Cache-Control: no-store, no-transform`.

---

## 🛠️ Ambiente de Desenvolvimento e Validação

- **Backend:** Rust 1.85+ (edição 2021), Axum 0.7, Tokio, MongoDB Driver.
- **Android:** Kotlin 2.1.20, Jetpack Compose (BOM 2024.12.01), Room 2.7.0, targetSdk/compileSdk 35, minSdk 28, JDK 17 / Temurin 21.
- **Scripts & Contratos:** Python 3.11+, Pytest, jsonschema.

### Executar Testes Locais

```powershell
# 1. Testes de Contratos do Domínio e Catálogo (Python)
pytest scripts/tests

# 2. Testes Unitários e de Integração do Backend (Rust)
cd backend-rust
cargo test --locked

# 3. Testes Unitários e Gate Visual Paparazzi (Android)
cd ../android-app
$env:JAVA_HOME = "C:\Users\danie\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:verifyPaparazziDebug
```

---

## 🚢 Pipeline de Deploy e Promoção

O deploy é automatizado via GitHub Actions (`.github/workflows/deploy.yml`) disparado por tags `v*`:

1. **Gate de Validação:** Execução simultânea de contratos Python, testes do backend Rust, testes unitários Android com lint e verificação de insets em emulador headless (API 35).
2. **Build e Assinatura:** Compilação do binário estático Rust em contêiner Docker seguro e assinatura do APK release com chave privada e APK Signature Scheme v3.
3. **Promoção Atômica:** Transferência do bundle imutável para a VPS DigitalOcean, fechamento do gate Nginx para rotas de dados, migração/preparação do MongoDB com `--prepare-question-storage`, ensaio controlado de rollback, troca de ponteiro symlink e reabertura do tráfego.
