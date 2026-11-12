# Cogniva 1.4.17 / build 24 — ajustes no editor de concursos

Estado: alterações locais, ainda não publicadas. A versão em produção permanece 1.4.16/build 23.

- A explicação sobre a estrutura dos estudos passa a ficar somente em **Ajustes → Ajuda**. O card foi retirado de Concursos, Gerenciar concursos e Inserir questões.
- O editor reserva uma barra inferior para Continuar, Voltar e Salvar, separada do formulário rolável e protegida pelos recuos de navegação e teclado. Trocar de etapa fecha o teclado e reinicia a rolagem.
- As etapas se acomodam em linhas e a exclusão usa um ícone acessível no cabeçalho, evitando comprimir o título com fonte ampliada.
- A mudança não altera a grade, a persistência ou a sincronização dos concursos.

## Verificação

Os seis arquivos Kotlin alterados e os dois arquivos de teste foram compilados diretamente com Kotlin 2.1.20 e as dependências locais, sem comunicação de rede. Quatro testes de captura Paparazzi passaram e as quatro imagens foram inspecionadas. Elas cobrem edição e gerenciamento com fonte 1× e 2× e recuo inferior de 48 dp no editor.

A execução Gradle normal e o emulador permanecem bloqueados pelas restrições desta sessão, inclusive para comunicação com processos em localhost. O teste instrumentado `ExamManagementTest` foi ampliado para abrir a edição pelo botão de gerenciamento, conferir que o formulário não invade a barra de ações nas três etapas e verificar a preservação da grade ao salvar. Sua execução em dispositivo/emulador e os gates completos de publicação continuam pendentes.

Antes de publicar: executar os gates normais de JVM, lint, Paparazzi e emulador, gerar e assinar o APK pelo pipeline de release.
