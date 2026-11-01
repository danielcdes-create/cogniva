# NOTA TÉCNICA Nº 001/2026

## Proposta de transformação do QuizBot Telegram em aplicativo Android nativo

**Projeto:** QuizBot — plataforma de quizzes educacionais  
**Plataforma-alvo:** Android  
**Tecnologia recomendada:** Kotlin + Jetpack Compose + Material 3  
**Data:** 30 de outubro de 2026  
**Versão:** 1.0  
**Status:** Proposta conceitual de UI/UX

---

## 1. Objetivo

Esta nota técnica apresenta uma proposta de experiência do usuário e interface para transformar o QuizBot atualmente operado pelo Telegram em um aplicativo Android nativo, com foco em clareza, aprendizagem, retenção e escalabilidade do produto.

A recomendação é preservar a lógica e o conteúdo já existentes no bot, mas substituir a interação baseada em mensagens por um fluxo visual estruturado, com progresso, seleção de alternativas, feedback pedagógico e acompanhamento de desempenho.

---

## 2. Contexto e diagnóstico

O fluxo atual utiliza o formato conversacional do Telegram para:

- iniciar um novo quiz;
- escolher o modo de perguntas;
- definir a quantidade de questões;
- apresentar uma questão com alternativas numeradas;
- receber a resposta do usuário;
- informar se a resposta está correta ou incorreta;
- apresentar a explicação da questão.

Esse fluxo é funcional, porém apresenta limitações quando utilizado como experiência principal de aprendizagem:

1. **Baixa visibilidade de progresso:** o usuário não visualiza claramente quantas questões respondeu nem quanto falta para concluir.
2. **Interação pouco estruturada:** escolhas importantes aparecem como mensagens, dificultando a compreensão da sequência.
3. **Dependência de respostas numéricas:** o usuário precisa digitar a alternativa, aumentando a possibilidade de erro de interação.
4. **Feedback extenso:** a explicação completa aparece em um bloco grande, dificultando a leitura e a identificação da informação principal.
5. **Pouco aproveitamento do histórico:** o fluxo não evidencia evolução, desempenho por tema ou revisão de erros.

### Diagnóstico geral

O conteúdo do QuizBot é adequado para uma aplicação educacional. A principal oportunidade está em converter uma conversa linear em uma jornada de estudo orientada, visual e mensurável.

---

## 3. Diretriz de produto

A proposta de posicionamento é:

> **QuizBot: aprenda, pratique e acompanhe sua evolução.**

O aplicativo deve combinar a simplicidade de um quiz com recursos de aprendizagem contínua. A experiência deve ser amigável, mas não infantilizada; descontraída, mas suficientemente profissional para conteúdos técnicos, como toxicologia ocupacional e segurança do trabalho.

A referência de experiência pode combinar elementos de:

- aplicativo de aprendizagem;
- formulário interativo;
- painel de desempenho;
- sistema de revisão inteligente.

---

## 4. Arquitetura de informação recomendada

A navegação principal deve conter quatro áreas:

### 4.1 Início

Painel com saudação, continuidade do último quiz, indicadores resumidos e atalhos para iniciar uma nova sessão ou revisar erros.

### 4.2 Explorar / Novo quiz

Área para escolher tema, categoria, modo, quantidade de questões, dificuldade e configurações opcionais.

### 4.3 Histórico / Progresso

Registro de sessões concluídas, percentuais de acerto, tempo médio, evolução e tópicos com maior dificuldade.

### 4.4 Perfil

Preferências, tema claro/escuro, tamanho do texto, notificações, configurações de conta e sincronização.

Para um MVP mais enxuto, a navegação pode ser reduzida para: **Início, Novo Quiz, Progresso e Perfil**.

---

## 5. Fluxo funcional proposto

```text
Início
  → Novo quiz
  → Escolher tema ou categoria
  → Escolher modo
  → Definir quantidade de questões
  → Configurar opções avançadas
  → Confirmar configuração
  → Responder questão
  → Confirmar alternativa
  → Visualizar feedback e explicação
  → Avançar para próxima questão
  → Visualizar resultado
  → Revisar erros ou iniciar novo quiz
```

O fluxo deve permitir que o usuário abandone e retome uma sessão sem perder o progresso.

---

## 6. Especificação das telas

### 6.1 Tela inicial — Dashboard

A tela inicial deve responder rapidamente a três perguntas:

- Onde estou?
- O que posso fazer agora?
- Como está meu desempenho?

