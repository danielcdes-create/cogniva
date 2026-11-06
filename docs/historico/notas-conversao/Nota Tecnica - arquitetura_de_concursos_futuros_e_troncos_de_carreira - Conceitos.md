# NOTA TÉCNICA & ESPECIFICAÇÃO PARA LLM HARNESS
**Projeto:** COGNIVA (Cogniva Planner · Learn · Questions · Insights)  
**Módulo:** Gestão Dinâmica de Concursos Futuros, Editais Verticalizados e Troncos de Carreira  
**Stack:** Android (Kotlin, Jetpack Compose, Room DB) + Backend (Rust, MongoDB)  
**Formato:** LLM-Harness Friendly (Contratos Determinísticos, Invariantes de Domínio e Plano de Execução Atômico)

---

## 1. CONTEXTO DO SISTEMA E DIRETRIZES PARA O AGENTE (HARNESS INSTRUCTIONS)

> **INSTRUÇÃO DE SISTEMA PARA O LLM CODING AGENT:**  
> Você está atuando em uma base de código em produção (**COGNIVA v1.2.2+**). O aplicativo possui arquitetura **Offline-First** no Android (sincronizando Room DB local com uma API REST em Rust `/api/v1/` apoiada por MongoDB) e integração com o bot do Telegram.  
> **REGRA DE OURO (INVARIANTE ARQUITETURAL):** O histórico de resolução de questões, o estado de Revisão Espaçada (SRS) e a Matriz Metacognitiva (Confiança $\times$ Resultado) **NUNCA** devem ficar presos a um ID de concurso isolado. Eles devem ser ancorados em uma **Taxonomia Canônica (`canonical_subject_id` e `canonical_topic_id`)**. Um Concurso (`Exam`) ou um Tronco (`CareerTrunk`) funciona apenas como uma **Lente de Projeção Ponderada** sobre o domínio canônico do usuário.

### 1.1. Diagnóstico do Estado Atual (Baseado na UI Existente)
1. **Aba Hoje (`Cogniva Planner · Learn`):** Exibe contagem regressiva do concurso principal, meta diária, Revisão Espaçada, Caderno de Erros, card de "Tronco Comum · Escada de Concursos" e a lista "Meus Concursos em Escada".
2. **Aba Concursos (`Edital Verticalizado · Cobertura Real`):** Exibe abas superiores por concurso, banner de transição de edital (*"Edital Publicado! Comparativo disponível: X confirmados · Y novos · Z arquivados"*) e cards de disciplinas com tópicos e badge `[Oficial]`.
3. **Aba Praticar (`Cogniva Questions · Exams`):** Possui modos de bateria, incluindo *"Cogniva Exams · Simulado Oficial Ponderado"* e *"Tronco Comum (Multi-Concurso)"*.
4. **Aba Progresso (`Cogniva Insights`):** Calcula o *Índice de Prontidão vs. Nota de Corte Histórica*, a *Matriz Metacognitiva* e o *Domínio por Disciplina*.
5. **Aba Ajustes (`Cogniva Settings`):** Permite selecionar os concursos ativos na Escada de Concursos, metas diárias e sincronização manual/automática do Room DB.

### 1.2. Lacunas a Resolver nesta Especificação
* Permitir o **cadastro livre de concursos futuros** pelo usuário (ou importação de templates), com ou sem edital publicado.
* Suportar **datas polimórficas de prova**: data exata (gera contador de dias) vs. janela estimada (ex.: `1º sem/2027`, `Q4/2027`, `A definir`).
* Formalizar a entidade **Tronco de Carreira (`CareerTrunk`)** (ex.: *Fiscal, Policial, Controle, Engenharia, Tribunais, Bancária*) como cidadão de primeira classe, permitindo focar no tronco inteiro e cruzar disciplinas equivalentes entre múltiplos concursos distintos.
* Permitir cadastrar/editar **disciplinas da prova, pesos, número de questões, banca(s) e tópicos**, além de realizar a migração assistida de **Pré-Edital $\rightarrow$ Pós-Edital** sem perda de progresso.

---

