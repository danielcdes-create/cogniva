# Changelog — Cogniva

Todas as mudanças arquiteturais e funcionais relevantes do ecossistema **Cogniva** são documentadas neste arquivo.

---

## [2.0.0] - 2026-11-12 (build 25)

Convergência arquitetural completa do ecossistema **Cogniva 2.0** (ADRs 01–12), unificando identidade, autenticação nativa, banco `cogniva` 100% V2, catálogo único de conhecimentos, governança de templates globais, curadoria administrativa e pipeline de promoção segura.

### Identidade, Infraestrutura e Segurança
- **Unificação de marca e runtime (`cogniva-api` `2.0.0`):** Banco MongoDB padronizado em `cogniva`, binário/container Rust renomeado para `cogniva-api`, Nginx consolidado em [`deploy/nginx-cogniva.conf`](deploy/nginx-cogniva.conf) com `COGNIVA_PUBLIC_ORIGIN` parametrizado, TLS IPv4/IPv6 e `certbot --deploy-hook` sem janelas de corrida.
- **Compatibilidade OTA Android preservada:** Mantido `applicationId = "br.com.concursoprep.app"` em [`android-app/app/build.gradle.kts`](android-app/app/build.gradle.kts) e o nome default de serviço `api-rust-core` em [`deploy/promote.py`](deploy/promote.py), garantindo atualização OTA transparente a partir da `1.4.17` (`build 24`).
- **Autenticação 100% nativa Cogniva (zero Telegram):** Remoção integral de fluxos, telas e flags de vínculo Telegram. Novo modelo nativo com modo convidado imediato (`POST /api/v1/auth/guest`), cadastro com upgrade *in-place* preservando o mesmo `user_id` (`POST /api/v1/auth/register`), login por e-mail e senha com hash `Argon2id` (`POST /api/v1/auth/login`), sessões revogáveis na coleção `sessions` (`Bearer` no Android com armazenamento cifrado via Android Keystore AES-GCM e cookie `HttpOnly`/`Secure`/`SameSite=Strict` + CSRF no `/admin/`) e concessão administrativa por `ADMIN_EMAILS` ou `cogniva-api --grant-admin <email>`.
- **Middleware de alta performance:** Substituição do buffer global de corpo em memória por `DefaultBodyLimit::max(2 * 1024 * 1024)` e cache `RwLock` com TTL de 10s (e invalidação imediata ao ativar catálogo) para snapshots de catálogo em execução.

### Catálogo Unificado, Templates Globais e Acervo 100% V2
- **Catálogo único (`shared/knowledge-catalog.json` v2):** Incorporação de `icon_key` nos troncos e `default_weight` nos vínculos de disciplinas, eliminando `shared/canonical-catalog.json` e o *split-brain* `v1`/`v2` entre Rust, Web Admin e Android.
- **Separação entre `exam_templates` e `user_exams`:** O painel `/admin/` administra templates globais (`shared/exam-templates.json`: `CPNU`, `RFB`, `BNDES`, `AFT`). Alunos sem customização recebem atualizações oficiais automaticamente; alunos com grade personalizada preservam suas edições intactas e visualizam aviso de atualização disponível (`upstream_update_available`) com adoção opcional (`POST /api/v1/exams/:id/adopt-template`). Sincronização atômica entre `is_active_in_ladder` e `usuarios.active_contest_ids`.
- **Normalização 100% V2 e hash único JCS (`contract_version: 3`):** Migração *read-only* do acervo de questões `CNU → cogniva` via [`scripts/migrate_cnu_to_cogniva.py`](scripts/migrate_cnu_to_cogniva.py), aposentadoria do hash binário legado e padronização universal em `content_revision` SHA-256 sobre JSON canônico RFC 8785 (JCS) em Python, Rust e Kotlin.
- **Questões `OPEN_ENDED` em modo preparatório explícito:** Validação completa com rubrica estruturada e hash JCS consistente, aviso claro de acervo preparatório no `/admin/` e no Android, e exclusão estrita das filas de prática objetiva.

### Curadoria Web Admin, IA xAI e Cliente Android
- **Curadoria completa no `/admin/`:** Persistência integral de `explanation`, `level`, `exam_year` e seletor explícito de `Validade` (`CURRENT`, `UNDER_REVIEW`, `OBSOLETE`, `UNKNOWN`) na edição V2; novas abas **Reportes** (com isolamento de reportes abertos por aluno denunciante) e **Classificação IA** (geração em lote e revisão humana sobre o catálogo ativo); aprovação multi-administrador de rascunhos `REVIEW_READY`.
- **Governança xAI:** Exigência explícita de `XAI_MODEL` quando `XAI_API_KEY` está configurada, teto global diário (`XAI_DAILY_GLOBAL_LIMIT`, padrão `500/dia`) somado à cota por conta (`XAI_DAILY_USER_LIMIT`, padrão `120/dia`), estorno automático de cota em falhas do provedor, `reqwest::Client` compartilhado e gravação de `ai_resource_meta` em todos os 8 tutores.
- **Cliente Android `2.0.0` enxuto e resiliente:** Migração Room `5 → 6` (metadados de templates e serialização JSON segura de bancas), filtro real por tópico em **Treinar Tópico** (`syllabusTopicId`), destravamento de rascunhos de importação em erros HTTP 4xx, drenagem segura de banco na troca de conta e minificação R8 (`isMinifyEnabled = true`, `isShrinkResources = true`) na build de release.
- **CI/CD e Higiene de Repositório:** Workflows ativos em `push` e `pull_request`, ensaio real de rollback Docker em CI sem `|| true`, verificação automática de links Markdown e limpeza de artefatos históricos obsoletos.

---

## [1.4.11 – 1.4.17] - Novembro de 2026 (Resumo Pré-Convergência)

- **1.4.17 (build 24):** Ajustes edge-to-edge no editor de concursos Android e centralização da ajuda estrutural.
- **1.4.16 (build 23):** Central de Ajuda pesquisável no `/admin/` sincronizada com [`docs/GUIA-ADMINISTRACAO.md`](docs/GUIA-ADMINISTRACAO.md).
- **1.4.13 – 1.4.15 (builds 20–22):** Painel administrativo web incorporado ao binário Rust, módulo de inserção individual/lote de questões V2 e metadados documentais de origem.
- **1.4.11 (build 18):** Isolamento definitivo das rotas binárias de APK fora de camadas de compressão dinâmica (`no-transform`, `proxy_buffering off`), documentado em [`docs/LICAO-APRENDIDA-OTA-APK.md`](docs/LICAO-APRENDIDA-OTA-APK.md).
