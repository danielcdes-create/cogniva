# Nota Técnica — Correção de UI (Insets, Modo Escuro, Tipografia, Alinhamento e Estabilidade FFI)

**Projeto:** app Android Kotlin + Rust (Cogniva)
**Dispositivo-alvo principal:** Samsung Galaxy S23 Ultra ($6.8\text{"}$ QHD+ Dynamic AMOLED 2X · LTPO $1\text{--}120\text{ Hz}$) · Android 17 (API 37) · One UI 9
**Data:** 2026-10-31
**Público:** LLM harness / agente de código autônomo que edita o repositório

---

## 0. Como usar esta nota (Regras de Execução do Agente)

Você é um agente de código operando de forma determinística. Esta nota define **diagnóstico, regras arquiteturais obrigatórias e critérios de aceite** para corrigir os problemas de UI/UX do Cogniva. Siga estritamente a ordem abaixo:

1. **Descubra antes de editar** (Seção 1). Não assuma versões de bibliotecas, nomes de pacotes ou o toolkit de UI sem inspecionar o repositório.
2. **Proibido adicionar novas dependências externas** no `libs.versions.toml` ou `build.gradle.kts` sem autorização explícita. Resolva os problemas utilizando as bibliotecas já declaradas no projeto ou APIs nativas do AndroidX / Jetpack Compose.
3. Aplique as **regras** (Seções 3, 4 e 5) de forma **sistêmica** (tema, tokens, componentes base, fronteira FFI e layout raiz), nunca tela por tela com remendos locais.
4. Verifique com a **matriz de testes em duas camadas** (Seção 6): primeiro os portões obrigatórios *offline* (build, testes unitários, lint) e, caso haja dispositivo conectado via ADB, a bateria instrumentada com restauração automática de estado (`trap`).
5. Só declare concluído quando o **Definition of Done** (Seção 7) estiver integralmente cumprido e documentado no formato da Seção 9.

> **Guardrail contra alucinação de API:** Não invente assinaturas de métodos do Compose ou AndroidX. Se uma API citada nesta nota tiver assinatura diferente na versão do Compose BOM instalada no projeto, inspecione as declarações do pacote local ou a documentação oficial correspondente à versão exata descoberta no Passo 0 e registre a adaptação no relatório final.

---

## 1. Passo 0 — Descoberta obrigatória (Somente Leitura)

Antes de modificar qualquer arquivo, execute buscas no repositório e registre:

- **Toolkit de UI:** Jetpack Compose, Views/XML ou híbrido? (Os exemplos desta nota usam Compose + Material 3; se houver telas em XML, aplique os mesmos princípios com `ConstraintLayout`, `WindowInsetsCompat`, `?attr/` e `Theme.Material3.DayNight`).
- **Configuração de Build:** `compileSdk`, `targetSdk`, `minSdk`, versão do Kotlin, Compose BOM, Material 3 e bibliotecas já presentes em `gradle/libs.versions.toml` (verifique especificamente se já existe renderizador de Markdown ou biblioteca de testes de screenshot como Roborazzi/Paparazzi).
- **Arquivos de Tema e Design System:** `Theme.kt`, `Color.kt`, `Type.kt`, `Shape.kt`, `themes.xml`, `values-night/`.
- **Inicialização e Manifesto:**
  - `MainActivity`: chama `enableEdgeToEdge()`? Como o layout raiz consome `WindowInsets`?
  - `AndroidManifest.xml`: `android:windowSoftInputMode`, `android:forceDarkAllowed`, `android:enableOnBackInvokedCallback`, `android:resizeableActivity`, `android:configChanges`.
- **Auditoria de Cores e Dimensões Literais (Grep):**
  - Cores hardcoded: `Color(0x`, `Color.Black`, `Color.White`, `Color.Gray`, `.background(Color`, `android:textColor="#`, `android:background="#`.
  - Alturas fixas perigosas em containers de texto: `.height(`, `.size(` aplicados em `Card`, `Row`, `Button` ou `Chip`.
- **Fronteira Rust $\leftrightarrow$ Kotlin:** Como os dados atravessam o FFI (UniFFI, JNI manual, protobuf/JSON)? Onde strings de UI, emojis e marcações Markdown (`**`) são montados? As structs geradas pelo binding FFI são passadas diretamente para funções `@Composable`?

---

## 2. Diagnóstico a partir dos screenshots

| # | Sintoma observado | Causa provável | Correção (seção) |
|---|---|---|---|
| 1 | Botão primário "Confirmar Resposta" cortado na borda inferior (telas de questão, claro e escuro) | Conteúdo rolável e CTA no mesmo fluxo vertical sem peso/scroll isolado; falta de consumo de `navigationBars`/`ime`; CTA fora do slot `bottomBar` do `Scaffold` | 3.1, 3.2 |
| 2 | Títulos da barra superior cortados no topo ("Questão 1 de 2 · Difícil", "Cogniva…") | Edge-to-edge ativo sem padding de `statusBars`/`displayCutout` (furo da câmera frontal do S23 Ultra). *Nota:* validar também se não houve recorte manual na captura | 3.1 |
| 3 | Modo escuro: títulos "Disponibilidade e Meta Diária", "Meta de questões por dia:", "Horas de estudo líquidas por dia:" quase invisíveis (texto preto sobre fundo escuro) | Container desenhado com `Modifier.background(...)` sem atualizar o `LocalContentColor` via `Surface`, ou uso de cor de texto literal escura | 3.3 |
| 4 | Modo escuro: opção selecionada (A) com texto escuro sobre azul escuro; chip selecionado ("50 questões/dia", "2h/dia") com baixo contraste | Cores de estado selecionado hardcoded ou uso de `primary` no fundo com `onSurface` no texto, sem par semântico `primaryContainer` / `onPrimaryContainer` | 3.3 |
| 5 | Card "Nível de confiança nesta resposta:" mais estreito que a tela; rótulo encosta/estoura o padding esquerdo; botões $2 \times 2$ com larguras diferentes; "Mais ou Menos" sobreposto/cortado | Card sem `fillMaxWidth()`; `Text` fora do padding interno do container; botões dimensionados pelo conteúdo sem `weight(1f)` na grade | 3.5 |
| 6 | Letras das alternativas (A, B, C…) com larguras de badge diferentes, deslocando o início do texto da alternativa (E começa mais à esquerda que A) | Badge circular/quadrado com largura `wrapContentWidth` dependendo da métrica do glifo da letra | 3.5 |
| 7 | Chips "Pedir Dica / Dr. Sincero / Simplificar Pergunta" quebram em duas linhas com vão vertical exagerado | Soma do `verticalArrangement = Arrangement.spacedBy(...)` com a margem invisível de $48\text{ dp}$ injetada automaticamente pelo `LocalMinimumInteractiveComponentSize` do Material 3 | 3.5 |
| 8 | Aba "BNDES" mais alta que as outras (emoji em cima, texto embaixo); abas desiguais | Rótulo quebrando em múltiplas linhas por falta de `maxLines = 1` / `ScrollableTabRow`; emoji embutido na string alterando a métrica de `lineHeight` | 3.4, 3.5 |
| 9 | Árvore de tópicos "└ ☐" desalinhada do texto em múltiplas linhas | Arte ASCII/Unicode usada na string para simular indentação hierárquica e checkbox | 3.5 |
| 10 | Botões "Treinar …" com larguras diferentes entre cards consecutivos | Botão com largura baseada em `wrapContentWidth` em vez de `fillMaxWidth()` dentro do card | 3.5 |
| 11 | Título e subtítulo do card "Continuar sessão…" colados verticalmente; ícone desalinhado | Falta de `lineHeight` explícito proporcional ao `fontSize`, ausência de `spacedBy` entre textos e `includeFontPadding` inconsistente | 3.4 |
| 12 | "Dr. Sincero" exibe `**Dr. Sincero:**` literal e emoji 🔥 duplicado (no cabeçalho e no corpo) | String pré-formatada em Markdown com emoji decorativo vinda do Rust/LLM e exibida em `Text` puro sem parser para `AnnotatedString` | 4 |
| 13 | Card OTA mostra "App atualizado" mas o botão "Atualizar APK" continua habilitado | Estado `enabled` do botão desconectado da expressão lógica `installedVersion != remoteVersion` | 5 |
| 14 | Texto geral muito grande, quebrando precocemente em várias linhas | "Zoom da tela" (*Screen Zoom*) e/ou "Tamanho da fonte" elevados na One UI 9 do S23 Ultra (reduzindo a largura lógica de $411\text{ dp}$ para $360\text{--}384\text{ dp}$ com `fontScale` alto) combinados com layouts rígidos | 3.1, 3.4, 3.6 |

---

## 3. Regras arquiteturais obrigatórias

### 3.1 Edge-to-edge, Insets e Geometria do S23 Ultra (Android 17 · One UI 9)

No Android 16 (API 36) e Android 17 (API 37), o modo **edge-to-edge é obrigatório** e o sistema ignora tentativas de opt-out. No Samsung Galaxy S23 Ultra rodando One UI 9, a janela do aplicativo convive com quatro elementos críticos de sistema:
1. **Câmera frontal perfurada (*punch-hole* central)** e barra de status no topo (`WindowInsets.statusBars` + `WindowInsets.displayCutout`).
2. **Barra de navegação** na base: pode operar por **Gestos** (barra fina com *gesture hint*) ou por **3 Botões** clássicos da One UI (`WindowInsets.navigationBars`).
3. **Alça do Painel Edge (*Edge Panel*) e zonas de gesto de voltar** nas bordas laterais (`WindowInsets.systemGestures`).
4. **Largura lógica variável por Zoom da Tela:** embora o painel físico seja QHD+ ($1440 \times 3088\text{ px}$, proporção $19.3:9$), o usuário da One UI 9 frequentemente ativa o "Zoom da tela", reduzindo a largura lógica disponível de $411\text{ dp}$ para $384\text{ dp}$ ou $360\text{ dp}$.

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // Obrigatório ANTES de super.onCreate / setContent
        super.onCreate(savedInstanceState)
        setContent {
            CognivaTheme {
                AppRoot()
            }
        }
    }
}
```

**Regras obrigatórias de Insets:**
- **Toda tela** usa `Scaffold` com `contentWindowInsets = WindowInsets.safeDrawing` (que engloba `statusBars`, `navigationBars`, `displayCutout` e `ime`).
- **Proibido** usar valores fixos em `dp` para compensar barras do sistema (ex.: `Modifier.padding(top = 24.dp)` ou `padding(bottom = 48.dp)`).
- **Teclado (IME) na One UI 9:** configure `android:windowSoftInputMode="adjustResize"` na `Activity` do manifesto e utilize `Modifier.imePadding()` na barra inferior ou no container rolável de telas com `TextField` / `OutlinedTextField`.
- **Proteção contra o Painel Edge da One UI:** nunca posicione controles arrastáveis horizontais (`Slider`, `SwipeToDismissBox`) colados na borda física ($0\text{ dp}$); mantenha sempre um `padding(horizontal = 16.dp)` mínimo além do `safeDrawing`.

### 3.2 Estrutura de tela: conteúdo rola, ação principal (CTA) fica ancorada e visível

Toda tela com ação primária ("Confirmar Resposta", "Continuar", "Finalizar Simulado") deve ancorar o CTA no slot `bottomBar` do `Scaffold`. O CTA **jamais** pode ficar dentro do `LazyColumn` ou `Column` rolável.

```kotlin
@Composable
fun QuestionScreen(
    state: QuestionUiState,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { QuestionTopBar(state) }, // TopAppBar do M3 já consome statusBars + displayCutout
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 3.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Button(
                        onClick = onConfirm,
                        enabled = state.canConfirm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.action_confirm_answer),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            /* Enunciado, ActionChipRow, Alternativas A-E, Card de Nível de Confiança */
        }
    }
}
```

**Regras de rolagem e visibilidade:**
- Qualquer tela cujo conteúdo possa exceder a altura útil em `fontScale = 2.0` ou em modo janela dividida da One UI 9 **deve** ser rolável (`LazyColumn` ou `Column(Modifier.verticalScroll(rememberScrollState()))`).
- O slot `bottomBar` garante que o botão "Confirmar Resposta" permaneça sempre visível e clicável acima da barra de gestos ou dos 3 botões da Samsung.

### 3.3 Modo escuro, tokens semânticos e contraste (WCAG AA)

A causa raiz dos textos "invisíveis" (pretos sobre fundo escuro) nos screenshots é desenhar fundos com `Modifier.background(cor)` sem atualizar o `LocalContentColor`, ou usar cores literais fora do `ColorScheme`.

1. **Fonte única de verdade de cores:** defina `lightColorScheme` e `darkColorScheme` completos em `Theme.kt`, preenchendo explicitamente todos os pares semânticos:
   - `background` / `onBackground`
   - `surface` / `onSurface`
   - `surfaceVariant` / `onSurfaceVariant`
   - `surfaceContainerLowest`, `surfaceContainerLow`, `surfaceContainer`, `surfaceContainerHigh`, `surfaceContainerHighest`
   - `primary` / `onPrimary` e `primaryContainer` / `onPrimaryContainer`
   - `secondary` / `onSecondary` e `secondaryContainer` / `onSecondaryContainer`
   - `error` / `onError` e `errorContainer` / `onErrorContainer`
   - `outline` e `outlineVariant`
2. **Proibição de literais de cor nas telas:** nenhum arquivo fora do pacote `ui/theme/` pode conter `Color(0x...)`, `Color.Black`, `Color.White` ou `Color.Gray`. Caso existam cores semânticas de domínio (ex.: acerto/erro/aviso de questão), crie uma classe `@Immutable data class ExtendedColors(...)` fornecida via `CompositionLocalProvider` com variantes clara e escura.
3. **Fundo + conteúdo sempre acoplados:** use `Surface`, `Card`, `ElevatedCard` ou `OutlinedCard` informando `color` (ou `containerColor`) e `contentColor` pareados. Se o uso de `Modifier.background(...)` for inevitável em um componente menor, envolva o conteúdo em `CompositionLocalProvider(LocalContentColor provides corOnCorrespondente)`.
4. **Desativar Dark Mode Forçado da One UI:** garanta `android:forceDarkAllowed="false"` no tema base em `res/values/themes.xml` para impedir que o algoritmo automático da Samsung inverta cores do Compose de maneira imprevisível.
5. **Cores dinâmicas (Material You):** se o app utilizar `dynamicDarkColorScheme` / `dynamicLightColorScheme` no Android 12+, valide se não há mistura de tokens dinâmicos com tokens estáticos. Na dúvida para manter a identidade visual da marca e garantir contraste determinístico, desative `dynamicColor` por padrão ou teste ambos os esquemas.
6. **Padrão obrigatório para estados selecionados (Alternativas e Chips de Meta Diária):**

```kotlin
@Composable
fun OptionCard(
    letter: String,
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val containerColor = if (selected) cs.primaryContainer else cs.surfaceContainerLow
    val contentColor = if (selected) cs.onPrimaryContainer else cs.onSurface
    val borderColor = if (selected) cs.primary else cs.outlineVariant

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(width = if (selected) 2.dp else 1.dp, color = borderColor),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            LetterBadge(letter = letter, selected = selected)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
```

7. **Contraste mínimo obrigatório (WCAG AA) — incluindo estados desabilitados translúcidos:**
   - Texto normal ($< 18\text{ sp}$ ou $< 14\text{ sp}$ bold): razão de contraste $\ge 4.5:1$.
   - Texto grande ($\ge 18\text{ sp}$ ou $\ge 14\text{ sp}$ bold), ícones funcionais, bordas de componentes selecionados e segmentos de barra de progresso: razão de contraste $\ge 3.0:1$.
   - Componentes desabilitados (como o botão "Confirmar Resposta" inativo ou "Atualizar APK" já atualizado): devem usar container e texto discerníveis (ex.: borda `outlineVariant` ou container `onSurface.copy(alpha = 0.12f)` com texto `onSurface.copy(alpha = 0.38f)`), garantindo legibilidade sem parecerem apagados por erro de renderização.
8. **Teste unitário obrigatório de contraste (suportando canal Alpha):**
   Como `ColorUtils.calculateContrast` falha se a cor de fundo tiver transparência e ignora o alpha do primeiro plano se não for composto antes, adicione e execute este teste na suíte unitária:

```kotlin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {

    /**
     * Compõe cores translúcidas sobre a superfície base opaca antes de medir o contraste WCAG.
     */
    private fun effectiveContrast(
        foreground: Color,
        container: Color,
        baseSurface: Color
    ): Double {
        val opaqueBg = if (container.alpha < 1f) {
            ColorUtils.compositeColors(container.toArgb(), baseSurface.toArgb())
        } else {
            container.toArgb()
        }
        val opaqueFg = if (foreground.alpha < 1f) {
            ColorUtils.compositeColors(foreground.toArgb(), opaqueBg)
        } else {
            foreground.toArgb()
        }
        return ColorUtils.calculateContrast(opaqueFg, opaqueBg)
    }

    @Test
    fun colorSchemes_meetWcagContrastRequirements() {
        listOf("Light" to LightColorScheme, "Dark" to DarkColorScheme).forEach { (name, cs) ->
            val textPairs = listOf(
                "onBackground/background" to Triple(cs.onBackground, cs.background, cs.background),
                "onSurface/surface" to Triple(cs.onSurface, cs.surface, cs.surface),
                "onSurface/surfaceContainer" to Triple(cs.onSurface, cs.surfaceContainer, cs.surface),
                "onSurfaceVariant/surfaceVariant" to Triple(cs.onSurfaceVariant, cs.surfaceVariant, cs.surface),
                "onPrimary/primary" to Triple(cs.onPrimary, cs.primary, cs.surface),
                "onPrimaryContainer/primaryContainer" to Triple(cs.onPrimaryContainer, cs.primaryContainer, cs.surface),
                "onSecondaryContainer/secondaryContainer" to Triple(cs.onSecondaryContainer, cs.secondaryContainer, cs.surface),
            )
            textPairs.forEach { (label, colors) ->
                val ratio = effectiveContrast(colors.first, colors.second, colors.third)
                assertTrue("$name [$label] contraste insuficiente: $ratio (< 4.5)", ratio >= 4.5)
            }

            // Verifica legibilidade mínima do estado desabilitado sobre surfaceContainer
            val disabledBg = cs.onSurface.copy(alpha = 0.12f)
            val disabledFg = cs.onSurface.copy(alpha = 0.38f)
            val disabledRatio = effectiveContrast(disabledFg, disabledBg, cs.surfaceContainer)
            assertTrue("$name [disabledButton] contraste muito baixo: $disabledRatio (< 2.5)", disabledRatio >= 2.5)
        }
    }
}
```

### 3.4 Tipografia, métricas de linha e resiliência à fonte da One UI

Na One UI 9, muitos usuários utilizam a fonte **SamsungOne** ou **Gothic Bold** com escala ampliada. Para impedir textos colados ou cortados:

- **Unidades estritas:** tamanho de fonte e `lineHeight` **sempre em `sp`**; paddings, tamanhos de ícones e raios de borda **sempre em `dp`**.
- **Proporção de `lineHeight` explícita em `Type.kt`:**
  - Corpo (`bodyLarge`, `bodyMedium`, `bodySmall`): `lineHeight` entre $1.4 \times$ e $1.5 \times$ o `fontSize` (ex.: `fontSize = 16.sp`, `lineHeight = 24.sp`), com `LineBreak.Paragraph`.
  - Títulos (`titleLarge`, `titleMedium`, `titleSmall`, `headlineSmall`): `lineHeight` entre $1.25 \times$ e $1.35 \times$ o `fontSize`.
  - Configure `PlatformTextStyle(includeFontPadding = false)` e `LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)` em todos os estilos da tipografia.
- **Zero emojis dentro de strings de rótulos/abas/badges:**
  - O glifo de emoji tem métricas verticais maiores que letras latinas, causando o desalinhamento da aba "BNDES" e do texto "Edital Publicado!".
  - Remova os emojis das strings estáticas de UI. Renderize o indicador visual separadamente usando `Icon(...)` ou um `Box(Modifier.size(10.dp).background(indicatorColor, CircleShape))` dentro de um `Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp))`.
- **Regras de quebra de linha por tipo de componente:**
  - **Rótulos curtos (botões, chips, abas):** `maxLines = 1`, `overflow = TextOverflow.Ellipsis`. Se o texto for longo demais em `fontScale > 1.3`, permita até `maxLines = 2` nos botões do seletor de confiança, centralizando o texto (`textAlign = TextAlign.Center`).
  - **Textos de conteúdo (enunciados, alternativas, explicações do Dr. Sincero):** **proibido** limitar `maxLines`. O container deve crescer verticalmente de forma livre (`Modifier.fillMaxWidth().wrapContentHeight()`).
  - **Textos em `Row`:** todo `Text` acompanhado de ícone, checkbox ou badge lateral **deve** receber `Modifier.weight(1f)` (ou `weight(1f, fill = false)` quando apropriado) para quebrar linha sem empurrar o elemento vizinho para fora da tela.

### 3.5 Alinhamento, padronização de componentes e correção do `FlowRow`

Unifique os padrões visuais através de componentes reutilizáveis e elimine variações locais:

1. **`LetterBadge` (Alternativas A–E):**
   - Tamanho estritamente fixo (`Modifier.size(36.dp)` com `CircleShape` ou `RoundedCornerShape(10.dp)`) e `contentAlignment = Alignment.Center`.
   - Garante que o texto de todas as alternativas (A, B, C, D, E) comece exatamente na mesma coordenada horizontal ($X$).
   - Se o usuário estiver com `fontScale >= 1.5`, escale o container proporcionalmente usando `with(LocalDensity.current) { 36.dp.coerceAtLeast(24.sp.toDp()) }`.

2. **`ActionChipRow` ("Pedir Dica / Dr. Sincero / Simplificar Pergunta") — Correção do vão vertical:**
   - **Causa técnica do bug:** os componentes `AssistChip` / `FilterChip` do Material 3 aplicam internamente `Modifier.minimumInteractiveComponentSize()`, que reserva $48\text{ dp}$ de altura mínima para uma caixa visual de $32\text{ dp}$ (adicionando $8\text{ dp}$ invisíveis acima e $8\text{ dp}$ abaixo). Quando colocados em uma `FlowRow` com `verticalArrangement = Arrangement.spacedBy(8.dp)`, o vão visual entre as linhas salta para $24\text{ dp}$ ($8 + 8 + 8\text{ dp}$).
   - **Correção obrigatória:** mantenha a área de toque de $48\text{ dp}$, mas use `verticalArrangement = Arrangement.spacedBy(0.dp)` (ou envolva o `FlowRow` ajustando o espaçamento visual controlado) e `horizontalArrangement = Arrangement.spacedBy(8.dp)`:

```kotlin
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestionActionChips(
    onHint: () -> Unit,
    onSincereDoctor: () -> Unit,
    onSimplify: () -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        // spacedBy(0.dp) vertical porque AssistChip já inclui 16.dp (8 top + 8 bottom)
        // de área de toque via LocalMinimumInteractiveComponentSize (48.dp total).
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        AssistChip(
            onClick = onHint,
            label = { Text("Pedir Dica", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Outlined.Lightbulb, contentDescription = null, Modifier.size(18.dp)) }
        )
        AssistChip(
            onClick = onSincereDoctor,
            label = { Text("Dr. Sincero", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Outlined.LocalFireDepartment, contentDescription = null, Modifier.size(18.dp)) }
        )
        AssistChip(
            onClick = onSimplify,
            label = { Text("Simplificar Pergunta", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, Modifier.size(18.dp)) }
        )
    }
}
```

3. **Abas de Concursos (CPNU / Receita Federal / BNDES):**
   - Use `PrimaryScrollableTabRow` (ou `ScrollableTabRow`) quando houver 3 ou mais abas com nomes extensos ou indicadores de status, evitando que abas estreitas espremam o texto em alturas diferentes.
   - Cada `Tab` deve ter `heightIn(min = 48.dp)` uniforme e conteúdo em uma única `Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp))` com ponto colorido (`Box` de $8\text{ dp}$) + `Text(maxLines = 1)`.

4. **Card "Nível de confiança nesta resposta:" (Grade $2 \times 2$ simétrica):**
   - O `Card` externo deve ter `Modifier.fillMaxWidth()`.
   - O container interno (`Column`) deve ter `Modifier.fillMaxWidth().padding(16.dp)` e `verticalArrangement = Arrangement.spacedBy(12.dp)`. O título "Nível de confiança nesta resposta:" fica **dentro** desse `Column` (respeitando os $16\text{ dp}$ de padding lateral).
   - Os 4 botões ("Chutei", "Mais ou Menos", "Confio", "Certeza") devem ser dispostos em duas `Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp))`, onde cada botão recebe `Modifier.weight(1f).heightIn(min = 48.dp)`. Dessa forma, todas as 4 células terão exatamente a mesma largura e altura.

5. **Árvore de Tópicos do Edital (Substituição de "└ ☐"):**
   - Remova caracteres Unicode estruturais (`└`, `├`, `☐`, `☑`) das strings.
   - Renderize cada nó da árvore como um componente `TopicTreeRow(depth: Int, title: String, isOfficial: Boolean, checked: Boolean, ...)`:
     - Indentação real via `Modifier.padding(start = (depth * 16).dp)`.
     - `Checkbox` nativo do Material 3 (ou `Icon` vetorial de $20\text{ dp}$).
     - `Text(title, modifier = Modifier.weight(1f))`.
     - Badge "Oficial" ao final da `Row` usando um `Surface` pequeno com `secondaryContainer` / `onSecondaryContainer`.

6. **Botões de Ação em Cards ("Treinar Direito do Trabalho", etc.):**
   - Todos os botões primários/secundários no rodapé de cards de disciplina devem usar `Modifier.fillMaxWidth().heightIn(min = 48.dp)`.

### 3.6 Layouts Adaptativos, Multi-Janela (DeX / Pop-up View) e Predictive Back no Android 17

No Android 17 (API 37) com One UI 9, o sistema força aplicativos a serem redimensionáveis e ignora bloqueios de orientação em telas grandes, Modo Tela Dividida, Janela Flutuante (*Pop-up View*) e **Samsung DeX**.

- **Preservação de Estado em Redimensionamento:** toda seleção do usuário na tela de questão (alternativa marcada, nível de confiança escolhido, dicas abertas, posição de scroll da `LazyColumn`) **deve** estar salva em `ViewModel` (com `SavedStateHandle`) ou `rememberSaveable`. Redimensionar a janela na One UI 9 ou girar o S23 Ultra **não pode** limpar a resposta selecionada.
- **Largura máxima em paisagem / DeX:** em janelas com largura $> 600\text{ dp}$, centralize o conteúdo da questão limitando a largura máxima de leitura (`Modifier.widthIn(max = 680.dp).align(Alignment.TopCenter)`), evitando linhas de texto excessivamente longas.
- **Gesto Voltar Preditivo (*Predictive Back*):**
  - Mantenha `android:enableOnBackInvokedCallback="true"` na tag `<application>` do `AndroidManifest.xml`.
  - Nos `ModalBottomSheet` (como o sheet de análise do "Dr. Sincero"), utilize o componente oficial do Material 3 sem interceptar o botão voltar com `BackHandler` manual que bloqueie a animação nativa de encolhimento do sheet na One UI 9. Garanta que o `ModalBottomSheet` consuma corretamente os insets inferiores (`windowInsets = BottomSheetDefaults.windowInsets`).

---

## 4. Contrato Rust $\leftrightarrow$ Kotlin, Renderização de Markdown e Estabilidade a $120\text{ Hz}$

### 4.1 Separação estrita entre Dados (Rust) e Apresentação (Kotlin)

O núcleo em Rust **não deve** devolver texto formatado como interface visual. Foi isso que causou a exibição literal de `**Dr. Sincero:**` e a duplicação do emoji 🔥.

- **No Rust:** retorne estruturas de dados semânticas limpas (ex.: `SincereFeedback { headline: String, body_markdown: String, severity: FeedbackSeverity }`).
  - Remova prefixos redundantes como `"🔥 **Dr. Sincero:**"` na camada Rust (ou sanitize na fronteira Kotlin caso o texto venha diretamente de um prompt LLM).
  - Aplique normalização Unicode (NFC), `.trim()` e colapse mais de duas quebras de linha consecutivas (`\n{3,}` $\rightarrow$ `\n\n`) em um único ponto de entrada.
- **Strings estáticas de UI:** pertencem exclusivamente ao `res/values/strings.xml` (e `values-pt-rBR/strings.xml` se houver localização) no módulo Android, nunca hardcoded dentro do código Rust.

### 4.2 Regra Determinística para Renderização de Markdown no Kotlin

Para eliminar os `**`, `*` e listas cruas sem quebrar o build do Gradle:

1. **Verifique no Passo 0** se o projeto já declara uma biblioteca de Markdown para Compose no `libs.versions.toml` (ex.: `multiplatform-markdown-renderer` ou `compose-markdown`). Se já existir no projeto, utilize-a envolvendo o componente no tema do app.
2. **Se NÃO houver biblioteca de Markdown no projeto:** **não adicione dependências externas**. Implemente um utilitário determinístico `parseSimpleMarkdownToAnnotatedString(raw: String): AnnotatedString` no pacote de UI Kotlin e cubra-o com testes unitários:
   - Remova prefixos duplicados no início do texto (ex.: `Regex("^\\s*(?:🔥\\s*)?(?:\\*\\*Dr\\.\\s*Sincero:?\\*\\*:?\\s*)", RegexOption.IGNORE_CASE)`).
   - Converta blocos `**texto**` em `SpanStyle(fontWeight = FontWeight.Bold)`.
   - Converta blocos `*texto*` em `SpanStyle(fontStyle = FontStyle.Italic)`.
   - Converta blocos `` `código` `` em `SpanStyle(fontFamily = FontFamily.Monospace, background = ...)`.
   - Converta linhas iniciadas por `- ` ou `* ` em listas com bullet point (`• `) e indentação adequada.

### 4.3 Estabilidade de Recomposição em Tela LTPO $120\text{ Hz}$ (S23 Ultra)

O painel Dynamic AMOLED 2X do S23 Ultra opera a $120\text{ Hz}$ ($8.33\text{ ms}$ por quadro). Tipos gerados automaticamente por geradores FFI (como UniFFI ou JNI) costumam ser tratados pelo compilador do Compose como **instáveis (*unstable*)**, forçando a recomposição de todos os itens da `LazyColumn` a cada quadro de rolagem.

- **Mapeamento na fronteira:** mapeie as structs do Rust no `ViewModel` / `Mapper` para classes de estado Kotlin anotadas com `@Immutable` (ou `@Stable`), utilizando listas imutáveis (ou garantindo que os tipos FFI estejam declarados no arquivo `compose_compiler_config.conf` se o projeto já o utilizar).
- **Chaves estáveis em listas:** toda chamada a `items(...)` dentro de `LazyColumn` (como a lista de alternativas, tópicos do edital ou cards de estudo) **deve** fornecer o parâmetro `key = { item -> item.id }`.

---

## 5. Correções funcionais pontuais observadas

- **Card de Atualização OTA:**
  - O estado da UI deve ser derivado de uma única fonte de verdade (`OtaUiState`).
  - Quando `installedVersion == remoteVersion` (ou `installedSha256 == remoteSha256`), o botão "Atualizar APK" deve ficar **desabilitado** (`enabled = false`, com rótulo "APK já atualizado") ou **oculto**, mantendo apenas o botão secundário `OutlinedButton("Verificar Versão")`.
  - Durante a verificação de rede, exiba um `CircularProgressIndicator` de $18\text{ dp}$ dentro do botão e desabilite cliques duplicados.
  - Exiba o hash truncado em `FontFamily.Monospace` com `maxLines = 1` e permita cópia via `SelectionContainer` ou clique longo.
- **Barras segmentadas de progresso no topo:**
  - Altura mínima de $6\text{ dp}$ com `CircleShape` nas extremidades.
  - Segmento ativo com `MaterialTheme.colorScheme.primary` e segmento inativo com `MaterialTheme.colorScheme.surfaceVariant` (garantindo contraste $\ge 3.0:1$ tanto no modo claro quanto no escuro).

---

## 6. Verificação e Matriz de Testes (Duas Camadas)

### Camada 1 — Portões Obrigatórios *Offline* (Sempre executados pelo Agente)

Estes passos não dependem de dispositivo físico e **devem passar com 100% de sucesso** antes de encerrar a tarefa:

1. **Testes do Core Rust (se aplicável):**
   ```bash
   cargo test
   ```
2. **Compilação, Testes Unitários (incluindo `ThemeContrastTest` e `MarkdownParserTest`) e Lint:**
   ```bash
   ./gradlew assembleDebug testDebugUnitTest lintDebug
   ```
3. **Auditoria de Regressão por Grep (deve retornar zero ocorrências fora do pacote de tema):**
   - Verificar ausência de `Color(0x`, `Color.Black`, `Color.White`, `Color.Gray` em `ui/screens/` e `ui/components/`.
   - Verificar ausência de `└`, `☐`, `**Dr. Sincero` nas strings e componentes de UI.
4. **Anotação `@PreviewMatrix` em todas as telas e componentes alterados:**
   ```kotlin
   @PreviewLightDark
   @PreviewFontScale
   @PreviewScreenSizes
   annotation class PreviewMatrix
   ```
   *(Se o projeto tiver Roborazzi ou Paparazzi configurado no `build.gradle.kts`, execute a tarefa de verificação/gravação de screenshots correspondente).*

### Camada 2 — Validação em Dispositivo S23 Ultra via ADB (Condicional e Segura)

Antes de rodar comandos `adb`, verifique se o dispositivo está conectado (`adb get-state`). Se retornar `device`, execute o script de verificação abaixo.

> **Regra Crítica de Segurança ADB:** Nunca altere `font_scale` ou `wm density` no S23 Ultra sem um bloco `trap` que restaure os valores originais do aparelho em caso de erro ou interrupção (`EXIT`, `INT`, `TERM`).

```bash
#!/usr/bin/env bash
set -euo pipefail

if ! adb get-state >/dev/null 2>&1; then
  echo "Nenhum dispositivo ADB conectado. Pulando Camada 2 (registrado no relatório)."
  exit 0
fi

ORIG_FONT_SCALE=$(adb shell settings get system font_scale | tr -d '\r')
ORIG_DENSITY=$(adb shell wm density | awk -F': ' '/Physical density/{p=$2} /Override density/{o=$2} END{print (o ? o : p)}' | tr -d '\r')

cleanup() {
  echo "Restaurando configurações originais do S23 Ultra..."
  adb shell settings put system font_scale "${ORIG_FONT_SCALE:-1.0}"
  adb shell wm density reset
  adb shell wm size reset
}
trap cleanup EXIT INT TERM

mkdir -p build/ui-evidence

for MODE in "no" "yes"; do
  adb shell cmd uimode night "$MODE"
  for SCALE in "1.0" "1.3" "2.0"; do
    adb shell settings put system font_scale "$SCALE"
    sleep 2
    adb exec-out screencap -p > "build/ui-evidence/screen_night-${MODE}_scale-${SCALE}.png"
  done
done

# Simula o "Zoom da tela" alto da One UI 9 no S23 Ultra (reduzindo largura lógica para ~360dp)
adb shell settings put system font_scale 1.3
adb shell wm density 560
sleep 2
adb exec-out screencap -p > "build/ui-evidence/screen_oneui_zoom_high.png"
```

**Cenários adicionais a validar no dispositivo/emulador:**
- Alternância entre navegação por **Gestos** e por **3 Botões** da One UI 9.
- Abertura do teclado virtual Samsung (IME) sem cobrir o campo de entrada ou o CTA.
- Redimensionamento da tela em **Modo Tela Dividida** / **Janela Pop-up** verificando se a alternativa selecionada na questão é preservada.

---

## 7. Definition of Done (Critérios de Aceite)

A tarefa só pode ser declarada concluída quando **todos** os itens abaixo forem verdadeiros:

- [ ] **CTAs visíveis e ancorados:** botão "Confirmar Resposta" e demais ações principais permanecem 100% visíveis e clicáveis no `bottomBar` em modo claro/escuro, com `fontScale` de $1.0$ a $2.0$, tanto em gestos quanto em 3 botões.
- [ ] **Insets de topo respeitados:** nenhum título de `TopAppBar` colide com a barra de status ou com o recorte da câmera frontal do S23 Ultra.
- [ ] **Contraste WCAG AA comprovado por teste:** `ThemeContrastTest` passa garantindo contraste $\ge 4.5:1$ para texto normal e $\ge 3.0:1$ para elementos gráficos/texto grande nos temas claro e escuro, incluindo estados selecionados e desabilitados.
- [ ] **Alinhamento geométrico perfeito:** badges das alternativas A–E com tamanho fixo ($36\text{ dp}$) alinhando o início do texto na mesma coluna; seletor de confiança $2 \times 2$ ocupando 100% da largura do card com 4 botões de dimensões idênticas; `FlowRow` de chips sem vão vertical duplicado.
- [ ] **Zero artefatos de texto:** nenhuma sintaxe Markdown crua (`**`, `*`, `#`) visível na UI; nenhum emoji duplicado ou embutido em rótulos de abas; árvore de tópicos usando componentes reais de layout em vez de `"└ ☐"`.
- [ ] **Estado resiliente e $120\text{ Hz}$ estável:** modelos de UI anotados com `@Immutable`, estado preservado via `ViewModel` / `rememberSaveable` ao rotacionar ou redimensionar janela na One UI 9, e card OTA desabilitando o botão de atualização quando `installed == remote`.
- [ ] **Build limpo:** `./gradlew assembleDebug testDebugUnitTest lintDebug` executa sem erros e a auditoria de cores literais fora do pacote de tema retorna zero ocorrências.

---

## 8. Anti-padrões expressamente proibidos

- Trocar a cor de um `Text` isolado na tela para "consertar" o modo escuro em vez de corrigir o par `containerColor` / `contentColor` no `Surface` ou no `ColorScheme`.
- Adicionar bibliotecas externas ao Gradle sem verificar se o problema pode ser resolvido com `AnnotatedString` ou APIs já instaladas.
- Usar `Modifier.padding(top = 24.dp)` ou `padding(bottom = 48.dp)` fixos para contornar barras de sistema do S23 Ultra.
- Aplicar `.height(...)` fixo em cards, botões, abas ou chips que contenham texto (use sempre `heightIn(min = ...)`).
- Colocar o botão de ação principal ("Confirmar Resposta") dentro da `LazyColumn` ou `Column` rolável.
- Usar `verticalArrangement = Arrangement.spacedBy(8.dp)` em uma `FlowRow` de `AssistChip`/`FilterChip` sem descontar os $16\text{ dp}$ verticais implícitos do `LocalMinimumInteractiveComponentSize`.
- Passar structs geradas pelo binding Rust diretamente para funções `@Composable` sem mapeamento para tipos `@Immutable`.
- Executar `adb shell wm density` ou `settings put system font_scale` sem `trap` de restauração automática.

---

## 9. Formato do relatório final que o agente deve devolver

Ao concluir as alterações, apresente o relatório final exatamente com estas 5 seções:

1. **Descobertas do Passo 0:** toolkit identificado, versões de SDK/Compose BOM, estado original do tema, insets e fronteira FFI Rust $\leftrightarrow$ Kotlin.
2. **Matriz de Resolução (Item a Item):** tabela relacionando os 14 sintomas da Seção 2 $\rightarrow$ causa raiz confirmada no código $\rightarrow$ arquivo(s) e componente(s) alterados.
3. **Resultados da Camada 1 (Offline):** saída resumida dos testes unitários (`ThemeContrastTest`, `MarkdownParserTest`), build e auditoria grep de cores literais.
4. **Resultados da Camada 2 (Dispositivo / Previews):** evidências de captura antes/depois (ou registro explícito caso nenhum dispositivo ADB estivesse conectado no ambiente de execução).
5. **Riscos Residuais:** qualquer comportamento específico da One UI 9 / Android 17 que dependa de validação visual humana no hardware físico.