# 📱 Cogniva Android App (`android-app` `2.1.0` / `build 27`)

Aplicativo nativo Android construído em **Kotlin + Jetpack Compose (Material 3)** com persistência local **Room v6** (com schema exportado), criptografia de sessão no **Android Keystore**, contraste **WCAG 2.1 AA** e sincronização *offline-first* idempotente com o backend `cogniva-api`.

> **Invariante de Atualização OTA:** Embora toda a marca, banco de dados e autenticação pertençam ao **Cogniva 2.0**, o identificador `applicationId = "br.com.concursoprep.app"` em [`app/build.gradle.kts`](app/build.gradle.kts) é preservado intencionalmente para permitir atualização OTA contínua sobre instalações anteriores sem perda de dados locais.

---

## 1. Arquitetura do Cliente (`2.0.1`)

- **Autenticação Nativa, Modo Convidado e Direitos LGPD ([`AuthStore.kt`](app/src/main/java/br/com/concursoprep/app/data/AuthStore.kt), [`OnboardingScreen.kt`](app/src/main/java/br/com/concursoprep/app/ui/screens/OnboardingScreen.kt), [`AccountLinkForm.kt`](app/src/main/java/br/com/concursoprep/app/ui/screens/AccountLinkForm.kt), [`SettingsScreen.kt`](app/src/main/java/br/com/concursoprep/app/ui/screens/SettingsScreen.kt)):**
  - Início imediato como convidado (online ou offline) ou autenticação por e-mail e senha (`Entrar` / `Criar conta`).
  - Ao criar conta a partir de uma sessão convidada, o token atual é enviado em `POST /api/v1/auth/register`, promovendo o mesmo `user_id` sem perder histórico ou grades.
  - Fluxo nativo de exclusão definitiva de conta e dados pessoais (Art. 18 da LGPD — `DELETE /api/v1/auth/account` com confirmação explícita `"EXCLUIR"`), apagando credenciais cifradas e o banco SQLite local `cogniva-<id>.db` da conta excluída.
  - Tokens de sessão armazenados cifrados com `AES/GCM/NoPadding` via `AndroidKeyStore`, com `allowBackup="false"`, [`backup_rules.xml`](app/src/main/res/xml/backup_rules.xml) e [`data_extraction_rules.xml`](app/src/main/res/xml/data_extraction_rules.xml) impedindo extração ou backup em nuvem.
- **Persistência Room v6 Isolada por Conta e Simulado Durável ([`AppDatabase.kt`](app/src/main/java/br/com/concursoprep/app/data/local/AppDatabase.kt), [`ExamLocal.kt`](app/src/main/java/br/com/concursoprep/app/data/local/ExamLocal.kt), [`ConcursoRepository.kt`](app/src/main/java/br/com/concursoprep/app/data/repository/ConcursoRepository.kt)):**
  - Bancos separados por `ownerUserId` (`cogniva-<id>.db`), com migrações aditivas `1 → 2 → 3 → 4 → 5 → 6` e schema exportado em [`app/schemas/`](app/schemas/br.com.concursoprep.app.data.local.AppDatabase/6.json).
  - Isolamento entre falhas de `syncPendingProfile` e `syncPendingAttempts`, descarte explícito de itens em quarentena (`PendingReviewSheet.kt`), consultas em lote paginadas (`<= 400` parâmetros SQLite) e poda automática de sessões abandonadas há mais de 30 dias.
  - Gravação atômica do modo Simulado em escopo `NonCancellable` e recuperação pós-*process death* via `SavedStateHandle`.
- **Catálogo Unificado e Templates Globais ([`ExamEngine.kt`](app/src/main/java/br/com/concursoprep/app/domain/exam/ExamEngine.kt), [`ContestsScreen.kt`](app/src/main/java/br/com/concursoprep/app/ui/screens/ContestsScreen.kt), [`ExamManagementScreen.kt`](app/src/main/java/br/com/concursoprep/app/ui/screens/ExamManagementScreen.kt)):**
  - Empacota apenas `knowledge-catalog.json`, `exam-templates.json` e `domain-contracts.json` sincronizados de [`shared/`](../shared/) pela task `syncSharedContracts`.
  - Exibe banner de atualização disponível quando um template oficial evolui no servidor e a grade local foi personalizada pelo estudante, permitindo adotar o novo template sob confirmação.
  - Botão **Treinar Tópico** filtra a bateria diretamente pelo `canonicalTopicId` (`syllabusTopicId`) online e offline.
- **Atualização OTA sem TOCTOU e Hardening de Release ([`AppUpdateManager.kt`](app/src/main/java/br/com/concursoprep/app/data/update/AppUpdateManager.kt), [`app/build.gradle.kts`](app/build.gradle.kts), [`app/proguard-rules.pro`](app/proguard-rules.pro)):**
  - `AppUpdateManager` valida tamanho (`sizeBytes`), SHA-256 em tempo constante e certificados `GET_SIGNING_CERTIFICATES` sobre o descritor aberto antes de transferir os bytes para `PackageInstaller.Session`, limpando sessões órfãs com mais de 24h.
  - Build `release` com `isMinifyEnabled = true` e `isShrinkResources = true` (R8), `HttpLoggingInterceptor` restrito a `BuildConfig.DEBUG` com redação de cabeçalhos sensíveis, `readTimeout(65s)` para tutores IA, contraste **WCAG 2.1 AA** validado em [`ThemeContrastTest.kt`](app/src/test/java/br/com/concursoprep/app/ThemeContrastTest.kt) e assinatura APK Signature Scheme v2/v3.

---

## 2. Telas Principais (`ui/screens/`)

1. **`OnboardingScreen.kt`:** Boas-vindas ao Cogniva, escolha opcional entre estudo imediato ou conta por e-mail/senha, seleção dinâmica de templates de concursos e metas diárias.
2. **`TodayScreen.kt`:** Pulso diário, revisões SRS vencidas, Caderno de Erros, retomada de sessão pausada, sugestão de Tronco Comum e alerta OTA.
3. **`ContestsScreen.kt` & `ExamBuilderSheet.kt`:** Edital verticalizado por concurso ou tronco, treino por disciplina ou por tópico específico, modal de diff de edital publicado, janela estimada customizável (com tema `Theme.Cogniva.EdgeToEdgeDialog`) e controle de participação ativa na escada.
4. **`PracticeAndQuestionScreen.kt`:** Sessão imersiva em tela cheia, coleta de confiança pré-resposta com semântica TalkBack (`Role.RadioButton`), 8 tutores Cogniva AI com renderização Markdown segura (`https://` apenas), modo Simulado cronometrado e reporte de problemas isolado por conta.
5. **`ProgressScreen.kt`:** Índice de Prontidão vs. nota de corte histórica, Matriz 2×2 Confiança × Acerto, domínio por disciplina e barras diárias paginadas/roláveis (`pt-BR`, `UTC-3`).
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
