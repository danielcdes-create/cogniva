# Nota Técnica — Screenshot JVM, emulador headless e hash de APK

**Projeto de origem:** Cogniva (`android-app` Kotlin/Compose + `backend-rust` Axum)
**Data:** 2026-10-31
**Público:** quem for repetir regressão visual, teste de insets no GitHub Actions ou metadados de APK neste repositório ou num cliente Android parecido
**Estado que esta nota descreve:** commit `3acc765` em `main` (`https://github.com/danielcdes-create/cogniva`)

Esta nota registra o caminho que funcionou e os becos que custaram horas. O objetivo é o próximo agente começar pelo caminho que passa.

---

## 0. O que esta nota cobre

Três portões, na ordem em que devem ser provados:

1. Uma biblioteca JVM de screenshot (aqui, Paparazzi) renderiza a UI real anotada com `@PreviewMatrix`, inclusive a variante chamada exatamente `Font Scale 2.0`. O verify falha quando o bitmap diverge e a árvore de baselines volta limpa.
2. Um job `workflow_dispatch` no GitHub Actions sobe um emulador headless na geometria pedida e o próprio job falha se a top bar ou o CTA colidir com o inset. YAML lido localmente e emulador local não contam.
3. `GET /api/app/latest-apk` no router Axum de verdade devolve `size_bytes` e `sha256_checksum` calculados dos bytes do arquivo. Duas leituras do mesmo arquivo devolvem o mesmo corpo.

Não reabrir os dez defeitos de layout já corrigidos na nota de UI de 2026-10-31. Não compilar no Droplet. Não adicionar Roborazzi e Paparazzi juntos.

---

## 1. Ambiente desta máquina

| Fato | Valor que funcionou |
| --- | --- |
| JDK do Gradle | Temurin 21 em uso explícito. O JBR do Android Studio nesta máquina é JDK 25; Robolectric 4.14 não carrega class file major 69. |
| Onde rodar Gradle | `C:\Users\danie\Cogniva\android-app`. O diretório padrão do shell é `C:\Users\danie` e não tem projeto. |
| SDK | `C:\Users\danie\AppData\Local\Android\Sdk`. `android-app/local.properties` está no `.gitignore`. Não commitar `sdk.dir`. |
| Rust | `Set-Location C:\Users\danie\Cogniva\backend-rust` antes de `cargo`. Não apontar `CARGO_HOME`, `RUSTUP_HOME` nem `HOME` para pasta temporária. |
| Git | Windows sem `core.longpaths`. Caminho acima de ~260 caracteres faz `git add` abortar a pasta inteira e não deixar nada no índice. |

Defina `JAVA_HOME` só na sessão do comando:

```powershell
$env:JAVA_HOME = "<temurin-21>"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
Set-Location C:\Users\danie\Cogniva\android-app
.\gradlew.bat :app:verifyPaparazziDebug --tests br.com.concursoprep.app.QuestionPreviewMatrixScreenshotTest --no-daemon --console=plain
```

---

## 2. Screenshot JVM

### 2.1 Escolha da biblioteca

Neste repositório a escolha fechada é **Paparazzi 1.3.5**. Motivos observados, para não repetir a volta:

- Roborazzi 1.76 exige metadados de Kotlin 2.3. O projeto está em Kotlin 2.1.
- Roborazzi 1.55 compila, mas o Windows Defender/McAfee bloqueia `robolectric-nativeruntime.dll` em `%TEMP%` com a mensagem de que o arquivo contém vírus. O teste não chega a comparar pixels.
- Paparazzi 2.0.0-alpha05 quebra o resource merger do AGP 8.12. A alpha 2.0.0-alpha04 pede um `aapt2` 8.13 que não está disponível.
- Forçar um layoutlib mais novo em cima do Paparazzi 1.3.5 faz `Bridge.init` voltar falso.

Uma biblioteca só. A outra fica de fora do `build.gradle.kts`.

### 2.2 compileSdk e dispositivo

Paparazzi 1.3.5 usa layoutlib que não contém `android.os.Build.VERSION_CODES_FULL`. Com `compileSdk = 36`, `Renderer.configureBuildProperties` lança `NoSuchElementException` na inicialização. **compileSdk 35** é o que inicializa. O `targetSdk` do app continua 35.

Não passe largura e altura arbitrárias no `DeviceConfig`. Isso também dispara `NoSuchElementException` no renderer. Use o aparelho de estoque e altere só o que o teste precisa:

```kotlin
DeviceConfig.PIXEL.copy(
    fontScale = info.fontScale,
    nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT,
)
```

`NightMode` é `com.android.resources.NightMode`, não um tipo aninhado em `DeviceConfig`.