**Componentes recomendados:**

- saudação personalizada;
- card “Continuar estudo”;
- botão “Novo quiz”;
- botão “Revisar erros”;
- taxa média de acertos;
- sequência de dias de estudo;
- quantidade de questões respondidas.

**Exemplo de conteúdo:**

> Olá, Carlos.  
> Continue seu estudo — Toxicologia Ocupacional, 7 de 20 questões.  
> [Continuar quiz] [Novo quiz] [Revisar erros]

---

### 6.2 Configuração de novo quiz

O atual conjunto de mensagens deve ser convertido em uma sequência de etapas curtas.

#### Etapa A — Tema

Apresentar cards com nome do tema, descrição curta e quantidade de questões disponíveis.

Exemplos:

- Toxicologia Ocupacional;
- Segurança do Trabalho;
- Legislação Trabalhista;
- Todos os temas.

Se houver apenas um tema, utilizar categorias, como limites de tolerância, exposição ocupacional, agentes químicos e prevenção.

#### Etapa B — Modo

Oferecer cards para:

- **Prática:** questões aleatórias sem pressão de tempo;
- **Simulado:** quantidade e tempo definidos;
- **Revisão:** somente questões respondidas incorretamente;
- **Desafio:** questões de maior dificuldade.

#### Etapa C — Quantidade

Oferecer opções rápidas de 5, 10, 20 e 30 questões, além de quantidade personalizada e modo infinito.

#### Etapa D — Opções avançadas

Configurações opcionais, inicialmente recolhidas:

- exibir explicação após a resposta;
- ativar cronômetro;
- embaralhar alternativas;
- selecionar dificuldade;
- permitir marcação para revisão.

---

### 6.3 Tela de questão

A questão deve ser o foco visual da tela.

**Estrutura recomendada:**

- nome do tema no topo;
- indicador “Questão 3 de 10”;
- barra de progresso;
- enunciado em card de leitura confortável;
- alternativas como cards selecionáveis;
- botão “Confirmar resposta”;
- opção discreta para marcar a questão.

As respostas não devem depender de digitação numérica. Cada alternativa deve ser um componente clicável com letra, texto e estado visual de seleção.

**Requisitos de interação:**

- destacar a alternativa selecionada;
- manter o botão de confirmação desativado até haver uma seleção;
- impedir mudança acidental após confirmação;
- oferecer feedback tátil discreto;
- suportar perguntas longas com rolagem confortável.

---

### 6.4 Feedback da resposta

O feedback deve ser dividido em camadas.

#### Camada resumida

Apresentar imediatamente:

- resultado: correto ou incorreto;
- resposta escolhida;
- resposta correta;
- explicação curta;
- botão “Próxima questão”.

#### Camada detalhada

Disponibilizar “Ver análise das alternativas” para expandir a justificativa de cada opção, preservando o conteúdo detalhado já existente no bot.

**Resposta correta:** usar linguagem de reforço, por exemplo, “Muito bem! Você acertou.”

**Resposta incorreta:** evitar linguagem punitiva. Usar, por exemplo, “Quase lá. Veja a explicação e tente novamente na revisão.”

O feedback não deve depender somente de cor. Usar também texto, ícone e hierarquia visual.

---

### 6.5 Resultado do quiz

A conclusão deve apresentar uma síntese objetiva:

- percentual de acertos;
- acertos e erros;
- tempo total;
- tempo médio por questão;
- comparação com melhor resultado anterior;
- temas de maior dificuldade;
- recomendação de próxima atividade.

**Ações principais:**

- Revisar erros;
- Tentar novamente;
- Voltar ao início.

---

### 6.6 Revisão de erros

A revisão é um recurso estratégico para diferenciar o app de um quiz simples.

Cada item deve exibir:

- tema da questão;
- resposta dada;
- resposta correta;
- data da tentativa;
- botão “Revisar”.

O aplicativo deve permitir iniciar um quiz composto exclusivamente pelas questões erradas.

---

## 7. Direção visual

### 7.1 Estilo

Recomenda-se uma interface moderna, limpa e educacional, sem reproduzir visualmente o Telegram. A personalidade amigável do bot pode ser preservada por meio da linguagem e de pequenos elementos ilustrativos.

Características:

- fundo neutro;
- cards com cantos arredondados;
- tipografia de alta legibilidade;
- ícones simples;
- espaçamento generoso;
- uma cor principal consistente;
- animações rápidas e funcionais.

### 7.2 Paleta sugerida

