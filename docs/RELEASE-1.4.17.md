# Release 1.4.17 / build 24

**Publicada e conferida em 12/11/2026 (Brasília).** Código `36811181f3cb106ee9ac24539e35404b96a63238`, tag `v1.4.17`, backend **1.4.17**, Android **1.4.17/build 24**, contrato de sincronização 2. A [run 43153558224](https://github.com/danielcdes-create/cogniva/actions/runs/43153558224) terminou com todos os jobs aprovados. Substitui a produção 1.4.16/build 23.

- [APK publicado / OTA](https://198.199.65.103/api/v1/apk/download): **46.445.617 bytes**.
- SHA-256: `8cb59460a876830e2c72fe828765e60654de071d8cded04f24873cb671b1b09d`.
- Runtime: **32.009.643 bytes**, abaixo do gate de 50 MB.
- Imagem: `ghcr.io/danielcdes-create/api-rust-core@sha256:c8edb76acee40c4559fdd173a6d0c4e96072980670a2ee36d2e4cdc2f397b4c6`.

## Conteúdo

- A explicação sobre a estrutura dos estudos passa a ficar somente em **Ajustes → Ajuda**. O card inline foi retirado de Concursos, Gerenciar concursos e Inserir questões.
- O editor de concursos reserva uma barra inferior fixa (`Scaffold` `bottomBar`) para Continuar, Voltar e Salvar na escada, separada do formulário rolável e protegida pelos recuos de navegação e teclado (`WindowInsets.safeDrawing` + `imePadding()`). Trocar de etapa fecha o teclado e reinicia a rolagem.
- O modal do editor usa janela não flutuante edge-to-edge (`EdgeToEdgeFloatingDialogWindowTheme` com `MATCH_PARENT × MATCH_PARENT`), garantindo medição e layout em tela cheia desde o primeiro passe no Android 12–15 (API 31–35), preservando escala de fonte (`LocalDensity`) e semântica de diálogo.
- As etapas se acomodam em linhas (`FlowRow`) e a exclusão usa ícone acessível no cabeçalho, evitando comprimir o título com fonte ampliada.
- A mudança não altera a grade, a persistência ou a sincronização dos concursos.

## Validação e publicação

Na tentativa anterior (`89e7b3621eb820c8a6af08744e20d989398f36cc`, [run 43142150168](https://github.com/danielcdes-create/cogniva/actions/runs/43142150168)), o job de emulador API 35 falhou em `ExamManagementTest#editFromManagementKeepsTheFormAboveActionsAtLargeFont` porque `androidx.compose.ui.window.Dialog` selecionava tema flutuante (`WRAP_CONTENT`) em API ≥ 31 e redimensionava a janela apenas no segundo passe após liberar o `ComposeIdlingResource`; `build` e `promote` foram bloqueados. Com a correção em `ExamBuilderSheet.kt` (`36811181f3cb106ee9ac24539e35404b96a63238`), todas as suítes locais e do CI passaram:

- **Local:** 15 testes `web-admin` e conferência do guia gerado; 47 testes de contratos de conhecimento/questões; 56 testes unitários Rust (`cargo test --locked`); `testDebugUnitTest`, `lintDebug`, `compileDebugAndroidTestKotlin` e `verifyPaparazziDebug` (fonte 1× e 2× sem divergência de snapshots).
- **CI ([run 43153558224](https://github.com/danielcdes-create/cogniva/actions/runs/43153558224)):** `verify / web-admin`, `verify / knowledge-contracts`, `verify / backend` (incluindo Mongo sintético), `verify / android`, `emulator / API 35 1440x3088 500dpi punch-hole` (13/13 testes instrumentados aprovados), `build` assinado e `promote` com ensaio de rollback (`ROLLBACK_HEALTHY`).

Health e readiness anunciam a revisão `36811181f3cb106ee9ac24539e35404b96a63238` / `1.4.17`. `GET /api/app/latest-apk` anuncia `version_name="1.4.17"`, `version_code=24`, `size_bytes=46445617` e SHA-256 `8cb59460a876830e2c72fe828765e60654de071d8cded04f24873cb671b1b09d`. O APK baixado foi inspecionado com `aapt dump badging` (`versionCode='24'`, `versionName='1.4.17'`, `minSdk='28'`, `targetSdk='35'`) e `apksigner verify` (APK Signature Scheme v3 válido, `CN=Cogniva Release`). As rotas `/api/v1/apk/download`, `/apk` e `/cogniva.apk` entregam `Content-Length: 46445617`, `Cache-Control: no-store, no-transform` e sem compressão dinâmica (`Content-Encoding` ausente). Os cinco arquivos públicos do painel `/admin/` conferem com o commit publicado e a rota protegida anônima responde 401.

## Preservação

As **770 questões (`todasQuestoes`)** e os **5 concursos (`user_exams`)** são **idênticos byte a byte** entre os backups pré e pós-promoção (`20261112T023158Z` → `20261112T043606Z`), sem reclassificação de questões. O histórico protegido permanece inalterado com **57 documentos / 94 eventos**, fingerprint `622fc97a22c120719536be41571be0cf49021dc72ac7e1b95e2b79886ede4d6a`. Os quatro validadores estritos, oito índices, catálogo v1 e `legacy-cohort-guard/1` foram conferidos. `/opt/apps/docker-compose.yml` (`ae0f543998b6bb3972e32a7942a73e8c269a4ac16af773c5c01e3fcfe531dcc2`) e os containers `spamshield-server`, `financial-twin-server` e `asbuildo-server` mantiveram exatamente os mesmos IDs. Não foram inseridas questões de teste, ativados catálogos ou realizadas chamadas pagas de IA no aceite.

Evidências: [CI](remediation-validation/1.4.17/ci.json), [release](remediation-validation/1.4.17/production-release.json), [preservação do banco](remediation-validation/1.4.17/storage-preservation.json) e [arquivos web](remediation-validation/1.4.17/web-assets.json).