`appCompatEnabled = true` procura `androidx.appcompat.R$drawable` e falha com `ClassNotFoundException` neste app, que não usa AppCompat. Deixe `appCompatEnabled = false` e o tema `android:Theme.Material.Light.NoActionBar`.

### 2.3 A matriz tem de ser a UI de verdade

`@PreviewMatrix` vive no pacote de tema. O teste varre `br.com.concursoprep.app.ui` com `AndroidComposablePreviewScanner` e `includeIfAnnotatedWithAnyOf(PreviewMatrix::class.java)`. O teste falha na configuração se não existir uma preview cujo nome seja exatamente `Font Scale 2.0` e cujo `fontScale` seja `2.0f`.

A preview tem de montar `QuestionSessionScreen` (CTA, `LetterBadge`, grade de confiança, chips). A grade de confiança só aparece com uma alternativa selecionada: o parâmetro `initialSelectedOptionIndex` existe para isso. Um composable folha que não é a tela da questão não satisfaz o portão.

### 2.4 Reporter do Paparazzi no Gradle 9.3

Paparazzi 1.3.5 troca o `TestReporter` do Gradle por um HTML reporter que chama `TestResultsProvider.hasOutput(long, Destination)`. Esse método saiu no Gradle 9.3. O `:app:testDebugUnitTest` falha mesmo quando os pixels batem.

`doFirst` é LIFO: um `doFirst` escrito depois do plugin roda **antes** do `doFirst` do plugin, e o reporter quebrado volta. O que segurou foi inserir uma ação no índice 0, para ela rodar depois de todos os `doFirst` e antes da ação de teste. A classe `cogniva.NoopTestReporter` está em `android-app/buildSrc` e precisa ir no commit: o `app/build.gradle.kts` carrega essa classe na configuração, inclusive de tarefas que não são o screenshot.

O proxy devolve `0` em `hashCode`, `false` em `equals` e `null` no resto. O verify continua falhando quando o Paparazzi lança `IllegalStateException` de diferença de imagem. O reporter noop só impede a falha falsa do HTML.

### 2.5 Nome de arquivo no Windows

O nome padrão do parâmetro JUnit inclui o método completo da preview. No Cogniva isso passou de 230 caracteres só no arquivo, e o caminho inteiro estourou o limite do Git no Windows:

```text
error: open("...matchesApprovedBaseline[br.com.concursoprep.app.ui.screens....].png"): Filename too long
fatal: adding files failed
```

Nada é staged quando um arquivo da pasta falha. A correção usada:

- `@Parameterized.Parameters(name = "{index}")`
- nome do snapshot só com o nome da preview, em minúsculas, com o resto virando `_`

