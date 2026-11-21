# Módulo Missões · Arquitetura, Contratos Declarativos e Epistemologia

O módulo **Missões** integra aprendizagem experiencial audiovisual interativa ao ecossistema **Cogniva** (Web em `/missoes/`, Portal Administrativo em `/admin/` e Aplicativo Nativo Android), preservando rigorosamente a separação epistemológica entre percurso narrativo, interação formativa e avaliação formal em questões humanas reais.

---

## 1. Hierarquia Conceitual Declarativa

Toda a estrutura pedagógica é declarativa e validada por schema JSON (`shared/schemas/missions.schema.json` + `shared/missions-catalog.json`):

1. **Carreira / Concurso** (`CareerSpec`) — Ex.: `Banco Central — Economia e Finanças` (`contest_template_id`: `c02bb92e-f015-4fae-8d52-92814babe02c`, tronco `bancaria`).
2. **Disciplina** (`DisciplineSpec`) — Ex.: `Finanças` (`canonical_subject_id`: `MFIN`, componente `bcb2027-ef-FIN`).
3. **Temporada** (`SeasonSpec`) — Ex.: `Temporada 1: Mercado Financeiro` (tema `Renda Fixa`).
4. **Missão** (`MissionSpec`) — Ex.: `Missão 01: O Choque dos Juros` (`bcb-ef-t1-m01-choque-dos-juros`).
5. **Cena** (`SceneSpec`) — Unidade audiovisual sequencial com contexto narrativo, transcrição integral PT-BR, legendas sincronizadas WebVTT, visualização matemática nativa opcional e interação didática opcional.
6. **Interação** (`DidacticInteractionSpec`) — Ponto de decisão formativa (`is_editorial_question: false`, `contributes_to_formal_mastery: false`).
7. **Evidência de Aprendizagem** — Tripartida em:
   - **Progresso narrativo**: cenas percorridas, posição de reprodução e conclusão do episódio (`mission_progress`).
   - **Desempenho nas interações**: acerto na 1ª escolha, tentativas totais e conceitos sinalizados para revisitação (`mission_progress`).
   - **Domínio demonstrado (independente)**: calculado exclusivamente sobre questões humanas reais (`CONFIRMED` + `CURRENT`) em `todasQuestoes` / `userActivityLog` (`MIN_HUMAN_QUESTIONS_FOR_MASTERY = 15`).

---

## 2. Invariantes Epistemológicas Inegociáveis

- **Proibição de poluição do acervo formal**: Decisões narrativas da Cena 3 e da Cena 5 **jamais** são inseridas em `todasQuestoes`, `userActivityLog` ou `attempt_receipts`.
- **Conclusão de vídeo não concede domínio**: Concluir 100% das cenas e acertar todas as decisões narrativas mantém `demonstrated_mastery` como `UNMEASURED` (ou `NO_QUESTIONS_AVAILABLE` quando o ambiente não possui questões humanas classificadas nos subtópicos).
- **Zero geração algorítmica de questões ou gabaritos**: O módulo não invoca LLMs para criar itens formais, editar gabaritos ou inventar métricas de domínio.

---

## 3. Engenharia de Vídeo e Resiliência

- **Preservação binária**: Os arquivos `.mp4` originais (`H.264 avc1` + `AAC-LC 48kHz stereo`, `1280x720` 16:9) são servidos sem recompressão a partir de `MISSIONS_MEDIA_DIR` (resolução automática para `missoes/videos` em desenvolvimento e `/app/missoes/videos` em container).
- **Streaming `HTTP Range`**: `GET /api/v1/missions/media/:asset_id` opera fora da camada gzip em blocos de 64 KB com suporte a `206 Partial Content`, `Accept-Ranges: bytes`, `ETag` (`304 Not Modified`), `416 Range Not Satisfiable` e `X-Accel-Buffering: no`.
- **Legendas e Modo Transcrição**: `GET /api/v1/missions/captions/:asset_id.vtt` gera legendas `WEBVTT` em PT-BR; qualquer indisponibilidade de vídeo ou rede ativa automaticamente o **Modo Transcrição Ativo**, permitindo concluir toda a missão sem bloqueio.
- **Visualizações matemáticas nativas**: Os gráficos da Cena 4 ($PU = VF / (1 + i)^n$) e da Cena 5 ($\Delta P / P \approx -[D / (1 + i)] \times \Delta i$) são renderizados nativamente pelo aplicativo (SVG interativo na Web; Compose `Canvas` no Android), garantindo legibilidade e exatidão numérica independentemente de elementos visuais estilizados nos vídeos.

---

## 4. Como Adicionar Novas Carreiras, Temporadas ou Missões

Nenhum código de player (Rust, Web ou Android) precisa ser alterado para publicar novas missões:

1. Adicione os arquivos `.mp4` em `MISSIONS_MEDIA_DIR` (`missoes/videos/`).
2. Registre os metadados e cues de legenda em `media_assets` e a definição da missão em `shared/missions-catalog.json` (ou publique dinamicamente na coleção `mission_definitions`).
3. Valide o contrato e os hashes SHA-256 locais:
   ```bash
   python scripts/missions_contracts.py --check --verify-media
   ```
