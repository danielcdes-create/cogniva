# Arquitetura em 3 Camadas: Diagnóstico por Evidências, Decisão sob Incerteza e Protocolo de 60s (`2.4.2`)

Este documento especifica a arquitetura pedagógica, estatística e comportamental do **Cogniva `2.4.2`** (`build 32`, `contract_version: 5`), implementada com simetria matemática bit a bit entre Python, o backend Rust ([`backend-rust/src/services/`](../backend-rust/src/services/)) e o cliente Android *offline-first* ([`PerformanceEngines.kt`](../android-app/app/src/main/java/br/com/concursoprep/app/domain/exam/PerformanceEngines.kt)).

A solução abandona três ilusões clássicas de plataformas para concursos:
1. Que são necessárias milhares de questões sintéticas para diagnosticar um conceito;
2. Que tempo prolongado de prova implica necessariamente deterioração uniforme de desempenho;
3. Que deixar em branco em bancas com penalidade é automaticamente ótimo apenas por uma confiança subjetiva arbitrária.

---

## 1. Camada 1 — Escassez: Diagnóstico por Evidências, Não por Geração

### 1.1. Ficha Editorial Rastreável (`editorial_card`)
Cada questão humana objetiva possui (por curadoria no `/admin/` ou sintetização determinística ancorada nas fontes oficiais do catálogo em [`models/question.rs`](../backend-rust/src/models/question.rs) e [`Models.kt`](../android-app/app/src/main/java/br/com/concursoprep/app/domain/model/Models.kt)) uma ficha editorial com:
- `legal_provisions[]`: dispositivos legais, normativos ou súmulas envolvidos;
- `tested_rule`: regra cobrada de forma objetiva;
- `exception_clause`: ressalva ou exceção examinada (quando presente);
- `decisive_stem_excerpt`: fragmento decisivo do enunciado humano;
- `official_source_excerpt`: excerto literal da fonte normativa;
- `plausible_conceptual_errors[]`: erros conceituais plausíveis associados aos distratores.

Após confirmar a resposta, o estudante recebe imediatamente o gabarito + fundamento legal destacado e pode, opcionalmente, apontar o trecho do enunciado/fonte oficial que determinou sua resposta (`POST /api/v1/quiz/attempt-evidence`), **sem jamais formular outra questão jurídica gerada por IA**.

### 1.2. Cruzamento dos 3 Sinais e Restrição `< 15` Questões (`diagnostic_engine.rs` / `DiagnosticEngine`)
O diagnóstico cruza **resposta × confiança declarada × evidência selecionada** sob `MIN_QUESTIONS_FOR_MASTERY = 15`:
- `OVERCONFIDENCE_ERROR`: erro com `confidence >= 3` (ou `confidence >= 2` com evidência incorreta).
- `CONCEPTUAL_ERROR`: erro com falha na evidência ou sem acerto prévio consolidado.
- `RETRIEVAL_FAILURE`: erro após histórico prévio comprovado (esquecimento temporal).
- `ILLUSORY_COMPETENCE`: **acerto no gabarito acompanhado de seleção de evidência/premissa incorreta** — tratado como indício ativo de fragilidade conceitual.
- `EVIDENCE_IN_PROGRESS`: desempenho consistente (`acc >= 80%`, `confidence >= 2`, retenção espaçada), porém com **`distinct_questions < 15`** questões humanas distintas — o sistema jamais afirma domínio pleno sob amostra pequena.
- `CONSOLIDATED_NO_TRANSFER`: `distinct_questions >= 15`, retenção espaçada e acurácia `>= 80%`, mas ainda sem verificação independente em outra banca.
- `VERIFIED_COMPETENCE`: exige simultaneamente **`distinct_questions >= 15`**, retenção espaçada, acurácia `>= 80%`, `confidence >= 2` e **`has_independent_transfer == true`**.

### 1.3. Conjunto Separado de Transferência Cross-Banca (`TRANSFER_PROBE`)
- Para testar transferência real, o modo `TRANSFER_PROBE` seleciona questões humanas inéditas de **outras bancas** vinculadas ao mesmo conceito ou tópico canônico (`evaluate_transfer_status`).
- Quando não há questão humana válida de outra banca no acervo, o sistema registra explicitamente **`TRANSFER_UNMEASURED`** (*"Transferência não mensurada"*), transformando a escassez em restrição explícita de confiabilidade em vez de incentivo à alucinação.

---

## 2. Camada 2 — Fadiga e Pontuação Líquida: Simulador de Decisão sob Incerteza

### 2.1. Valor Esperado, Complementaridade Binária e Probabilidade de Meta (`strategy_engine.rs` / `StrategyEngine`)
- Em provas estilo Cebraspe ($+1$ acerto, $-1$ erro, $0$ omissão; $\lambda = 1$), o valor esperado de responder é:

$$VE = 2p - 1$$