Os PNGs ficam em `app/src/test/snapshots/images/` com cerca de 100–114 caracteres no nome. Confira o caminho completo antes do `git add`. Apague os PNGs longos com prefixo `\\?\` se o `Remove-Item` comum não enxergar o arquivo.

O índice entra no nome do arquivo. A ordem do scanner precisa ser estável. O sufixo ainda é o nome da preview (`font_scale_2_0`, `dark_mode`, …), então um deslocamento de índice muda o arquivo e o verify acusa.

### 2.6 Como provar o portão

Rode `verifyPaparazziDebug` duas vezes, apagando `app/build/test-results/testDebugUnitTest` entre elas. Sem isso a segunda execução fica `UP-TO-DATE` e não reimprime a contagem. As duas têm de sair com o mesmo `tests=` e `failures=0`.

Depois substitua só o PNG `font_scale_2_0` por um PNG válido de outro tamanho (1×1 basta). O verify tem de sair com código diferente de zero. A mensagem observada foi `Images are different sizes`. Corromper o cabeçalho do PNG produz `ZipException` no ImageIO e não é o portão de pixel. Restaure o arquivo a partir de uma cópia fora da pasta de snapshots e confira o SHA-256. `git status` dessa pasta tem de voltar limpo.

Grave o log em UTF-8 sem bytes nulos. `Set-Content` do Windows PowerShell 5 grava UTF-16, e um auditor que usa busca binária trata o arquivo como ilegível. Em PowerShell 7, escreva com `UTF8Encoding($false)`.

Contagem observada neste repositório: 7 testes, 0 falhas, duas vezes; na divergência, 7 testes e 1 falha.

---

## 3. Emulador headless no GitHub Actions

### 3.1 O rótulo "Android 17 / API 35"

Android 17 é API 37. API 35 é Android 15. Nenhuma system image carrega os dois rótulos. Não rebatize o log. Suba a API numérica pedida (`android-35` / `google_apis` / `x86_64`) na geometria que o portão de inset exige:

- 1440 × 3088 px
- 500 dpi
- recorte `com.android.internal.display.cutout.emulation.hole`

O log imprime `ro.build.version.release` e `ro.build.version.sdk` reais. A frase de aceite é a que o aparelho devolveu, por exemplo:

```text
EMULATOR_BOOTED 15 35 Physical size: 1440x3088 Physical density: 500 punch-hole=hole
INSET_CHECK: success topBar=136.0 obstacle=136 ctaBottom=2975.0 nav=75 window=3088.0
```

`workflow_dispatch` só enxerga o arquivo depois que ele está no branch padrão (`main`). `gh workflow run` antes do push responde HTTP 404. Leia o log da run. Não marque o critério a partir do YAML.

O job fica em `.github/workflows/emulator-insets.yml`, só manual. O `deploy.yml` continua disparando por tag e por `workflow_dispatch`. Push em `main` não publica no Droplet.

### 3.2 O runner executa uma linha por shell

`reactivecircus/android-emulator-runner@v2` invoca cada linha do `script` e do `pre-emulator-launch-script` com `/usr/bin/sh`, que no Ubuntu do runner é dash. Consequências que já derrubaram o job:

- `set -o pipefail` morre com `Illegal option -o pipefail` antes de qualquer `adb`.
- Variável atribuída numa linha não existe na seguinte. `SIZE=$(...)` seguido de `echo "$SIZE"` imprime vazio e o `grep` falha mesmo depois de `wm size` ter funcionado.
- Heredoc Python de várias linhas não chega no Python. Cada linha vira um comando separado.

Cada verificação é uma linha fechada. `cd` também não sobrevive:

```yaml
script: |
  adb shell wm size 1440x3088
  adb shell wm density 500
  adb shell cmd overlay enable com.android.internal.display.cutout.emulation.hole
  adb shell cmd overlay list | tr -d '\r' | grep -E '\[x\].*com\.android\.internal\.display\.cutout\.emulation\.hole'
  adb shell wm size | tr -d '\r' | grep 1440x3088
  adb shell wm density | tr -d '\r' | grep 500
  echo EMULATOR_BOOTED $(adb shell getprop ro.build.version.release | tr -d '\r') $(adb shell getprop ro.build.version.sdk | tr -d '\r') $(adb shell wm size | tr -d '\r') $(adb shell wm density | tr -d '\r') punch-hole=hole
  cd android-app && chmod +x gradlew && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=br.com.concursoprep.app.QuestionInsetCollisionTest --no-daemon --console=plain
  adb logcat -d | tr -d '\r' | grep -E 'INSET_CHECK.*success'
```

O `grep` sem `-q` nas linhas de tamanho deixa `Physical size: 1440x3088` e `Physical density: 500` no log. O exit code do `grep` é o portão. Em pipeline, o status é o do último comando, então o `grep` no fim basta. Não dependa de `pipefail`.

Ajustar `config.ini` (`hw.lcd.width`, `hw.lcd.height`, `hw.lcd.density`) antes do boot faz o emulador nascer já em 1440×3088 e 500 dpi. Isso também tem de ser uma linha só. `wm size` / `wm density` depois do boot continuam sendo a medição que o log prova.

### 3.3 Filtro do teste instrumentado

`:app:connectedDebugAndroidTest` **não aceita** `--tests`. O Gradle 9.3 responde `Unknown command-line option '--tests'` e nem instala o APK. O filtro que funciona é:

```text
-Pandroid.testInstrumentationRunnerArguments.class=br.com.concursoprep.app.QuestionInsetCollisionTest
```

### 3.4 O que o teste de inset mede

A activity de prova é só de debug (`QuestionInsetProbeActivity`), chama `enableEdgeToEdge()` e mostra o mesmo conteúdo da preview. O teste lê `WindowInsetsCompat` de verdade.

`ViewCompat.getRootWindowInsets` e `requestApplyInsets` no decor view têm de rodar na main thread (`rule.runOnUiThread`). Na thread de instrumentação o aparelho lança `CalledFromWrongThreadException` e o teste nem chega na asserção.

O `testTag("question_top_bar")` fica **depois** de `windowInsetsPadding`. No Compose o primeiro modifier é o de fora. Se a tag estiver fora do padding, `boundsInWindow.top` é 0 e a asserção `top >= inset` falha com a tela visualmente correta. O CTA já está dentro do `bottomBar` com padding de `safeDrawing` na parte de baixo; a tag fica no próprio botão.

O teste exige inset superior e inset de navegação maiores que zero. Inset zero não é sucesso.

O formato do logcat no Android 15 é `I INSET_CHECK: success ...` ou `I/INSET_CHECK(...): success`. O `grep` do job usa `INSET_CHECK.*success` para aceitar os dois.

### 3.5 O que precisa estar no commit para o job compilar

O checkout do Actions compila o que está em `main`. Um workflow sozinho não basta. Entram juntos:

- a tela corrigida, com as tags e o CTA no `bottomBar`
- a activity de debug e o `AndroidManifest` de debug
- o teste instrumentado
- `buildSrc` com `NoopTestReporter`
- o plugin Paparazzi, porque a configuração do módulo o aplica mesmo no `connectedDebugAndroidTest`

Não entre `local.properties`, `README` com link `file:///` da máquina local, nem a nota solta se ela não for o objeto do commit.

