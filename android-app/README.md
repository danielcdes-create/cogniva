# 📱 COGNIVA Android App (`android-app`)

> **Registro histórico de 2026-10-31.** As seções abaixo descrevem a baseline anterior à recuperação. Para comportamento atual, versão, autenticação, preservação de filas e evidências, usar o [README principal](../README.md), [REMEDIACAO](../docs/REMEDIACAO.md) e [operação](../docs/OPERACAO.md). O cliente novo exige HTTPS e vínculo comprovado; ID/@username não autenticam. Build local não publica automaticamente. Não considerar os exemplos e garantias históricos abaixo como estado atual.

Aplicativo nativo Android construído em **Kotlin + Jetpack Compose (Material 3)** para o ecossistema **COGNIVA** (*Conhecimento em evolução · LEARN · PRACTICE · EVOLVE*).

---

## 1. Diretrizes de UX & Arquitetura Mobile

* **Identidade Visual COGNIVA (`ui/theme/Theme.kt`):**
  * Paleta oficial em **Azul-Marinho Profundo (`#071120`)**, **Azul Elétrico (`#0066FF` / `#38BDF8`)** e **Branco Puro (`#FFFFFF`)**, com suporte nativo a *Dark Mode* para estudo noturno sem fadiga visual.
  * Tipografia otimizada para leitura prolongada de provas, garantindo enunciados em **`22sp`** e cartões de alternativa com altura mínima de **`64dp`**.
  * Símbolo geométrico neural (`CognivaNeuralSymbol`) renderizado via `Canvas` e ícone adaptativo vetorial no Android (`res/drawable/ic_launcher_foreground.xml`).
* **Arquitetura Offline-First (`data/local/AppDatabase.kt` & `data/repository/ConcursoRepository.kt`):**
  * Armazena baterias de questões localmente no **Room** (`cached_questions`) para resolução no metrô/ônibus sem internet.
  * Registra cada tentativa na fila local (`pending_attempts`) e sincroniza em lote (`POST /api/v1/quiz/submit-batch`) via **WorkManager** (`OfflineSyncWorker.kt`) assim que a conexão é restabelecida.
* **Imersão Total em Questões e Simulados:**
  * A barra de navegação inferior (*Bottom Navigation Bar*) desaparece automaticamente durante sessões do **Cogniva Questions** e **Cogniva Exams**.

---

## 2. Telas e Módulos (`ui/screens/`)

1. `OnboardingScreen.kt`: Fluxo tático em 4 passos (vínculo com ID/@username do Bot Telegram, escolha de concursos em escada, metas diárias e ativação do Edital Verticalizado).
2. `TodayScreen.kt` (**Cogniva Planner · Learn**): Pulso diário, revisões espaçadas (SRS) e Caderno de Erros sempre no topo, retomada de sessão pausada, sugestão de *Tronco Comum* e contagens regressivas.
3. `ContestsScreen.kt` (**Cogniva Planner**): Árvore do Edital Verticalizado com selos de confiança clicáveis (*Bottom Sheet* de transparência das fontes) e modal da *Tela de Diff* (Previsto ➔ Edital Publicado).
4. `PracticeAndQuestionScreen.kt` (**Cogniva Questions · Exams · AI**):
   * Suporte a Múltipla Escolha e Certo/Errado (Cebraspe com aviso de penalidade).
   * Coleta de Nível de Confiança (`0..3`) pré-gabarito e Alerta de Ponto Cego quando há erro com alta confiança.
   * **Cogniva AI:** 8 tutores inteligentes exibidos em *Bottom Sheet* sem sair da questão.
   * **Cogniva Exams:** Modo Simulado com cronômetro regressivo, ausência de gabarito durante a prova e **Tela de Resultado do Simulado** (Nota Líquida, corte histórico e desempenho por matéria).
   * Modal de reporte de problemas com as 20 categorias oficiais (`VALID_PROBLEM_CATEGORIES`).
5. `ProgressScreen.kt` (**Cogniva Insights**): Índice de Prontidão vs. Nota de Corte Histórica, Matriz 2×2 Confiança × Acerto, barras por disciplina ordenadas por fraqueza e Heatmap diário.

---

## 3. Sistemática de Atualização OTA 100% Automatizada (CI/CD + App)

O aplicativo implementa o fluxo completo e automatizado de atualização OTA (*Over-The-Air*):

1. **Verificação Automática na Inicialização (`MainViewModel.init`):** Ao abrir o app, `checkApkUpdate()` consulta automaticamente `GET /api/v1/apk/info` no servidor e compara o `version_code` remoto com `BuildConfig.VERSION_CODE`.
2. **Download com Verificação Criptográfica SHA-256 (`GET /api/v1/apk/download`):** Baixa o binário em `context.cacheDir/updates/cogniva-update.apk`, calcula o hash SHA-256 local e rejeita o arquivo caso divirja do `sha256_checksum` oficial do backend.
3. **Instalação Nativa via `FileProvider`:** Verifica a permissão `REQUEST_INSTALL_PACKAGES` (`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`) e dispara o instalador nativo do Android para `br.com.concursoprep.app.fileprovider`.
4. **Publicação Automática via GitHub Actions (`deploy.yml`):** A cada release/tag (`v*`), o pipeline do GitHub Actions compila o APK Android com JDK 21 e envia automaticamente `app-debug.apk` e `output-metadata.json` via SCP para `/opt/apps/apk/` no Droplet DigitalOcean (`198.199.65.103`), sem precisar rodar nenhum comando manual.

### 📌 Procedimento Obrigatório a Cada Modificação no App

Sempre que realizar alterações em telas, regras de negócio ou recursos do app Android:

1. **Incrementar versão em `app/build.gradle.kts`:**
   ```kotlin
   versionCode = 4       // Incrementar sempre +1
   versionName = "1.2.1" // SemVer da nova release
   ```
2. **Validar compilação localmente:**
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   ./gradlew compileDebugKotlin
   ```
3. **Commit e Tag de versão:**
   ```powershell
   git add .
   git commit -m "fix(escopo): descricao da mudanca (vX.Y.Z)"
   git tag vX.Y.Z
   git push origin main
   git push origin vX.Y.Z
   ```
4. **Instalação local para testes imediatos (opcional):**
   ```powershell
   ./gradlew assembleDebug --no-daemon
   # APK gerado em: app/build/outputs/apk/debug/app-debug.apk
   ```

O endereço do backend de produção é configurado em `gradle.properties`:
```properties
DO_API_BASE_URL=http://198.199.65.103/api/v1/
```