## 2. MODELAGEM DE DOMÍNIO E ARQUITETURA DE DADOS

Para que uma questão ou sessão de estudo em *"Finanças Públicas"* ou *"Direito Tributário"* avance simultaneamente a **Receita Federal**, o **CPNU (AFT)** e qualquer **SEFAZ** cadastrada pelo usuário, a modelagem é dividida em 3 camadas:

1. **Camada Canônica (Catálogo Global / Tronco):** `CanonicalSubject` (Disciplina Raiz) $\rightarrow$ `CanonicalTopic` (Tópico/Assunto Raiz).
2. **Camada de Tronco (`CareerTrunk`):** Agrupamento estratégico (Fiscal, Policial, Engenharia, etc.) que define um conjunto padrão de disciplinas canônicas e pesos sugeridos para quem estuda "no escuro" (pré-edital).
3. **Camada de Concurso (`ExamBlueprint`):** Representa um concurso específico (oficial do sistema ou criado pelo usuário). Mapeia suas disciplinas locais (`ExamSubject`) para os IDs canônicos (`canonical_subject_id`), adicionando metadados específicos da prova (peso na nota, quantidade de questões, nome customizado no edital, status do tópico: `PRE_EDITAL`, `OFICIAL_CONFIRMADO`, `OFICIAL_NOVO`, `ARQUIVADO`).

---

## 3. CONTRATOS DE DADOS: BACKEND (RUST + MONGODB)

Crie ou atualize os modelos no módulo `domain::exams` e `domain::trunks` do backend em Rust.

