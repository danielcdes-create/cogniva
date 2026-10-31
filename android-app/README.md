# 📱 COGNIVA Android App (`android-app`)

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

## 3. Como Compilar via Linha de Comando

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd C:\Users\danie\Telebot\android-app
.\gradlew.bat assembleDebug
```

O endereço do backend de produção é configurado em `gradle.properties`:
```properties
DO_API_BASE_URL=http://198.199.65.103/api/v1/
```
