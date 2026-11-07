# 🦀 COGNIVA Backend (`api-rust-core`)

> **Registro histórico de 2026-10-31.** As seções abaixo descrevem a baseline anterior à recuperação. Contratos atuais, API autenticada, CAS/ACK, catálogo compartilhado e runtime estão no [README principal](../README.md), [REMEDIACAO](../docs/historico/auditoria-2026-11-01/REMEDIACAO.md), [operação](../docs/OPERACAO.md) e no [mapa da documentação](../docs/README.md). Tamanho/RAM citados abaixo não são medições da release nova. Nunca compilar na VPS nem substituir seu Compose compartilhado pelo exemplo do repositório.

Microsserviço de alta performance em **Rust (`Axum 0.7` + `Tokio` + `MongoDB 3.1`)** que alimenta o ecossistema **COGNIVA**.

---

## 1. Destaques Arquiteturais

* **Retrocompatibilidade Total com o Banco `CNU`:** Lê e atualiza nativamente os documentos legados gerados pelo antigo `AFTBot.py` nas coleções `todasQuestoes`, `userActivityLog`, `usuarios`, `reportedQuestoes` e `utils`, utilizando `#[serde(default)]` para novos atributos de governança.
* **Consumo Mínimo de Recursos:** Binário nativo estático compilado com `lto = true`, `codegen-units = 1` e `strip = true`, rodando em container `debian:bookworm-slim` com menos de 40 MB de imagem e ~15 MB de RAM.
* **Agendador Assíncrono Integrado (`services/scheduler.rs`):** Substitui o antigo loop `schedule` em Python e o script `countdown.py` por uma *task* nativa do Tokio que aplica diariamente o decaimento de 1% (`points_with_decay`), recalcula vencimentos do SRS e atualiza a contagem regressiva oficial dos concursos.

---

## 2. Módulos Internos (`src/`)

* `models/question.rs`: Estrutura completa da questão, gabarito, vigência legislativa (`VIGENTE`, `DESATUALIZADA`, `ANULADA`), vínculo com o edital verticalizado (`syllabus_topic_ids`) e campos cacheados do **Cogniva AI**.
* `models/contest.rs`: Máquina de estados do concurso (`PREVISTO`, `SOLICITADO_AUTORIZADO`, `EDITAL_PUBLICADO`, `EM_ANDAMENTO`, `ENCERRADO`), blocos com pesos e metadados de estimativa transparente.
* `models/activity.rs`: Histórico individual por questão (`pastSessions`), *streak*, pontuação com decaimento, intervalo SRS, flag de Caderno de Erros e flag de Ponto Cego (`high_confidence_error_flag`).
* `services/srs_engine.rs`: Motor matemático de repetição espaçada (curva de 3 a 90 dias ou degraus de 1, 7 e 30 dias) e classificador da Matriz 2×2 Confiança × Acerto.
* `services/difficulty.rs`: Classificador automático de dificuldade (`Fácil`, `Média`, `Difícil`, `Muito Difícil`) e estimador heurístico de plausibilidade de distratores em Rust puro.
* `services/quiz_selector.rs`: Seletores para os 8 modos de estudo (`STANDARD`, `SHUFFLE`, `SIMULADO`, `MOST_BADASS`, `SRS_REVIEW`, `ERROR_NOTEBOOK`, `COMMON_TRUNK`, `SINGLE`).
* `services/ai_tutor.rs`: Cliente HTTP assíncrono (`reqwest` + `rustls`) para Gemini 1.5 Flash e OpenAI `gpt-4o-mini` com persistência imediata na coleção `todasQuestoes`.
* `routes/apk_ota.rs`: Servidor de atualização OTA (*Over-The-Air*) do aplicativo Android (`GET /api/v1/apk/info` e `GET /api/v1/apk/download`), lendo dinamicamente `output-metadata.json` e calculando o hash **SHA-256** do binário publicado em `/app/apk/app-debug.apk`.

---

## 3. Execução Local e Testes

```powershell
# Rodar a suíte de testes unitários do motor SRS e Matriz de Confiança
cargo test

# Verificar compilação sem gerar binário
cargo check

# Executar servidor localmente (requer arquivo .env)
cargo run
```
# Inserção revisada de questões

As rotas administrativas `/api/v1/admin/questions/import/{capabilities,preview,commit,assist}` usam o schema v2 e a outbox de auditoria existentes, com criação sem sobrescrita e recibos por item. A assistência opcional usa xAI/Grok 4.3 com revisão humana. Formatos, limites, acesso e teste Mongo isolado estão em [INSERCAO-QUESTOES.md](../docs/INSERCAO-QUESTOES.md).
## Administração web

O backend também serve `/admin/`, com assets de `web-admin/` incorporados à compilação, login por código temporário e cookie HttpOnly. O painel reutiliza os contratos Android; revisões do catálogo têm ativação explícita em execução e snapshots históricos imutáveis no Mongo. Consulte [ADMIN-WEB.md](../docs/ADMIN-WEB.md). O build deve usar o contexto raiz do repositório, incluindo `web-admin/` e `shared/`.