```rust
// filepath: backend/src/domain/models/exam_hierarchy.rs
use bson::{oid::ObjectId, DateTime};
use serde::{Deserialize, Serialize};

/// Representa a área/tronco principal de estudo (Fiscal, Policial, Engenharia, etc.)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CareerTrunk {
    #[serde(rename = "_id", skip_serializing_if = "Option::is_none")]
    pub id: Option<ObjectId>,
    pub slug: String,                  // ex: "fiscal", "policial", "engenharia", "controle"
    pub name: String,                  // ex: "Área Fiscal", "Carreiras Policiais"
    pub icon_key: String,              // chave de ícone para o app Android
    pub color_hex: String,             // cor de destaque na UI
    pub core_subjects: Vec<TrunkSubjectDefault>,
    pub is_system_default: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrunkSubjectDefault {
    pub canonical_subject_id: ObjectId,
    pub default_weight: f32,           // Peso padrão no tronco (ex: 1.0, 2.0)
    pub relevance_tier: RelevanceTier, // CORE (Tronco Comum), COMPLEMENTARY, SPECIFIC
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RelevanceTier {
    Core,          // Cai em >80% dos concursos do tronco
    Complementary, // Cai em 40-80% dos concursos do tronco
    Specific,      // Específico de um órgão/cargo
}

/// Catálogo Canônico de Disciplinas (Unifica o progresso entre concursos distintos)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CanonicalSubject {
    #[serde(rename = "_id", skip_serializing_if = "Option::is_none")]
    pub id: Option<ObjectId>,
    pub code: String,                  // ex: "DIR_TRIB", "FIN_PUB", "ENG_CIVIL_EST"
    pub name: String,                  // ex: "Direito Tributário", "Finanças Públicas"
    pub aliases: Vec<String>,          // ex: ["Legislação Tributária", "Direito Financeiro e Finanças"]
    pub trunk_slugs: Vec<String>,      // ex: ["fiscal", "controle"]
    pub topics: Vec<CanonicalTopic>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CanonicalTopic {
    pub topic_id: String,              // UUID ou código determinístico: "FIN_PUB_01"
    pub title: String,                 // ex: "Orçamento Público: PPA, LDO, LOA e Ciclo Orçamentário na CF/88"
    pub tags: Vec<String>,
}

/// Ciclo de vida do Concurso (suporta desde rumor até pós-edital)
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ExamLifecycleStatus {
    RumorPrevisto,     // Sem autorização formal, estudo por Tronco/Edital anterior
    Autorizado,        // Autorizado / Comissão formada
    BancaDefinida,     // Banca contratada, aguardando edital
    EditalPublicado,   // Edital na praça (habilita diff de tópicos e modo Pós-Edital)
    ProvaRealizada,    // Prova já ocorreu
    Arquivado,         // Pausado/Inativo na escada do usuário
}

/// Polimorfismo para a Data da Prova (Exata vs. Janela Estimada)
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ExamDateTarget {
    ExactDate {
        iso_date: String,              // "2026-12-13" -> UI renderiza "Faltam X dias"
    },
    EstimatedWindow {
        label: String,                 // ex: "1º sem/2027", "2º sem/2027", "Q3/2027"
        estimated_sort_date: String,   // "2027-06-30" -> Usado para ordenação na Escada de Concursos
    },
    Undefined,                         // Sem previsão -> Ordenado por prioridade manual da escada
}

/// Entidade de Concurso (Oficial do Sistema ou Criado pelo Usuário)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExamBlueprint {
    #[serde(rename = "_id", skip_serializing_if = "Option::is_none")]
    pub id: Option<ObjectId>,
    pub owner_user_id: Option<String>, // None = Template Global; Some(id) = Customizado pelo usuário
    pub base_template_id: Option<ObjectId>,
    pub title: String,                 // ex: "Receita Federal · Auditor Fiscal"
    pub short_name: String,            // ex: "Receita Federal", "CPNU (AFT)"
    pub institution: String,           // ex: "RFB", "MTE", "BNDES", "PF"
    pub role: String,                  // ex: "Auditor Fiscal", "Agente", "Engenheiro Civil"
    pub trunk_slug: String,            // Vínculo com o Tronco: "fiscal", "policial", "engenharia"
    pub status: ExamLifecycleStatus,
    pub exam_boards: Vec<String>,      // ex: ["FGV", "Cebraspe"] (Suporta múltiplas bancas prováveis no pré-edital)
    pub target_date: ExamDateTarget,
    pub historical_cutoff_score: f32,  // ex: 74.0 (para o card de Índice de Prontidão)
    pub is_active_in_ladder: bool,     // Define se participa do cálculo ativo da Escada / Tronco Comum
    pub ladder_priority: i32,          // 1 = Foco Principal (Ponto Vermelho), 2 = Secundário (Amarelo), 3+ = Escada (Cinza)
    pub subjects: Vec<ExamSubjectConfig>,
    pub edital_diff_summary: Option<EditalDiffSummary>,
    pub updated_at: DateTime,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExamSubjectConfig {
    pub local_subject_id: String,
    pub canonical_subject_id: ObjectId, // CHAVE CRÍTICA: Conecta ao progresso global do usuário
    pub display_name: String,           // Nome como aparece no edital (ex: "Direito Previdenciário")
    pub category: SubjectCategory,      // CONHECIMENTOS_GERAIS | CONHECIMENTOS_ESPECIFICOS | DISCURSIVA
    pub weight: f32,                    // Peso na prova (ex: 1.0, 2.0, 3.0)
    pub question_count: Option<u32>,    // Qtd de questões na prova (usado pelo Simulado Oficial Ponderado)
    pub minimum_cutoff_pct: Option<f32>,// Mínimo para não zerar/ser eliminado na matéria
    pub topics: Vec<ExamTopicItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SubjectCategory {
    ConhecimentosGerais,
    ConhecimentosEspecificos,
    DiscursivaEstudoDeCaso,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExamTopicItem {
    pub local_topic_id: String,
    pub canonical_topic_id: String,     // Conecta ao tópico canônico para compartilhar % de cobertura
    pub display_title: String,
    pub diff_status: TopicDiffStatus,   // Controla a badge [Oficial], [Novo], [Pré-Edital], [Arquivado]
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TopicDiffStatus {
    PreEditalBase,     // Estudando antes do edital sair
    OficialConfirmado, // Estava no pré-edital e foi mantido no edital publicado
    OficialNovo,       // Surpresa do edital publicado (prioridade de estudo!)
    Arquivado,         // Estava no pré-edital, mas ficou de fora do edital publicado
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EditalDiffSummary {
    pub confirmed_count: u32,
    pub new_count: u32,
    pub archived_count: u32,
    pub published_at_iso: String,
}
```