Logo, $p = 0{,}55 \implies VE = +0{,}10$ ponto esperado.
- **Complementaridade Binária (`normalize_binary_choice_probability`):** Em questão estritamente binária (`Certo`/`Errado`), se a probabilidade estimada de uma alternativa ser verdadeira é $48\%$, a complementar possui $52\%$.
- **Decisão Ótima sob Incerteza (`simulate_decision_under_uncertainty`):** A omissão ganha utilidade estratégica quando consideramos a nota mínima eliminatória $S_{\min}$, a meta de corte $S_{\text{meta}}$, a pontuação líquida atual $S_{\text{atual}}$, os itens restantes $N_{\text{rest}}$ e a incerteza Beta-Binomial $\sigma_p$. O motor compara analiticamente (via CDF normal racional Abramowitz & Stegun 7.1.26, idêntica em Python/Rust/Kotlin) $P(S_{\text{final}} \ge \max(S_{\text{meta}}, S_{\min}) \mid \text{Responder})$ contra $P(S_{\text{final}} \ge \max(S_{\text{meta}}, S_{\min}) \mid \text{Omitir})$, determinando `MAXIMIZE_EV`, `PROTECT_MARGIN` ou `CALCULATED_RISK`.

### 2.2. Modelo Individual de Desempenho ao Longo de 300 Minutos (`fatigue_engine.rs` / `FatigueEngine`)
O desempenho instantâneo no minuto $t \in [0, 300]$ é modelado como:

$$p(t) = \sigma\!\left(\operatorname{logit}(p_0) - \lambda_i\frac{t}{300}\right)$$

onde $\operatorname{logit}(p_0) = \operatorname{logit}(p_{\text{base}}) - 0{,}80(d_{\text{item}} - 0{,}50)$ ajusta a dificuldade individual do item e $\lambda_i \in \mathbb{R}$ captura o efeito longitudinal individual do tempo de prova:
- $\lambda_i > 0$ estatisticamente significativo $\implies$ `FATIGUE_DETECTED` (queda controlada por dificuldade);
- $\lambda_i < 0$ estatisticamente significativo $\implies$ `WARMUP_GAIN_DETECTED` (efeito de aquecimento ao longo da prova);
- efeito nulo/não significativo $\implies$ `STABLE_ENDURANCE`;
- menos de **3 simulados prolongados contínuos sem interrupção** ou menos de **120 itens cronometrados** $\implies$ `INSUFFICIENT_EVIDENCE` ($\lambda_i = \text{null}$).

Sessões diárias de **8–12 minutos** (`STRATEGY_BATTERY`) apresentam cenários de prova (`35m`, `165m`, `275m`, `285m`), sinalizando explicitamente na interface quando os cenários operam em simulação matemática antes da calibração fisiológica longitudinal.

---

## 3. Camada 3 — "Terça · 22h": Protocolo de Entrada de 60 Segundos e Consulta em Camada

### 3.1. Compromisso Reversível sem Culpa (`TodayScreen.kt` + `QuizMode.ENTRY_60S`)
Nenhuma página inicial com metas atrasadas, gráficos punitivos ou cobrança de sequência diária:

| Alvo de Interface (Não-Imposto) | Experiência no Produto |
| :--- | :--- |
| **`0–8 s`** | Cabeçalho calmo `DIA · HORA` (`UTC-3`, ex.: `TERÇA · 22H`), *"Só uma questão. Você pode encerrar logo depois, independentemente do resultado."*, botão primário **`Resolver 1 questão`** e saída visível **`Sem estudar hoje`**. |
| **`8–40 s`** | Exibe questão humana curta (`entry_60s_question_score`), preferencialmente de assunto familiar com retenção pendente, com três decisões diretas: **Certo**, **Errado** (ou alternativas) e **Deixar em Branco**. |
| **`40–50 s`** | Gabarito imediato, Ficha Editorial com fundamento legal destacado, indicação objetiva do erro (quando houver) e seleção opcional do fragmento decisivo. |
| **`50–60 s`** | Oferece **`Encerrar`** ou **`Resolver outra`** sem qualquer punição ou pressão. O sucesso é medido por **taxa de início voluntário**, **conclusão da 1ª questão** e **retorno espontâneo**. |

### 3.2. Consulta Contextual em Camada (`ContextualLookupSheet.kt` + `/api/v1/questions/:id/lookup`)
Ao selecionar qualquer palavra ou trecho do enunciado (ou tocar em **`Consultar / Google`**), uma camada `ModalBottomSheet` sobe sobre a questão oferecendo:
1. Correspondências imediatas nas **Fontes Normativas e Conceitos** do catálogo Cogniva (inclusive 100% offline);
2. **Síntese Enciclopédica** aberta via endpoint `POST /api/v1/questions/:id/lookup` (custo zero de cota IA);
3. **Explicação Contextual Cogniva AI** (`term-lookup` pré-resposta com guarda anti-spoiler / `term-lookup-post` pós-resposta, jamais salva em cache compartilhado);
4. **Pesquisa Google / Planalto / STF-STJ em Camada** via Partial Custom Tab sobreposta (~74% da tela), mantendo a questão ativa ao fundo.
