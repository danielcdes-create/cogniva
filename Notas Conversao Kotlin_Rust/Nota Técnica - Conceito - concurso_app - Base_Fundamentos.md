# Nota Técnica --- Conceito e Diretrizes do Aplicativo de Preparação para Concursos Públicos

**Versão:** 1.0\
**Data:** 30 de outubro de 2026\
**Status:** Documento de concepção --- diretrizes iniciais para produto,
UX/UI e arquitetura Android\
**Plataforma-alvo:** Android\
**Tecnologia principal:** Kotlin + Jetpack Compose

------------------------------------------------------------------------

## 1. Objetivo

Transformar o QuizBot atualmente utilizado no Telegram em um aplicativo
Android nativo, com experiência de uso própria e foco em **preparação
estratégica para concursos públicos**.

O produto deverá atender a dois públicos ou momentos de preparação:

1.  **Concursos com edital publicado:** preparação orientada pelo
    conteúdo programático oficial, banca examinadora, cronograma e data
    da prova.
2.  **Concursos sem edital publicado:** preparação antecipada por
    carreira, área de atuação, disciplinas recorrentes e conteúdos
    historicamente relevantes.

O aplicativo não deve ser percebido apenas como um quiz ou ferramenta de
perguntas e respostas. A proposta é construir uma **plataforma de
preparação, aprendizagem e avaliação de desempenho**, na qual a
resolução de questões seja o principal mecanismo de interação e
diagnóstico.

## 2. Posicionamento do produto

### 2.1 Proposta de valor

**Prepare-se antes. Chegue competitivo.**

A plataforma deve ajudar o candidato a:

-   organizar sua preparação por concurso, carreira ou área;
-   estudar antes ou depois da publicação de um edital;
-   resolver questões e compreender os fundamentos das respostas;
-   identificar lacunas de conhecimento;
-   revisar conteúdos com base em desempenho e retenção;
-   acompanhar sua evolução ao longo do tempo;
-   realizar simulados alinhados ao concurso e à banca.

### 2.2 Princípios do produto

1.  **Preparação contínua:** o candidato não precisa aguardar a
    publicação de um edital para começar.
2.  **Conteúdo estruturado:** questões, disciplinas e assuntos devem
    possuir metadados consistentes e reutilizáveis.
3.  **Aprendizagem orientada por evidências:** recomendações devem
    considerar histórico de respostas, dificuldade, confiança e
    recorrência dos erros.
4.  **Contexto do concurso:** a experiência deve considerar carreira,
    órgão, edital, banca e conteúdo programático quando essas
    informações estiverem disponíveis.
5.  **Clareza e baixo atrito:** a interface deve permitir iniciar uma
    sessão de estudo com poucos passos.
6.  **Transparência analítica:** indicadores devem explicar o que medem
    e não devem ser apresentados como probabilidade de aprovação sem
    validação estatística.

## 3. Direção de identidade visual e UX

### 3.1 Conceito visual

Direção recomendada: **Modern Learning Platform**, com aparência
profissional, tecnológica e organizada.

Referências conceituais de experiência:

-   Duolingo: fluidez, progressão e incentivo à continuidade;
-   Brilliant: aprendizagem estruturada e experiência centrada no
    conteúdo;
-   Google Material 3: consistência visual, acessibilidade e componentes
    nativos.

Essas referências são direcionais, não uma proposta de replicação
visual.

### 3.2 Paleta e linguagem visual

-   **Azul-marinho:** cor institucional, associada à confiança e
    concentração.
-   **Azul elétrico:** ações primárias, seleção e indicadores de
    progresso.
-   **Branco e cinzas neutros:** superfícies e leitura.
-   **Verde:** acertos, metas atingidas e evolução positiva.
-   **Âmbar:** atenção, revisão necessária e desempenho intermediário.
-   **Vermelho:** erros e alertas relevantes.

A aplicação deverá oferecer temas claro e escuro. Recomenda-se
disponibilizar ambos desde a primeira versão, com preferência inicial
configurável.

### 3.3 Princípios de interface