---

## 4. CONTRATOS DE DADOS: ANDROID (KOTLIN + ROOM DB)

No app Android, o Room DB precisa refletir o vínculo entre os Concursos cadastrados, os Troncos e o Catálogo Canônico, mantendo suporte à fila de sincronização (`sync_status`).

```kotlin
// filepath: android/app/src/main/java/com/cogniva/app/data/local/entities/ExamEntities.kt
package com.cogniva.app.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "career_trunks")
data class CareerTrunkEntity(
    @PrimaryKey val slug: String,           // "fiscal", "policial", "engenharia", etc.
    val name: String,
    val iconKey: String,
    val colorHex: String,
    val isFocusedTrunk: Boolean = false     // Permite ativar o "Modo Foco no Tronco"
)

@Entity(
    tableName = "user_exams",
    indices = [Index(value = ["trunkSlug"]), Index(value = ["isActiveInLadder", "ladderPriority"])]
)
data class UserExamEntity(
    @PrimaryKey val examId: String,         // ObjectId hex ou UUID gerado localmente (offline)
    val title: String,                      // "Receita Federal · Auditor Fiscal"
    val shortName: String,                  // "Receita Federal"
    val institution: String,
    val role: String,
    val trunkSlug: String,                  // FK lógica -> career_trunks.slug
    val lifecycleStatus: String,            // RUMOR_PREVISTO, AUTORIZADO, BANCA_DEFINIDA, EDITAL_PUBLICADO
    val examBoardsCsv: String,              // "FGV,Cebraspe"
    val dateType: String,                   // EXACT_DATE | ESTIMATED_WINDOW | UNDEFINED
    val exactDateIso: String?,              // "2026-12-13"
    val estimatedWindowLabel: String?,      // "1º sem/2027"
    val estimatedSortDateIso: String,       // "2027-06-30"
    val historicalCutoffScore: Float,       // 74.0f
    val isActiveInLadder: Boolean,          // Se entra no cálculo do Tronco Comum
    val ladderPriority: Int,                // 1 = Primário ( Ponto Vermelho), 2 = Secundário (Amarelo), 3+ = Cinza
    val confirmedTopicsCount: Int = 0,
    val newTopicsCount: Int = 0,
    val archivedTopicsCount: Int = 0,
    val syncStatus: String = "SYNCED"       // SYNCED | PENDING_CREATE | PENDING_UPDATE | PENDING_DELETE
)

@Entity(
    tableName = "exam_subjects",
    primaryKeys = ["examId", "localSubjectId"],
    indices = [Index(value = ["canonicalSubjectId"])]
)
data class ExamSubjectEntity(
    val examId: String,
    val localSubjectId: String,
    val canonicalSubjectId: String,         // CHAVE DE CRUZAMENTO MULTI-CONCURSO
    val displayName: String,
    val category: String,                   // CONHECIMENTOS_GERAIS | CONHECIMENTOS_ESPECIFICOS
    val weight: Float,                      // Peso (1.0f, 2.0f, etc.)
    val questionCount: Int?,                // Qtd questões na prova
    val sortOrder: Int
)

@Entity(
    tableName = "exam_topics",
    primaryKeys = ["examId", "localTopicId"],
    indices = [Index(value = ["canonicalTopicId"]), Index(value = ["examId", "localSubjectId"])]
)
data class ExamTopicEntity(
    val examId: String,
    val localSubjectId: String,
    val localTopicId: String,
    val canonicalTopicId: String,           // CHAVE DE PROGRESSO COMPARTILHADO
    val displayTitle: String,
    val diffStatus: String                  // PRE_EDITAL_BASE | OFICIAL_CONFIRMADO | OFICIAL_NOVO | ARQUIVADO
)
```

---

## 5. REGRAS DE NEGÓCIO E ALGORITMOS MATEMÁTICOS