---

## 4. Hash e tamanho do APK

A rota nova é `GET /api/app/latest-apk`. As rotas já entregues continuam: `GET /api/v1/apk/info` e `GET /api/v1/apk/download`.

`get_latest_apk` lê os bytes do caminho em `APK_STORAGE_PATH`. Se o arquivo não existe, responde 404. Tamanho e hash saem de `apk_info_value`, a mesma função da rota antiga: `apk_bytes.len()` e SHA-256 desses bytes. Não grave constante de tamanho nem de hash.

O teste usa `build_router`, o mesmo construtor do `main`, e `tower::ServiceExt::oneshot`. Duas chamadas no mesmo arquivo têm de devolver corpos idênticos. O SHA-256 do teste é calculado de novo sobre os bytes da fixture, não copiado do JSON.

`main()` precisa de URI do MongoDB para subir o processo. O teste da rota não disca o Atlas: `mongodb://127.0.0.1:27017` só precisa passar em `Client::with_uri_str`. Não exija cluster para esse portão.

A variável `COGNIVA_LATEST_APK_CAPTURE`, se existir, faz o teste gravar os dois corpos. Ela é só evidência. O assert não depende do arquivo.

Fixture usada: os 48 bytes `cogniva-fixture-apk-bytes-v1-not-a-constant-hash`. O SHA-256 correspondente é `4af8ee749bb7de425d31c5629365fde591d91a79a6ca36520705650336a074ef`. Se a fixture mudar, o hash muda. Recalcule. Não atualize o número à mão.

---

## 5. Ordem de trabalho que evita a volta

1. Confirme JDK 21, diretório do Gradle e diretório do Cargo.
2. Faça o screenshot passar localmente duas vezes e a divergência falhar com a pasta de PNG restaurada.
3. Encurte o nome dos PNG até o `git add` aceitar a pasta. Liste os arquivos restantes antes de commitar.
4. Suba para `main` o workflow **e** o código que o job compila.
5. `gh workflow run emulator-insets.yml --repo <owner>/<repo> --ref main`. Espere a run. Procure no log `1440x3088`, densidade `500` e `INSET_CHECK` com `success`.
6. Rode `cargo test` da rota com a fixture e guarde os dois corpos.

Se o job falhar nos primeiros dois minutos, leia o log antes de mudar o teste Android. As falhas iniciais foram shell (`pipefail`, variável entre linhas) e `--tests`, com o emulador já em 1440×3088. Mudar o teste nessa hora não adianta.

---

## 6. Sinais de que o portão ainda não passou

- `gh workflow run` responde 404: o YAML não está no branch padrão.
- O log diz `punch-hole=hole` numa linha de `echo` escrita antes do `cmd overlay` ter sucesso. A linha de `grep` do overlay é que prova o recorte.
- `Physical size` diferente de `1440x3088`, ou densidade sem `500`.
- `INSET_CHECK` ausente, ou presente sem `success`.
- Verify de screenshot `UP-TO-DATE` sem uma execução anterior com `tests=` e `failures=0` no mesmo nome de baseline.
- Baseline divergente que não volta ao SHA-256 original.
- `size_bytes` ou `sha256_checksum` iguais a um literal no handler em vez de `apk_bytes.len()` e do digest desses bytes.

---

## 7. Transferir para outro projeto

O mecanismo serve para outro app Android e outra API. Troque os nomes e mantenha as restrições:

- Uma biblioteca de screenshot, compatível com a versão exata de Kotlin, AGP, compileSdk e Gradle do projeto. No Windows, desconfie de DLL nativa do Robolectric bloqueada pelo antivírus e de caminho de golden acima do limite do Git.
- Preview ou teste que renderize a tela do portão, não um substituto. O nome da variante de fonte grande entra na asserção do scanner.
- Emulador de CI: uma linha, um comando, shell POSIX sem `pipefail`. Geometria provada por `wm size` e `wm density` depois do boot. Insets lidos na main thread, com a tag de semântica no nó que já recebeu o padding.
- `connected*AndroidTest` filtra por propriedade do instrumentation runner, não por `--tests`.
- Metadado de artefato (tamanho, hash) sai dos bytes lidos na hora. O teste bate no router da aplicação e compara com um digest calculado à parte.
- O log de evidência é UTF-8. O commit que o CI compila é o mesmo commit que o `gh workflow run` enxerga.