-   Priorizar componentes nativos e padrões de navegação Android.
-   Evitar reproduzir a experiência de conversa do Telegram.
-   Usar hierarquia visual clara, tipografia legível e espaçamento
    consistente.
-   Dar destaque ao concurso ou objetivo ativo do candidato.
-   Evitar excesso de gamificação, notificações ou elementos
    decorativos.
-   Apresentar informações de desempenho de forma objetiva e
    interpretável.

## 4. Arquitetura de navegação

Recomenda-se uma navegação principal por barra inferior (*Bottom
Navigation Bar*) com cinco áreas:

  -----------------------------------------------------------------------
  Área                                Responsabilidade
  ----------------------------------- -----------------------------------
  Início                              Painel do candidato, objetivo
                                      ativo, progresso e plano do dia

  Concursos / Biblioteca              Concursos, carreiras, disciplinas,
                                      assuntos e banco de questões

  Praticar                            Quizzes, sessões personalizadas e
                                      simulados

  Desempenho                          Indicadores, evolução, domínio e
                                      análise de erros

  Configurações                       Perfil, preferências, conta e
                                      personalização
  -----------------------------------------------------------------------

A nomenclatura final pode ser ajustada após testes de usabilidade. A
estrutura deve manter acesso rápido às ações mais frequentes: continuar
estudando, iniciar questões e revisar erros.

## 5. Home --- painel do candidato

A tela inicial deve funcionar como um centro de comando da preparação.

### 5.1 Componentes recomendados

1.  **Identificação do candidato:** saudação discreta e acesso ao
    perfil.
2.  **Objetivo ativo:** concurso, carreira ou área prioritária.
3.  **Situação da preparação:** com edital ou sem edital.
4.  **Indicadores resumidos:** taxa de acerto, questões respondidas,
    tempo de estudo e disciplinas acompanhadas.
5.  **Continuar preparação:** retomada da última atividade.
6.  **Plano de hoje:** tarefas, disciplinas e metas pendentes.
7.  **Acesso rápido:** iniciar quiz aleatório, escolher disciplina,
    revisar erros ou iniciar simulado.

### 5.2 Contextualização

A Home deve adaptar seus elementos ao estado do candidato:

-   **Com edital:** mostrar cobertura do edital, prazo até a prova,
    disciplinas pendentes e progresso do cronograma.
-   **Sem edital:** mostrar objetivo de carreira, trilha de disciplinas,
    consistência de estudo e evolução da base de conhecimento.

Dados e indicadores exibidos em protótipos devem ser identificados como
ilustrativos até que exista instrumentação real.

## 6. Dois modos de preparação

### 6.1 Modo A --- concurso com edital publicado

Fluxo recomendado:

1.  Selecionar ou cadastrar o concurso.
2.  Informar órgão, cargo, banca e data da prova, quando conhecidos.
3.  Importar ou cadastrar o edital.
4.  Estruturar o conteúdo programático em disciplinas, assuntos e
    subassuntos.
5.  Relacionar questões aos itens do edital.
6.  Criar cronograma e metas de estudo.
7.  Realizar quizzes e simulados alinhados ao conteúdo e à banca.
8.  Acompanhar cobertura, desempenho e lacunas.

Funcionalidades prioritárias:

-   cadastro e gerenciamento de concursos;
-   importação ou cadastro de edital;
-   matriz de disciplinas e conteúdos;
-   controle de cobertura do edital;
-   cronograma até a prova;
-   simulados por banca e conteúdo;
-   análise de desempenho por disciplina, assunto e subassunto.

A importação de edital deverá prever revisão humana, pois documentos
podem conter tabelas, hierarquias e textos que exigem interpretação.

### 6.2 Modo B --- preparação antecipada, sem edital

Fluxo recomendado:

1.  Selecionar uma carreira ou área de interesse.
2.  Escolher disciplinas e conteúdos-base.
3.  Definir prioridade ou objetivo de preparação.
4.  Construir uma trilha de estudo de longo prazo.
5.  Resolver questões por disciplina, assunto, banca e nível.
6.  Acompanhar domínio e retenção.
7.  Adaptar o plano conforme desempenho e mudanças no cenário dos
    concursos.