### 5.1. Algoritmo de Intersecção do "Tronco Comum (Multi-Concurso)"
Atualmente, na tela **Hoje** e na tela **Praticar**, o app destaca:
> *"Estudar 15 questões de Finanças Públicas avança CPNU e Receita Federal ao mesmo tempo"*

Com o novo suporte a múltiplos concursos cadastrados e Foco no Tronco, o motor de recomendação deve calcular o **Score de Alavancagem Multi-Concurso ($S_{alav}$)** para cada disciplina canônica $d$:

$$S_{alav}(d) = \left( \sum_{e \in E_{ativos}} \mathbb{I}(d \in e) \cdot \omega_{e,d} \cdot \tau(e) \right) \cdot \left( 1 - \text{Domínio}(d) \right)$$

Onde:
* $E_{ativos}$ é o conjunto de concursos marcados como ativos na Escada do usuário (ou pertencentes ao `CareerTrunk` em foco).
* $\mathbb{I}(d \in e) \in \{0, 1\}$ indica se a disciplina canônica $d$ está presente no concurso $e$.
* $\omega_{e,d}$ é o peso normalizado da disciplina $d$ no concurso $e$.
* $\tau(e)$ é o fator de urgência temporal do concurso $e$, definido por:
  $$\tau(e) = \begin{cases} 2.0, & \text{se data exata } \le 60 \text{ dias} \\ 1.5, & \text{se } \text{status} = \text{EDITAL\_PUBLICADO} \\ 1.0, & \text{se pré-edital / janela futura} \end{cases}$$
* $\text{Domínio}(d) \in [0, 1]$ é a taxa de domínio atual do usuário na disciplina canônica $d$ (calculada via acertos com alta confiança + retenção SRS).

**Regra de Exibição do Card Azul (Tronco Comum):**
* Selecionar a disciplina canônica $d^*$ que maximiza $S_{alav}(d)$ e que esteja presente em pelo menos $|\{e \in E_{ativos} \mid d \in e\}| \ge 2$ concursos ativos (ou que seja `CORE` no Tronco selecionado caso o usuário tenha ativado o "Modo Foco no Tronco").
* Gerar dinamicamente a string: `"Estudar {N} questões de {d.name} avança {Lista de Concursos impactados} ao mesmo tempo"`.

### 5.2. Cálculo do Índice de Prontidão por Concurso vs. por Tronco
Na tela **Progresso (`Cogniva Insights`)**, o *Índice de Prontidão* ($IP_e$) de um concurso $e$ ou tronco deve ser calculado de forma ponderada pelas disciplinas ativas (excluindo tópicos com `diffStatus == ARQUIVADO`):

$$IP_e = 100 \times \frac{\sum_{d \in e} \omega_{e,d} \cdot \left( 0.7 \cdot M_{real}(d) + 0.3 \cdot C_{edital}(d, e) \right)}{\sum_{d \in e} \omega_{e,d}}$$

Onde:
* $M_{real}(d) \in [0, 1]$ é o índice de domínio na Matriz Metacognitiva (priorizando *Domínio Real* e penalizando *Ponto Cego Crítico*).
* $C_{edital}(d, e) \in [0, 1]$ é a fração de tópicos de $d$ no edital $e$ que já possuem questões praticadas/dominadas no SRS.

### 5.3. Máquina de Estados: Transição Pré-Edital $\rightarrow$ Edital Publicado
Quando um concurso cadastrado como `RUMOR_PREVISTO`, `AUTORIZADO` ou `BANCA_DEFINIDA` tem seu edital divulgado, o usuário (ou uma atualização via servidor) aciona a ação **"Publicar/Atualizar Edital"**:
1. O sistema compara a lista anterior de `canonical_topic_id` do concurso com a nova lista pós-edital.
2. **Intersecção ($T_{antigo} \cap T_{novo}$):** Recebe `diffStatus = OFICIAL_CONFIRMADO`. Todo o histórico de questões e % da barra de progresso permanece intacto!
3. **Diferença Nova ($T_{novo} \setminus T_{antigo}$):** Recebe `diffStatus = OFICIAL_NOVO`. Se o usuário já estudou esse `canonical_topic_id` em *outro* concurso do mesmo tronco, a porcentagem de progresso já nasce preenchida automaticamente!
4. **Diferença Removida ($T_{antigo} \setminus T_{novo}$):** Recebe `diffStatus = ARQUIVADO`. Ocultado da fila de estudo daquele concurso, mas o histórico canônico é preservado caso o tópico caia em outro concurso da escada.
5. Atualiza os contadores `confirmedTopicsCount`, `newTopicsCount` e `archivedTopicsCount` para exibir o banner azul na tela **Concursos**.