- Azul principal: `#3157D5`;
- Azul claro de apoio: `#EAF0FF`;
- Verde de acerto: `#27A66F`;
- Coral de erro: `#D9535F`;
- Fundo: `#F7F8FC`;
- Texto principal: `#1C1E26`;
- Texto secundário: `#707482`.

Como alternativa de posicionamento mais tecnológico, o azul principal pode ser substituído por roxo `#6750A4`.

### 7.3 Tipografia e componentes

- Material 3 como base de componentes;
- títulos fortes para o enunciado;
- corpo entre 16 e 18 sp para leitura principal;
- botões com área mínima de 48 dp;
- cantos entre 12 e 20 dp;
- elevação discreta, sem excesso de sombras.

---

## 8. Acessibilidade e requisitos de qualidade

Devem ser considerados desde a primeira versão:

- suporte ao tamanho de fonte do sistema;
- contraste adequado;
- áreas de toque de no mínimo 48 dp;
- compatibilidade com leitor de tela;
- indicação de estado por texto e ícone, além da cor;
- modo escuro;
- navegação confortável por perguntas extensas;
- preservação automática da sessão;
- funcionamento parcial offline, quando aplicável;
- feedback sonoro e tátil configurável.

---

## 9. Arquitetura técnica recomendada

### 9.1 Stack

- Kotlin;
- Jetpack Compose;
- Material 3;
- Navigation Compose;
- ViewModel;
- StateFlow;
- Room para questões, histórico e sessões locais;
- DataStore para preferências;
- Retrofit ou Ktor para API remota;
- Hilt para injeção de dependência;
- WorkManager para sincronização em segundo plano.

### 9.2 Organização de pacotes

```text
ui/
  home/
  quizsetup/
  question/
  result/
  review/
  history/
  profile/

domain/
  model/
  usecase/

data/
  local/
  remote/
  repository/
```

### 9.3 Estado da tela de questão

```kotlin
data class QuizQuestionUiState(
    val questionNumber: Int = 1,
    val totalQuestions: Int = 10,
    val question: String = "",
    val options: List<AnswerOption> = emptyList(),
    val selectedOption: Int? = null,
    val answerSubmitted: Boolean = false,
    val isCorrect: Boolean? = null,
    val explanation: String? = null
)
```

A separação entre estado da questão, seleção, submissão, feedback e navegação facilita testes e evolução do produto.

---

## 10. Escopo recomendado para o MVP

A primeira versão deve priorizar a experiência principal, evitando excesso de funcionalidades.

### Incluído no MVP

1. Home;
2. seleção de tema ou categoria;
3. seleção de modo;
4. seleção de quantidade;
5. tela de questão com alternativas clicáveis;
6. feedback imediato com explicação;
7. resultado final;
8. revisão de erros;
9. histórico básico;
10. tema claro e escuro;
11. persistência local da sessão.

### Pós-MVP

- ranking e desafios;
- metas semanais;
- notificações inteligentes;
- gamificação avançada;
- geração de questões por IA;
- sincronização entre dispositivos;
- painel administrativo para conteúdo;
- relatórios por assunto e dificuldade.

---

## 11. Critérios de sucesso

A solução poderá ser avaliada pelos seguintes indicadores:

- redução de abandono antes da primeira questão;
- aumento da taxa de conclusão dos quizzes;
- redução de erros de interação na seleção de respostas;
- aumento do retorno para revisão de erros;
- aumento da frequência semanal de uso;
- compreensão do resultado sem necessidade de instrução adicional;
- tempo médio para iniciar um novo quiz.

---

## 12. Conclusão e recomendação

Recomenda-se não replicar o Telegram dentro do aplicativo. O bot deve ser tratado como fonte de conteúdo e regras de negócio, enquanto o novo aplicativo deve oferecer uma experiência visual orientada ao estudo.

A prioridade deve ser transformar a conversa linear em um fluxo com progresso, decisões claras, feedback em camadas e histórico de aprendizagem. Para o lançamento inicial, a combinação de **Home, configuração de quiz, questão, feedback, resultado e revisão de erros** oferece o melhor equilíbrio entre valor para o usuário e esforço de desenvolvimento.

A implementação em Kotlin com Jetpack Compose é adequada para esse objetivo, pois permite construir uma interface moderna, responsiva, testável e preparada para evolução futura.

---

**Responsável pela proposta:** Assistente de produto e UX  
**Documento:** Nota técnica de UI/UX do QuizBot  
**Versão:** 1.0