Áreas exemplificativas:

-   Área Fiscal;
-   Área Policial;
-   Área Administrativa;
-   Área de Controle;
-   Área Jurídica.

A plataforma não deve tratar previsões de concursos como fatos.
Informações sobre concursos potenciais, autorizações, comissões, bancas
e expectativas devem possuir fonte, data de atualização e classificação
de confiabilidade, quando forem incorporadas ao produto.

## 7. Biblioteca e organização do conteúdo

A biblioteca deverá permitir navegar por concursos, carreiras,
disciplinas e assuntos.

### 7.1 Estrutura conceitual

Para concursos com edital:

`Concurso → Edital → Disciplina → Assunto → Subassunto → Questão`

Para preparação sem edital:

`Carreira → Área de conhecimento → Disciplina → Assunto → Subassunto → Questão`

Uma questão poderá estar associada a vários concursos, editais ou
trilhas, sem que seja necessário duplicar seu registro.

### 7.2 Metadados recomendados para questões

Cada questão deverá permitir registrar, quando aplicável:

-   enunciado;
-   alternativas;
-   gabarito;
-   justificativa da resposta correta;
-   comentários sobre alternativas incorretas;
-   disciplina;
-   assunto e subassunto;
-   banca examinadora;
-   ano;
-   órgão e cargo;
-   concurso ou edital de origem;
-   nível de dificuldade;
-   tipo de questão;
-   fonte e referência;
-   situação de revisão ou validação;
-   data de inclusão e atualização;
-   direitos de uso ou licença, quando aplicável.

A origem e os direitos de uso do conteúdo precisam ser tratados como
requisitos do produto. Questões de terceiros não devem ser reproduzidas
sem base legítima de utilização.

## 8. Experiência de resolução de questões

A tela de questão é uma das experiências centrais do aplicativo e deve
substituir a interação textual do Telegram.

### 8.1 Elementos da tela

-   botão de retorno;
-   identificação da disciplina ou sessão;
-   número da questão e total;
-   barra de progresso;
-   indicação de dificuldade, quando disponível;
-   enunciado;
-   alternativas selecionáveis por toque;
-   opção de marcar questão para revisão;
-   registro opcional do nível de confiança;
-   botão para confirmar resposta.

### 8.2 Confiança da resposta

Antes de revelar o resultado, o candidato poderá informar se está:

-   pouco confiante;
-   moderadamente confiante;
-   muito confiante.

Esse registro deve ser separado do acerto ou erro. Ele permitirá
identificar situações como:

-   acerto com alta confiança;
-   acerto com baixa confiança;
-   erro com baixa confiança;
-   erro com alta confiança.

O último caso merece atenção especial, pois pode indicar uma compreensão
equivocada mantida pelo candidato.

### 8.3 Feedback e correção

Após a confirmação, a tela deverá apresentar:

1.  resultado visual imediato;
2.  resposta correta;
3.  explicação do fundamento;
4.  análise individual das alternativas;
5.  possibilidade de aprofundar o assunto;
6.  opção de favoritar ou sinalizar a questão;
7.  inclusão em uma fila de revisão, conforme regras configuradas.

O feedback deve explicar por que cada alternativa está correta ou
incorreta, evitando mensagens genéricas.

## 9. Funcionalidades de aprendizagem e preparação

  -----------------------------------------------------------------------
  Funcionalidade                      Objetivo
  ----------------------------------- -----------------------------------
  Revisão de erros                    Reapresentar questões e assuntos
                                      com dificuldades

  Banco de questões                   Organizar questões por disciplina,
                                      assunto, banca e concurso

  Quiz adaptativo                     Ajustar a seleção de questões ao
                                      desempenho

  Histórico                           Consultar sessões e respostas
                                      anteriores

  Favoritos                           Guardar questões para consulta e
                                      revisão

  Simulados                           Reproduzir uma prova com
                                      quantidade, tempo e distribuição
                                      definidos

  Estatísticas                        Medir acertos, erros, tempo e
                                      evolução

  Explicação aprofundada              Aprofundar conceitos após a
                                      resposta

  Revisão programada                  Priorizar conteúdos conforme
                                      desempenho e retenção
  -----------------------------------------------------------------------

