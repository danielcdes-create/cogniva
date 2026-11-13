# 📱 Cogniva Android App (`android-app` `2.0.0` / `build 25`)

Aplicativo nativo Android construído em **Kotlin + Jetpack Compose (Material 3)** com persistência local **Room v6**, criptografia de sessão no **Android Keystore** e sincronização *offline-first* idempotente com o backend `cogniva-api`.

> **Invariante de Atualização OTA:** Embora toda a marca, banco de dados e autenticação pertençam ao **Cogniva 2.0**, o identificador `applicationId = "br.com.concursoprep.app"` em [`app/build.gradle.kts`](app/build.gradle.kts) é preservado intencionalmente para permitir atualização OTA contínua sobre instalações anteriores sem perda de dados locais.

---

## 1. Arquitetura do Cliente (`2.0.0`)

- **Autenticação Nativa e Modo Convidado ([`AuthStore.kt`](app/src/main/java/br/com/concursoprep/app/data/AuthStore.kt), [`OnboardingScreen.kt`](app/src/main/java/br/com/concursoprep/app/ui/screens/OnboardingScreen.kt), [`AccountLinkForm.kt`](app/src/main/java/br/com/concursoprep/app/ui/screens/AccountLinkForm.kt)):**
  - Início imediato como convidado (online ou offline) ou autenticação por e-mail e senha (`Entrar` / `Criar conta`).
  - Ao criar conta a partir de uma sessão convidada, o token atual é enviado em `POST /api/v1/auth/register`, promovendo o mesmo `user_id` sem perder histórico ou grades.
  - Tokens de sessão armazenados cifrados com `AES/GCM/NoPadding` via `AndroidKeyStore`.
- **Persistência Room v6 Isolada por Conta ([`AppDatabase.kt`](app/src/main/java/br/com/concursoprep/app/data/local/AppDatabase.kt), [`ExamLocal.kt`](app/src/main/java/br/com/concursoprep/app/data/local/ExamLocal.kt), [`ConcursoApp.kt`](app/src/main/java/br/com/concursoprep/app/ConcursoApp.kt)):**
  - Bancos separados por `ownerUserId` (`cogniva-<id>.db`), com migrações aditivas `1 → 2 → 3 → 4 → 5 → 6`.
  - `MIGRATION_5_6` adiciona metadados de sincronização com templates globais (`templateSourceId`, `templateRevisionSynced`, `isCustomized`, `upstreamUpdateAvailable`) e serialização JSON segura para nomes de bancas contendo vírgulas.
  - Na troca de conta (`selectAccount`), o aplicativo aguarda `repository.awaitIdleForClose()` antes de encerrar a conexão SQLite anterior.
- **Catálogo Unificado e Templates Globais ([`ExamEngine.kt`](app/src/main/java/br/com/concursoprep/app/domain/exam/ExamEngine.kt), [`ContestsScreen.kt`](app/src/main/java/br/com/concursoprep/app/ui/screens/ContestsScreen.kt), [`ExamManagementScreen.kt`](app/src/main/java/br/com/concursoprep/app/ui/screens/ExamManagementScreen.kt)):**
  - Empacota apenas `knowledge-catalog.json`, `exam-templates.json` e `domain-contracts.json` sincronizados de [`shared/`](../shared/) pela task `syncSharedContracts`.
  - Exibe banner de atualização disponível quando um template oficial evolui no servidor e a grade local foi personalizada pelo estudante, permitindo adotar o novo template sob confirmação.
  - Botão **Treinar Tópico** filtra a bateria diretamente pelo `canonicalTopicId` (`syllabusTopicId`) online e offline.
- **Enxugamento e Hardening de Release ([`app/build.gradle.kts`](app/build.gradle.kts), [`app/proguard-rules.pro`](app/proguard-rules.pro)):**
  - Build `release` com `isMinifyEnabled = true` e `isShrinkResources = true` (R8), `HttpLoggingInterceptor` restrito a `BuildConfig.DEBUG` e assinatura APK Signature Scheme v3.

---

## 2. Telas Principais (`ui/screens/`)

1. **`OnboardingScreen.kt`:** Boas-vindas ao Cogniva, escolha opcional entre estudo imediato ou conta por e-mail/senha, seleção dinâmica de templates de concursos e metas diárias.
2. **`TodayScreen.kt`:** Pulso diário, revisões SRS vencidas, Caderno de Erros, retomada de sessão pausada, sugestão de Tronco Comum e alerta OTA.
3. **`ContestsScreen.kt` & `ExamBuilderSheet.kt`:** Edital verticalizado por concurso ou tronco, treino por disciplina ou por tópico específico, modal de diff de edital publicado, janela estimada customizável e controle de participação ativa na escada.
4. **`PracticeAndQuestionScreen.kt`:** Sessão imersiva em tela cheia, coleta de confiança pré-resposta, 8 tutores Cogniva AI, modo Simulado cronometrado e reporte de problemas isolado por conta.
5. **`ProgressScreen.kt`:** Índice de Prontidão vs. nota de corte histórica, Matriz 2×2 Confiança × Acerto, domínio por disciplina e heatmap diário (`UTC-3`).
6. **`SettingsScreen.kt` & `QuestionImportScreen.kt`:** Gestão de conta nativa Cogniva (login, criação de conta, logout), tema, sincronização offline-first, atualização OTA e módulo administrativo de inserção de questões V2 (visível para `userProfile.isAdmin`).

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
