# 📱 Cogniva Android App (`android-app` `2.4.2` / `build 32`)

Aplicativo nativo Android construído em **Kotlin + Jetpack Compose (Material 3)** com persistência local **Room v7** (com schema exportado em `7.json`), **Módulo Missões Audiovisual Interativo** (`MissionsScreen.kt`), **Motores Simétricos de Decisão & Curva de 300 Minutos** (`PerformanceEngines.kt`, `contract_version: 5`), **Protocolo de Entrada de 60 Segundos ("Terça · 22h")**, **Consulta Contextual em Camada (Catálogo + IA + Google Partial Custom Tab)**, criptografia de sessão no **Android Keystore**, contraste **WCAG 2.1 AA** e sincronização *offline-first* idempotente com o backend `cogniva-api`.

> **Invariante de Atualização OTA:** Embora toda a marca, banco de dados e autenticação pertençam ao **Cogniva 2.0**, o identificador `applicationId = "br.com.concursoprep.app"` em [`app/build.gradle.kts`](app/build.gradle.kts) é preservado intencionalmente para permitir atualização OTA contínua sobre instalações anteriores sem perda de dados locais.

---

## 1. Arquitetura do Cliente (`2.4.2`)

- **Autenticação Nativa, Modo Convidado e Direitos LGPD ([`AuthStore.kt`](app/src/main/java/br/com/concursoprep/app/data/AuthStore.kt), [`OnboardingScreen.kt`](app/src/main/java/br/com/concursoprep/app/ui/screens/OnboardingScreen.kt), [`AccountLinkForm.kt`](app/src/main/java/br/com/concursoprep/app/ui/screens/AccountLinkForm.kt), [`SettingsScreen.kt`](app/src/main/java/br/com/concursoprep/app/ui/screens/SettingsScreen.kt)):**
  - Início imediato como convidado (online ou offline) ou autenticação por e-mail e senha (`Entrar` / `Criar conta`).
  - Ao criar conta a partir de uma sessão convidada, o token atual é enviado em `POST /api/v1/auth/register`, promovendo o mesmo `user_id` sem perder histórico ou grades.
  - Fluxo nativo de exclusão definitiva de conta e dados pessoais (Art. 18 da LGPD — `DELETE /api/v1/auth/account` com confirmação explícita `"EXCLUIR"`), apagando credenciais cifradas e o banco SQLite local `cogniva-<id>.db` da conta excluída.
  - Tokens de sessão armazenados cifrados com `AES/GCM/NoPadding` via `AndroidKeyStore`, com `allowBackup="false"`, [`backup_rules.xml`](app/src/main/res/xml/backup_rules.xml) e [`data_extraction_rules.xml`](app/src/main/res/xml/data_extraction_rules.xml) impedindo extração ou backup em nuvem.
- **Persistência Room v7 Isolada por Conta, Simulado Durável e Motores Simétricos ([`AppDatabase.kt`](app/src/main/java/br/com/concursoprep/app/data/local/AppDatabase.kt), [`ExamLocal.kt`](app/src/main/java/br/com/concursoprep/app/data/local/ExamLocal.kt), [`PerformanceEngines.kt`](app/src/main/java/br/com/concursoprep/app/domain/exam/PerformanceEngines.kt), [`ConcursoRepository.kt`](app/src/main/java/br/com/concursoprep/app/data/repository/ConcursoRepository.kt)):**
  - Bancos separados por `ownerUserId` (`cogniva-<id>.db`), com migrações aditivas `1 → 2 → 3 → 4 → 5 → 6 → 7` e schema exportado em [`app/schemas/`](app/schemas/br.com.concursoprep.app.data.local.AppDatabase/7.json), persistindo telemetria de sessão, confiança explícita, evidência selecionada e omissão estratégica (`isBlank`).
  - Execução local *offline-first* dos motores de Diagnóstico, Retenção Contínua, Priorização (`ADAPTIVE_LOOP` 40/40/20), Calibração Beta-Binomial, Estratégia (`STRATEGY_BATTERY` / Holdout 20% / Fallback Cross-Banca) e Fadiga.
  - Isolamento entre falhas de `syncPendingProfile` e `syncPendingAttempts`, descarte explícito de itens em quarentena (`PendingReviewSheet.kt`), consultas em lote paginadas (`<= 400` parâmetros SQLite) e poda automática de sessões abandonadas há mais de 30 dias.
  - Gravação atômica do modo Simulado em escopo `NonCancellable` e recuperação pós-*process death* via `SavedStateHandle`.
