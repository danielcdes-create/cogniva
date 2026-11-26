# Documentação do Ecossistema Cogniva

Este diretório reúne as especificações arquiteturais, contratos normativos, políticas de segurança/privacidade, manuais operacionais e guias de administração da plataforma **Cogniva `2.4.4`** (`build 34`).

---

## 🗺️ Mapa de Documentação Viva

### 1. Arquitetura, Motores de Decisão, Segurança e Operação
- **[ARQUITETURA.md](ARQUITETURA.md):** Visão sistêmica da versão `2.4.4` — autenticação nativa (`Argon2id` + sessões revogáveis), banco `cogniva` 100% V2.4, módulo **Missões** (`/missoes/` + Android), Verificação Conceitual `CTV`, conformidade LGPD Art. 18, simetria Rust ↔ Kotlin (`contract_version: 5`), consulta contextual em camada e governança xAI.
- **[MODULO-MISSOES.md](MODULO-MISSOES.md):** Aprendizagem experiencial audiovisual declarativa (`shared/missions-catalog.json`), streaming `HTTP Range`, gráficos nativos e separação epistemológica entre percurso narrativo, decisões formativas e avaliação formal em questões humanas.
- **[MOTORES-DECISAO-CORE-LOOP.md](MOTORES-DECISAO-CORE-LOOP.md):** Especificação matemática da arquitetura em 3 camadas (`contract_version: 5`): Escassez & Diagnóstico de 3 Sinais (`< 15` questões + Transferência Cross-Banca), Simulador de Decisão sob Incerteza ($VE = 2p-1$ + curva de 300 min $p(t)$ com controle de dificuldade) e Protocolo de Entrada de 60 Segundos ("Terça · 22h").
- **[PRIVACY.md](../PRIVACY.md):** Política de Privacidade e Proteção de Dados Pessoais (LGPD — Lei 13.709/2018), detalhando minimização, transferência internacional controlada e exclusão física imediata de dados pessoais com preservação do acervo compartilhado.
- **[SECURITY.md](../SECURITY.md):** Política de Segurança, versões suportadas, invariantes criptográficos/operacionais e canal de relato responsável de vulnerabilidades.
- **[OPERACAO.md](OPERACAO.md):** Runbook de produção — configuração `.env`, concessão administrativa (`ADMIN_EMAILS` / `--grant-admin`), migração *read-only* `CNU → cogniva`, TLS + HSTS + rate limits IPv6 `/64`, promoção atômica, retenção de releases e rollback verificado.
- **[LICAO-APRENDIDA-OTA-APK.md](LICAO-APRENDIDA-OTA-APK.md):** Registro técnico sobre streaming binário direto de APKs (`no-transform`, `proxy_buffering off`), validação SHA-256 + tamanho no instalador Android e checklist pós-deploy.
- **[CHANGELOG.md](../CHANGELOG.md):** Notas de versão consolidadas do ecossistema.

### 2. Administração, Catálogo e Contratos de Dados
- **[ADMIN-WEB.md](ADMIN-WEB.md):** Arquitetura, segurança (`HttpOnly` + `Sec-Fetch-Site` + CSRF), acessibilidade WCAG 2.1 AA e fluxos do painel `/admin/` (templates globais, questões V2, **Reportes**, **Classificação IA** e catálogo versionado).
- **[GUIA-ADMINISTRACAO.md](GUIA-ADMINISTRACAO.md):** Guia passo a passo da Central de Ajuda do painel, exportado automaticamente de [`web-admin/help.js`](../web-admin/help.js) e validado em CI.
- **[ESTRUTURA-ESTUDOS.md](ESTRUTURA-ESTUDOS.md):** Hierarquia conceitual entre troncos de carreira, templates globais, grades pessoais, componentes por etapa, disciplinas, assuntos, subassuntos e questões.
- **[CATALOGO-CONHECIMENTOS.md](CATALOGO-CONHECIMENTOS.md):** Especificação do catálogo unificado [`shared/knowledge-catalog.json`](../shared/knowledge-catalog.json) (`catalog_version: 2`), templates globais [`shared/exam-templates.json`](../shared/exam-templates.json) e preservação de identidades.
- **[catalogo/CONHECIMENTOS.md](catalogo/CONHECIMENTOS.md):** Listagem completa de troncos, disciplinas, assuntos e subassuntos gerada por `scripts/knowledge_catalog.py --export`.
- **[SCHEMA-QUESTOES-V2.md](SCHEMA-QUESTOES-V2.md):** Contratos JSON Schema 2020-12 de questões 100% V2, hash canônico JCS/SHA-256 (RFC 8785), validação de URIs seguras `http(s)://`, modo preparatório `OPEN_ENDED` e compatibilidade por edital.
- **[INSERCAO-QUESTOES.md](INSERCAO-QUESTOES.md):** Fluxo individual e em lote (JSON/JSONL) no Android e no `/admin/`, recibos idempotentes e estruturação assistida por IA.
- **[PACOTES-QUESTOES.md](PACOTES-QUESTOES.md):** Guia passo a passo para modelar `Disciplina → Assunto → Subassunto` e criar/importar pacotes de questões 100% prontos (`CONFIRMED`, `CURRENT`, compatibilidade `DIRECT` e rastreamento de domínio).

### 3. Inteligência Artificial
- **[IA-XAI.md](IA-XAI.md):** Integração com a API xAI — obrigatoriedade de `XAI_MODEL`, delimitação XML contra injeção de prompt, privacidade de cache para notas pessoais, dupla proteção de cota em `UTC-3` e estorno automático em falhas.