### 9.1 Inteligência de bancas

A plataforma poderá identificar características de cobrança de bancas
como Cebraspe, FGV e FCC, desde que existam dados suficientes e
classificados corretamente.

Possíveis aplicações:

-   filtros de questões por banca;
-   simulados com distribuição semelhante à banca;
-   análise de desempenho por banca;
-   identificação de formatos e temas recorrentes;
-   recomendações de treino específicas.

Não se deve afirmar que um padrão é representativo sem amostra adequada,
período definido e metodologia documentada.

### 9.2 Revisão inteligente

A revisão deverá priorizar conteúdos com base em sinais como:

-   erros recentes;
-   repetição de erros;
-   tempo desde a última revisão;
-   baixa confiança;
-   dificuldade do assunto;
-   desempenho em tentativas sucessivas.

A lógica pode evoluir para um sistema de repetição espaçada, com
parâmetros ajustados a partir de resultados reais.

## 10. Analytics e indicadores

O módulo de desempenho deverá ir além de uma taxa geral de acertos.

### 10.1 Indicadores básicos

-   questões respondidas;
-   percentual de acertos;
-   percentual de erros;
-   tempo médio por questão;
-   tempo de estudo;
-   evolução por período;
-   desempenho por disciplina;
-   desempenho por assunto e subassunto;
-   desempenho por banca;
-   questões revisadas;
-   consistência de estudo.

### 10.2 Mapa de domínio

O mapa de domínio deverá mostrar os pontos fortes e as lacunas do
candidato por disciplina e assunto.

Exemplo ilustrativo:

  Disciplina                 Domínio estimado
  ------------------------ ------------------
  Direito Constitucional                  88%
  Português                               76%
  Raciocínio Lógico                       54%
  Direito Administrativo                  82%

A visualização pode utilizar barras de progresso e faixas de atenção,
desde que a escala seja definida e documentada.

### 10.3 Índice de prontidão

Propõe-se um **Índice de Prontidão**, destinado a sintetizar a
preparação em relação a um concurso ou matriz de referência.

O indicador poderá considerar:

-   cobertura do conteúdo programático;
-   desempenho nas questões;
-   distribuição do desempenho entre disciplinas;
-   dificuldade das questões;
-   atualidade das respostas;
-   consistência e quantidade de evidências.

O índice não deve ser confundido com probabilidade de aprovação. Essa
interpretação exigiria validação estatística com dados de candidatos e
resultados reais de provas.

Na preparação sem edital, o índice deverá ser associado a uma matriz de
referência da carreira ou área, e não a um edital inexistente.

## 11. Arquitetura técnica proposta

### 11.1 Aplicativo Android

  Camada / função               Tecnologia recomendada
  ----------------------------- ----------------------------------------
  Linguagem                     Kotlin
  Interface                     Jetpack Compose
  Design System                 Material 3
  Arquitetura de apresentação   MVVM
  Organização de domínio        Clean Architecture, quando justificada
  Banco local                   Room
  Preferências                  DataStore
  Processamento assíncrono      Kotlin Coroutines e Flow
  Navegação                     Navigation Compose
  Gráficos                      Vico Charts ou alternativa compatível

### 11.2 Backend

A recomendação inicial é não reconstruir imediatamente toda a lógica
existente no QuizBot.

Antes, deve-se avaliar o backend atual e identificar quais funções podem
ser reutilizadas por meio de API:

-   obtenção de questões;
-   validação de respostas;
-   cálculo de pontuação;
-   geração de explicações;
-   gerenciamento de sessões;
-   histórico de desempenho.

A interface Android deverá ser desacoplada da implementação do bot. Se o
backend atual não possuir uma API adequada, poderá ser criada uma camada
de serviços para expor as funcionalidades necessárias.

### 11.3 Responsabilidades

**Aplicativo Android:**

-   experiência de navegação;
-   apresentação de conteúdo;
-   interação com questões;
-   armazenamento local e preferências;
-   exibição de histórico e indicadores;
-   sincronização com backend.