---

## 6. ESPECIFICAÇÃO DE TELAS E FLUXOS DE UI (JETPACK COMPOSE)

### 6.1. Ponto de Entrada 1: Aba `Concursos` (Header e Abas Superiores)
* Ao final da linha de abas de concursos (`[● CPNU (AFT)] [● Receita Federal] [● BNDES]`), adicionar um botão compacto `[+ Novo / Tronco]`.
* Adicionar um seletor de filtro/visão no subtítulo do topo: alternar entre **"Visão por Concurso"** e **"Visão por Tronco (ex: Tronco Fiscal)"**.
  * Na **Visão por Tronco**, o Edital Verticalizado exibe todas as disciplinas `CORE` e `COMPLEMENTARY` do tronco selecionado, mostrando badges em cada disciplina indicando em quais dos seus concursos ativos aquela matéria cai (ex.: `[CPNU] [RFB] [SEFAZ-MG]`).

### 6.2. Ponto de Entrada 2: Fluxo de Cadastro/Edição (`ExamBuilderBottomSheet` / Wizard em 3 Passos)
Criar um fluxo rápido e fluido em Jetpack Compose seguindo a paleta escura (`#0A111E` / `#121E31` / `#1E88E5`) do COGNIVA:

1. **Passo 1: Identificação & Tronco de Carreira**
   * **Seletor de Tronco (Chips com ícone):** `Fiscal`, `Policial`, `Controle`, `Engenharia`, `Tribunais`, `Bancária`, `Administrativa`, `Personalizado`.
   * **Modo de Criação:**
     * *Usar Template Pré-Edital do Tronco* (já pré-carrega as disciplinas clássicas do tronco selecionado).
     * *Clonar de outro concurso cadastrado* (ideal para criar "SEFAZ-SP" a partir de "Receita Federal").
     * *Criar do zero*.
   * **Campos:** Nome do Órgão/Concurso (ex: `Polícia Federal` ou `SEFAZ-MG`), Cargo (ex: `Auditor Fiscal`), Bancas Prováveis (chips multi-seleção: `FGV`, `Cebraspe`, `Cesgranrio`, `FCC`, `Vunesp`, `A definir`).
2. **Passo 2: Status do Edital & Data da Prova**
   * **Status:** `Pré-Edital (Previsto/Rumor)`, `Autorizado`, `Banca Definida`, `Edital Publicado`.
   * **Data da Prova (Segmented Button):**
     * **Data Exata:** DatePicker $\rightarrow$ alimenta contador regressivo (`42 dias`).
     * **Janela Estimada:** Seletores rápidos (`1º sem/2027`, `2º sem/2027`, `1º sem/2028`, `Sem previsão`).
   * **Posição na Escada de Concursos:**
     * `Foco Imediato / Pós-Edital` (Bolinha Vermelha)
     * `Médio Prazo / Escada Principal` (Bolinha Amarela)
     * `Longo Prazo / Radar` (Bolinha Cinza)
   * **Nota de Corte Histórica Estimada:** Slider (ex: `70%` a `85%`, padrão `75%`).
3. **Passo 3: Grade de Disciplinas, Pesos e Questões**
   * Lista de disciplinas pré-selecionadas pelo Tronco + botão `+ Adicionar Disciplina do Catálogo Canônico` (ou criar nova disciplina vinculada ao tronco).
   * Para cada disciplina ativa no concurso, exibir linha compacta com:
     * Categoria: `Gerais (P1)` | `Específicos (P2/P3)`
     * Peso: Stepper `1x`, `1.5x`, `2x`, `3x`
     * Qtd. de Questões (opcional, para o *Simulado Oficial Ponderado*): ex. `10`, `15`, `20`.
   * Expansão opcional de Tópicos para marcar/desmarcar itens específicos do Edital Verticalizado.