- **Catálogo Unificado e Templates Globais ([`ExamEngine.kt`](app/src/main/java/br/com/concursoprep/app/domain/exam/ExamEngine.kt), [`ContestsScreen.kt`](app/src/main/java/br/com/concursoprep/app/ui/screens/ContestsScreen.kt), [`ExamManagementScreen.kt`](app/src/main/java/br/com/concursoprep/app/ui/screens/ExamManagementScreen.kt)):**
  - Empacota `knowledge-catalog.json`, `knowledge-concepts-seed.json`, `exam-templates.json` e `domain-contracts.json` sincronizados de [`shared/`](../shared/) pela task `syncSharedContracts`.
  - Exibe banner de atualização disponível quando um template oficial evolui no servidor e a grade local foi personalizada pelo estudante, permitindo adotar o novo template sob confirmação.
  - Botão **Treinar Tópico** filtra a bateria diretamente pelo `canonicalTopicId` (`syllabusTopicId`) online e offline.
- **Atualização OTA sem TOCTOU e Hardening de Release ([`AppUpdateManager.kt`](app/src/main/java/br/com/concursoprep/app/data/update/AppUpdateManager.kt), [`app/build.gradle.kts`](app/build.gradle.kts), [`app/proguard-rules.pro`](app/proguard-rules.pro)):**
  - `AppUpdateManager` valida tamanho (`sizeBytes`), SHA-256 em tempo constante e certificados `GET_SIGNING_CERTIFICATES` sobre o descritor aberto antes de transferir os bytes para `PackageInstaller.Session`, limpando sessões órfãs com mais de 24h.
  - Build `release` com `isMinifyEnabled = true` e `isShrinkResources = true` (R8), `HttpLoggingInterceptor` restrito a `BuildConfig.DEBUG` com redação de cabeçalhos sensíveis, `readTimeout(65s)` para tutores IA, contraste **WCAG 2.1 AA** validado em [`ThemeContrastTest.kt`](app/src/test/java/br/com/concursoprep/app/ThemeContrastTest.kt) e assinatura APK Signature Scheme v2/v3.

---

## 2. Telas Principais (`ui/screens/`)

1. **`OnboardingScreen.kt`:** Boas-vindas ao Cogniva, escolha opcional entre estudo imediato ou conta por e-mail/senha, seleção dinâmica de templates de concursos e metas diárias.
2. **`TodayScreen.kt`:** **Protocolo de Entrada de 60 Segundos ("Terça · 22h")** — tela inicial livre de metas atrasadas ou cobrança de sequência, exibindo proposta reversível (*"Só uma questão"* / **`Resolver 1 questão`** / **`Sem estudar hoje`**), atalho opcional para o **Simulador de Decisão (8–12 min)**, retomada de sessão pausada e alerta OTA.
3. **`ContestsScreen.kt` & `ExamBuilderSheet.kt`:** Edital verticalizado por concurso ou tronco, treino por disciplina ou por tópico específico, modal de diff de edital publicado, janela estimada customizável (com tema `Theme.Cogniva.EdgeToEdgeDialog`) e controle de participação ativa na escada.
4. **`PracticeAndQuestionScreen.kt` & `ContextualLookupSheet.kt`:** Sessão imersiva em tela cheia, seleção nativa de texto no enunciado + chip **`Consultar / Google`** abrindo **Camada de Consulta Contextual** (fontes normativas do catálogo, síntese enciclopédica, explicação IA sem spoiler e **Pesquisa Google / Planalto / STF-STJ em Camada** via Partial Custom Tab), botão **Deixar em Branco (Omitir · 0 pt)**, **Ficha Editorial Rastreável**, **Rastreabilidade dos 3 Sinais** (em acertos e erros, detectando `ILLUSORY_COMPETENCE`), **Simulador de Decisão sob Incerteza** e saída **`Encerrar`** ou **`Resolver outra`**.
5. **`ProgressScreen.kt`:** Prontidão sob **Regra de Escassez `< 15` Questões Humanas**, **Mapa de Transferência Cross-Banca (`TRANSFER_PROBE` / `TRANSFER_UNMEASURED`)**, **Diagnóstico dos 3 Sinais & Omissões Estratégicas**, **Decisão sob Incerteza ($VE = 2p - 1$ e cenários de 300 min)**, **Curva Individual de 300 Minutos $p(t)$ com Controle de Dificuldade**, **Métricas Voluntárias do Protocolo 60s** e evidência por disciplina (`X/15`).
6. **`SettingsScreen.kt` & `QuestionImportScreen.kt`:** Gestão de conta nativa Cogniva (login, criação de conta, logout e exclusão definitiva LGPD Art. 18 — [Política de Privacidade](../PRIVACY.md)), tema, sincronização offline-first, atualização OTA e módulo administrativo de inserção de questões V2 (visível para `userProfile.isAdmin`).

---

## 3. Compilação e Testes

Requer **JDK 17** (ou Temurin 21 conforme configurado no ambiente) e Android SDK `compileSdk = 35`:

```bash
cd android-app

# Sincronizar contratos compartilhados e rodar testes unitários JVM + Lint
./gradlew :app:testDebugUnitTest :app:lintDebug

# Verificar snapshots visuais Paparazzi
./gradlew :app:verifyPaparazziDebug

# Gerar APK de release otimizado com R8
./gradlew :app:assembleRelease
```