**Backend:**

-   autenticação e autorização;
-   gestão de usuários;
-   banco central de questões;
-   concursos, editais e trilhas;
-   validação de respostas;
-   histórico consolidado;
-   regras de recomendação e revisão;
-   APIs de integração.

A sincronização e o funcionamento offline devem ser avaliados como
requisitos. É recomendável permitir acesso local a conteúdos previamente
baixados, com sincronização posterior.

## 12. Modelo de domínio inicial

Entidades conceituais recomendadas:

  Entidade          Descrição
  ----------------- ---------------------------------------------
  Usuário           Candidato e suas preferências
  Objetivo          Concurso, carreira ou área prioritária
  Concurso          Concurso público específico
  Edital            Documento e versão do conteúdo programático
  Banca             Instituição organizadora
  Cargo             Cargo ou especialidade
  Disciplina        Matéria de estudo
  Assunto           Unidade de conteúdo
  Questão           Item de avaliação
  Alternativa       Opção de resposta
  Tentativa         Resposta do candidato em uma questão
  Sessão            Conjunto de questões respondidas
  Simulado          Prova estruturada
  Plano de estudo   Organização de atividades e metas
  Revisão           Registro de conteúdo a revisar
  Fonte             Origem e referência do conteúdo

### 12.1 Relacionamentos importantes

-   Uma questão pode estar associada a várias disciplinas ou
    classificações, se necessário.
-   Uma questão pode aparecer em diferentes concursos e trilhas.
-   Um edital possui uma versão identificável e uma estrutura de
    conteúdo programático.
-   Uma tentativa deve preservar a questão e as alternativas
    apresentadas naquele momento, para manter a integridade histórica.
-   O desempenho deve ser calculável por período, disciplina, assunto,
    banca e objetivo.

A estrutura final deverá ser refinada durante a modelagem de dados,
evitando relacionamentos redundantes ou classificações ambíguas.

## 13. Escopo recomendado para o MVP

A primeira versão deve validar a proposta central sem tentar implementar
todas as funcionalidades avançadas.

### Prioridade 1 --- núcleo funcional

1.  Cadastro e perfil do candidato.
2.  Seleção de carreira ou concurso.
3.  Organização de disciplinas e assuntos.
4.  Banco de questões estruturado.
5.  Tela de resolução de questões.
6.  Correção com explicação.
7.  Registro de tentativas e histórico.
8.  Indicadores básicos de desempenho.
9.  Tema claro e escuro.

### Prioridade 2 --- preparação orientada

1.  Cadastro de concursos com edital.
2.  Estruturação do conteúdo programático.
3.  Trilhas para preparação sem edital.
4.  Filtros por banca, ano e assunto.
5.  Favoritos e revisão de erros.
6.  Simulados básicos.
7.  Cronograma e metas.

### Prioridade 3 --- inteligência e personalização

1.  Revisão espaçada adaptativa.
2.  Quiz adaptativo.
3.  Análise de padrões de bancas.
4.  Índice de prontidão.
5.  Recomendações personalizadas.
6.  Explicações aprofundadas com IA.
7.  Importação assistida de editais.
8.  Alertas e atualização de informações sobre concursos.

## 14. Diretrizes de qualidade e governança do conteúdo

A qualidade do banco de questões será determinante para a credibilidade
do aplicativo.

Requisitos recomendados:

-   identificar a fonte de cada questão;
-   registrar banca, ano e concurso de origem quando conhecidos;
-   manter histórico de alterações;
-   permitir sinalização de gabarito ou justificativa possivelmente
    incorretos;
-   estabelecer processo de revisão e validação;
-   diferenciar conteúdo oficial, conteúdo editorial e conteúdo gerado
    por IA;
-   identificar questões desatualizadas ou dependentes de legislação
    alterada;
-   respeitar direitos autorais e licenças de uso.

Em matérias jurídicas e normativas, deverá existir controle de vigência
e data de referência. Uma questão correta em determinado contexto
temporal pode deixar de ser adequada após alteração legislativa ou
jurisprudencial.