### 6.3. Ponto de Entrada 3: Aba `Ajustes` (Card "Metas Diárias & Escada de Concursos")
* Substituir os chips estáticos (`CPNU - AFT`, `Receita Federal`, `BNDES`) por uma lista dinâmica lida do `UserExamDao.observeAllExams()`.
* Adicionar seletor **"Foco de Tronco Principal"**: permite marcar um ou mais troncos (ex.: `Fiscal` + `Controle`), garantindo que o modo **"Tronco Comum (Multi-Concurso)"** na aba *Praticar* filtre automaticamente questões das bancas e disciplinas daquele tronco.

---

## 7. CONTRATOS DA API REST (RUST `/api/v1/`)

| Método | Endpoint | Descrição | Suporte Offline / Sync |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/catalog/trunks` | Retorna todos os Troncos (`CareerTrunk`) e o catálogo de `CanonicalSubject` + `CanonicalTopic`. | Cacheado no Room DB (atualizado via ETag/versão). |
| `GET` | `/api/v1/exams` | Lista os concursos do usuário + templates oficiais ativos na escada. | Sincronizado no boot e botão "Sincronizar". |
| `POST` | `/api/v1/exams` | Cria um novo concurso futuro (pré-edital ou pós-edital) customizado pelo usuário. | Enfileirado localmente via `WorkManager` se offline. |
| `PUT` | `/api/v1/exams/{exam_id}` | Atualiza data da prova, bancas, status, pesos ou grade de disciplinas. | Enfileirado localmente se offline. |
| `POST` | `/api/v1/exams/{exam_id}/publish-edital` | Recebe o payload do novo edital publicado, executa o algoritmo de diff (`confirmados`, `novos`, `arquivados`) e atualiza o status para `EDITAL_PUBLICADO`. | Requer conexão ou processa diff local no Room DB e sincroniza depois. |
| `POST` | `/api/v1/questions/batch/trunk-common` | Gera uma bateria de questões otimizada pela fórmula $S_{alav}(d)$ cruzando os `exam_ids` ativos ou `trunk_slug`. | Fallback local no Room DB usando as questões em cache. |

---

## 8. PLANO DE EXECUÇÃO ATÔMICO PARA O LLM HARNESS

> **INSTRUÇÃO DE EXECUÇÃO:** Execute as fases abaixo sequencialmente. Ao final de cada fase, rode os comandos de verificação (`cargo check` / `cargo test` no backend e `./gradlew compileDebugKotlin` no Android) antes de prosseguir para a próxima fase.

### Fase 1: Catálogo Canônico e Modelos no Backend (Rust + MongoDB)
- [ ] **Task 1.1:** Criar o arquivo `src/domain/models/exam_hierarchy.rs` com as structs `CareerTrunk`, `CanonicalSubject`, `ExamBlueprint`, `ExamDateTarget` e enums de ciclo de vida.
- [ ] **Task 1.2:** Criar um *Seeder/Migration* inicial em Rust que popule no MongoDB os Troncos principais (`fiscal`, `policial`, `controle`, `engenharia`, `tribunais`, `bancaria`) e mapeie os concursos atuais (`CPNU - AFT`, `Receita Federal`, `BNDES`) para `canonical_subject_id` e `canonical_topic_id` consistentes.
- [ ] **Task 1.3:** Garantir que a coleção de respostas de questões (`question_attempts` / `srs_cards`) possua índice e referência direta para `canonical_subject_id` e `canonical_topic_id`.

### Fase 2: Endpoints REST e Motor de Tronco Comum (Rust)
- [ ] **Task 2.1:** Implementar os handlers em Rust (`GET/POST/PUT /api/v1/exams` e `GET /api/v1/catalog/trunks`).
- [ ] **Task 2.2:** Implementar o serviço de transição `publish_edital_diff` que recebe a nova matriz de tópicos e classifica automaticamente cada item em `OFICIAL_CONFIRMADO`, `OFICIAL_NOVO` ou `ARQUIVADO`.
- [ ] **Task 2.3:** Atualizar o endpoint de recomendação diária (card azul da tela *Hoje*) para usar o cálculo de alavancagem $S_{alav}(d)$ sobre os concursos ativos do usuário.

### Fase 3: Persistência Offline-First no Android (Kotlin + Room DB)
- [ ] **Task 3.1:** Adicionar as entidades `CareerTrunkEntity`, `UserExamEntity`, `ExamSubjectEntity` e `ExamTopicEntity` no Room Database e incrementar a versão do banco com `Migration` segura (preservando os dados atuais em cache local).
- [ ] **Task 3.2:** Criar o `ExamDao` com queries reativas (`Flow`) que cruzem o progresso das questões com `canonicalSubjectId` e `canonicalTopicId`, de modo que resolver uma questão atualize instantaneamente a porcentagem de cobertura em **todos** os concursos que contenham aquela disciplina/tópico.
- [ ] **Task 3.3:** Atualizar o `SyncRepository` para enviar criações e edições de concursos (`syncStatus != "SYNCED"`) quando o usuário clicar em **"Sincronizar"** na aba *Ajustes* ou quando houver conectividade.

### Fase 4: Interface do Usuário (Jetpack Compose)
- [ ] **Task 4.1:** Criar o `ExamFormBottomSheet` (Wizard de cadastro/edição de concurso com seleção de Tronco, Status Pré/Pós-Edital, Data Exata vs. Janela `1º sem/2027`, Bancas e Disciplinas com Pesos).
- [ ] **Task 4.2:** Atualizar a tela **Hoje (`PlannerScreen`)**:
  - Renderizar a lista *"Meus Concursos em Escada"* dinamicamente a partir do Room DB, com clique longo ou ícone para editar o concurso/data e botão `+ Adicionar Concurso na Escada`.
  - Renderizar o texto de prazo de forma polimórfica (`"42 dias"` em vermelho para `EXACT_DATE` próxima vs. `"1º sem/2027"` para `ESTIMATED_WINDOW`).
- [ ] **Task 4.3:** Atualizar a tela **Concursos (`VerticalizedEditalScreen`)**:
  - Adicionar a opção de alternar entre os concursos cadastrados e a visão consolidada do **Tronco de Carreira**.
  - Permitir adicionar/remover disciplinas e tópicos diretamente no edital verticalizado e acionar a conversão **"Edital Publicado! Comparar com Pré-Edital"**.
- [ ] **Task 4.4:** Atualizar a tela **Ajustes (`SettingsScreen`)**:
  - Tornar os chips da seção *"Metas Diárias & Escada de Concursos"* dinâmicos e adicionar o seletor de **Foco de Tronco**.

---

## 9. GUARDRAILS E CRITÉRIOS DE ACEITE (DEFINITION OF DONE)

1. **Invariante de Progresso Compartilhado:** Se o usuário cadastrar um novo concurso "SEFAZ-SP" no *Tronco Fiscal* e incluir a disciplina canônica *"Finanças Públicas"* ou *"Direito Tributário"*, a porcentagem de domínio e os tópicos já estudados para a *Receita Federal* ou *CPNU* devem aparecer imediatamente contabilizados no novo concurso.
2. **Isolamento de Pesos no Simulado:** No modo *"Cogniva Exams · Simulado Oficial Ponderado"*, a distribuição de questões e o cálculo da nota líquida devem respeitar estritamente os pesos ($\omega_{e,d}$) e quantidades de questões configurados no `ExamBlueprint` selecionado, ignorando tópicos com status `ARQUIVADO`.
3. **Resiliência Offline:** O usuário deve conseguir cadastrar um concurso futuro, definir suas disciplinas e estudar no modo *Tronco Comum* mesmo em modo avião, sincronizando com o backend em Rust posteriormente sem conflitos de chave primária (usar UUIDv7 ou ObjectId gerado no cliente).