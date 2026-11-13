# Documentação do Ecossistema Cogniva

Este diretório reúne as especificações arquiteturais, contratos normativos, manuais operacionais e guias de administração da plataforma **Cogniva `2.0.0`**.

---

## 🗺️ Mapa de Documentação Viva

### 1. Arquitetura, Operação e Distribuição
- **[ARQUITETURA.md](ARQUITETURA.md):** Visão sistêmica da versão `2.0.0` — autenticação nativa (`Argon2id` + sessões revogáveis), banco `cogniva` 100% V2, separação `exam_templates` vs. `user_exams`, simetria Rust ↔ Kotlin e governança xAI.
- **[OPERACAO.md](OPERACAO.md):** Runbook de produção — configuração `.env`, concessão administrativa (`ADMIN_EMAILS` / `--grant-admin`), migração *read-only* `CNU → cogniva`, TLS IPv4/IPv6, promoção atômica e rollback verificado.
- **[LICAO-APRENDIDA-OTA-APK.md](LICAO-APRENDIDA-OTA-APK.md):** Registro técnico de engenharia sobre streaming binário direto de APKs (`no-transform`, `proxy_buffering off`), validação de certificados Android (API 28+) e checklist pós-deploy.
- **[CHANGELOG.md](../CHANGELOG.md):** Notas de versão consolidadas do ecossistema.

### 2. Administração, Catálogo e Contratos de Dados
- **[ADMIN-WEB.md](ADMIN-WEB.md):** Arquitetura, segurança (`HttpOnly` + CSRF), rotas e fluxos do painel `/admin/` (templates globais, questões V2, **Reportes**, **Classificação IA** e ativação colaborativa do catálogo).
- **[GUIA-ADMINISTRACAO.md](GUIA-ADMINISTRACAO.md):** Guia passo a passo da Central de Ajuda do painel, exportado automaticamente de [`web-admin/help.js`](../web-admin/help.js) e validado em CI.
- **[ESTRUTURA-ESTUDOS.md](ESTRUTURA-ESTUDOS.md):** Hierarquia conceitual entre troncos de carreira, templates globais, grades pessoais, componentes por etapa, disciplinas, assuntos, subassuntos e questões.
- **[CATALOGO-CONHECIMENTOS.md](CATALOGO-CONHECIMENTOS.md):** Especificação do catálogo unificado [`shared/knowledge-catalog.json`](../shared/knowledge-catalog.json) (`catalog_version: 2`), templates globais [`shared/exam-templates.json`](../shared/exam-templates.json) e preservação de identidades.
- **[catalogo/CONHECIMENTOS.md](catalogo/CONHECIMENTOS.md):** Listagem completa de troncos, disciplinas, assuntos e subassuntos gerada por `scripts/knowledge_catalog.py --export`.
- **[SCHEMA-QUESTOES-V2.md](SCHEMA-QUESTOES-V2.md):** Contratos JSON Schema 2020-12 de questões 100% V2, hash canônico JCS/SHA-256 (RFC 8785), revisão de validade, modo preparatório `OPEN_ENDED`, compatibilidade por edital e isolamento de reportes.
- **[INSERCAO-QUESTOES.md](INSERCAO-QUESTOES.md):** Fluxo individual e em lote (JSON/JSONL) no Android e no `/admin/`, recibos idempotentes e estruturação assistida por IA.

### 3. Inteligência Artificial
- **[IA-XAI.md](IA-XAI.md):** Integração com a API xAI — obrigatoriedade de `XAI_MODEL`, dupla proteção de cota (`XAI_DAILY_USER_LIMIT` + `XAI_DAILY_GLOBAL_LIMIT`), estorno em falhas, cache persistente com `ai_resource_meta` e curadoria de classificação V2.