## 15. Riscos e pontos de atenção

  -----------------------------------------------------------------------
  Risco                               Diretriz de mitigação
  ----------------------------------- -----------------------------------
  Banco de questões inconsistente     Metadados obrigatórios, validação e
                                      controle de qualidade

  Gabaritos incorretos                Revisão, fontes e canal de
                                      contestação

  Conteúdo jurídico desatualizado     Controle de vigência e atualização

  Indicadores enganosos               Definições transparentes e
                                      validação metodológica

  Previsões de concursos imprecisas   Fontes, datas e classificação de
                                      confiabilidade

  Dependência do Telegram             API independente e desacoplamento
                                      do backend

  Escopo excessivo no MVP             Entrega incremental e priorização
                                      do núcleo de questões

  Uso indevido de conteúdo de         Gestão de licenças e direitos de
  terceiros                           uso
  -----------------------------------------------------------------------

## 16. Decisões de produto consolidadas

1.  O produto será um aplicativo Android nativo, desenvolvido em Kotlin.
2.  O foco será preparação para concursos públicos, e não quizzes
    genéricos.
3.  A plataforma atenderá concursos com edital publicado e preparação
    antecipada sem edital.
4.  A experiência deixará de ser conversacional, substituindo o fluxo do
    Telegram por uma interface nativa.
5.  O banco de questões será o núcleo funcional e de dados do produto.
6.  Concursos, editais, carreiras, disciplinas e assuntos serão camadas
    de organização e contextualização.
7.  A experiência de resolução e correção deverá ser rápida, clara e
    didática.
8.  Analytics e revisão de erros serão partes fundamentais da proposta
    de valor.
9.  O backend atual do QuizBot deverá ser avaliado para possível
    reutilização via API.
10. A primeira entrega deverá priorizar Home, seleção de objetivo,
    biblioteca, questão, correção e desempenho básico.

## 17. Próximas etapas recomendadas

### Etapa 1 --- diagnóstico técnico do QuizBot

-   mapear linguagem, backend, banco de dados e hospedagem;
-   identificar funções existentes;
-   verificar estrutura e qualidade do banco de questões;
-   avaliar possibilidade de exposição via API;
-   mapear dependências do Telegram.

### Etapa 2 --- especificação funcional

-   definir jornadas do candidato;
-   detalhar os dois modos de preparação;
-   especificar entidades e metadados;
-   definir regras de sessão, correção e pontuação;
-   estabelecer métricas e fórmulas dos indicadores.

### Etapa 3 --- protótipo UX/UI

Criar protótipo navegável em Jetpack Compose, começando por:

1.  Home;
2.  configuração do objetivo e do quiz;
3.  biblioteca de concursos e disciplinas;
4.  resolução de questão;
5.  correção;
6.  desempenho.

### Etapa 4 --- MVP Android

-   implementar navegação e design system;
-   integrar banco de questões e backend;
-   implementar sessões e histórico;
-   disponibilizar indicadores básicos;
-   testar usabilidade, desempenho e sincronização.

### Etapa 5 --- evolução

-   incluir editais e cronogramas;
-   desenvolver simulados;
-   implementar revisão inteligente;
-   adicionar análise de bancas;
-   evoluir analytics e personalização.

------------------------------------------------------------------------

## 18. Síntese executiva

A proposta é evoluir o QuizBot para uma **plataforma Android de
preparação estratégica para concursos públicos**, que acompanhe o
candidato desde a preparação antecipada até a preparação direcionada a
um edital específico.

O produto terá como núcleo um banco de questões estruturado, associado a
carreiras, concursos, editais, disciplinas e assuntos. A resolução de
questões será o principal mecanismo de aprendizagem e coleta de
evidências sobre o domínio do candidato.

A diferenciação não estará apenas em oferecer perguntas e respostas, mas
em organizar a preparação, explicar erros, identificar lacunas,
recomendar revisões e demonstrar a evolução do conhecimento.

**Diretriz central de desenvolvimento:** construir primeiro uma
plataforma sólida de questões, objetivos e desempenho; adicionar
progressivamente gestão de editais, simulados e inteligência adaptativa.
