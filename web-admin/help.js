// Product guidance: keep actions and field names aligned with the portal.
export const helpTopics = [
  {id:'primeiros-passos',title:'Por onde começar',category:'Começar',keywords:'início tutorial portal administração ajuda',intro:'O portal organiza sua preparação e o acervo compartilhado. Você pode preparar uma grade antes de existir edital novo.',steps:[
    'Confira os concursos já cadastrados em Concursos antes de criar outro.',
    'Abra a grade do concurso e selecione disciplinas, assuntos e subassuntos.',
    'Insira ou revise questões, classificando o conteúdo cobrado.',
    'Use Rascunhos e pendências para continuar edições e conferir envios sem resposta.'
  ],notes:['Concursos e grades pertencem à sua conta. Questões e catálogo são compartilhados com o Android e outras contas autorizadas.'],page:'overview',action:'Ir à visão geral'},
  {id:'estrutura',title:'Tronco, concurso, edital, grade e conteúdo: qual a diferença?',category:'Entender a estrutura',keywords:'hierarquia relação componente disciplinas assuntos subassuntos questões',intro:'Existem duas estruturas conectadas: a organização dos estudos por concurso e o catálogo de conteúdo compartilhado.',steps:[
    'Tronco de carreira agrupa uma área e relaciona disciplinas. Um concurso declara seu tronco; uma disciplina pode se relacionar a vários troncos.',
    'Concurso identifica a oportunidade e o cargo. Sua grade organiza componentes: uma disciplina em uma etapa, com peso, quantidade e conteúdo próprios.',
    'Edital é a referência oficial. A grade também pode conter uma base de preparação pré-edital, ainda estimada.',
    'No catálogo, Disciplina → Assunto → Subassunto. Um subassunto detalha um assunto da mesma disciplina.',
    'Questões são classificadas pelo conteúdo. Prova, banca e ano registram sua origem; o uso em outras grades depende da correspondência e das regras de compatibilidade.'
  ],notes:['O tronco sugere relações editoriais; ele não determina sozinho o conteúdo do edital. Incluir um assunto pai não inclui seus subassuntos automaticamente.'],page:'subjects',action:'Explorar disciplinas e assuntos'},
  {id:'visao-geral',title:'Visão geral e indicadores',category:'Telas do portal',keywords:'resumo contagem atualizar acervo dashboard',intro:'Reúne indicadores do acervo e atalhos para organizar concursos, consultar questões e revisar o catálogo.',steps:[
    'Use os cartões para distinguir concursos da sua conta de troncos, disciplinas e questões compartilhados.',
    'Abra os atalhos para a área em que deseja trabalhar.',
    'Use Atualizar indicadores para consultar novamente os totais do servidor.'
  ],notes:['Uma contagem de questões não significa que todas estão classificadas, revisadas ou disponíveis em todos os modos de treino.'],page:'overview',action:'Abrir visão geral'},
  {id:'concursos',title:'Criar e gerenciar concursos',category:'Telas do portal',keywords:'cadastro cargo instituição banca situação data escada prioridade arquivar',intro:'Em Concursos, busque uma oportunidade existente ou use Criar concurso. Cada cadastro mantém sua própria grade.',steps:[
    'Informe nome completo, nome curto, instituição, cargo e tronco de carreira. Confira o tronco: escolher uma carreira não preenche a grade automaticamente no painel.',
    'Selecione a situação correspondente ao que está confirmado. Deixe banca e data sem definição quando ainda não forem conhecidas.',
    'Configure a previsão da prova, prioridade e participação na sua escada. Corte histórico e penalidade por erro devem refletir a referência que você está usando.',
    'Monte a grade e clique em Salvar concurso e grade. Abrir grade permite continuar a edição do mesmo cadastro.',
    'Arquivar concurso retira-o da escada ativa e conserva a grade e o histórico.'
  ],notes:['Uma edição é um rascunho até a confirmação do salvamento no servidor.'],page:'exams',action:'Abrir concursos'},
  {id:'pre-edital-banco-central',title:'Exemplo: Banco Central sem edital novo',category:'Passo a passo',keywords:'pré-edital pre edital último anterior Banco Central BCB BACEN previsto ainda não saiu',intro:'Você pode estudar com uma grade estimada baseada no último edital do mesmo cargo e área, identificando essa referência no nome do cadastro.',steps:[
    'Em Concursos, procure o Banco Central. Se já houver um cadastro adequado, abra sua grade; caso contrário, crie o concurso.',
    'Use um nome como “Banco Central — cargo/área — Pré-edital — base [ano]”. Informe instituição, cargo e tronco Bancária conforme seu perfil de preparação.',
    'Use Previsto se essa for a situação conhecida. Deixe a banca e a data do novo concurso sem definição enquanto não estiverem confirmadas.',
    'Adicione as disciplinas do edital anterior do mesmo cargo/área. Organize etapas, grupos, pesos e quantidades como referência de preparação.',
    'Selecione os assuntos e subassuntos que constavam da referência. Mantenha a situação dos tópicos como Pré-edital e salve o concurso e a grade.',
    'Cadastre as questões com os dados da prova de origem real e classifique-as pelos mesmos conteúdos da grade.',
    'Quando sair o edital novo, revise o que mudou. Só então confirme o conteúdo oficial com Publicar esta grade como edital.'
  ],notes:['O painel não importa automaticamente uma grade a partir de PDF. A montagem atual é manual. O exemplo não afirma qual é o último edital nem confirma banca, cargo ou datas de um novo concurso.'],page:'exams',action:'Organizar concurso pré-edital'},
  {id:'editais-grades',title:'Montar a grade e confirmar o edital',category:'Telas do portal',keywords:'componente etapa objetiva discursiva peso quantidade assunto personalizado publicar oficial',intro:'Editais e grades mostra as grades dos seus concursos. Abra a grade para organizar o conteúdo de cada etapa.',steps:[
    'Escolha uma Disciplina do catálogo e use Adicionar componente. A mesma disciplina pode aparecer em mais de uma etapa, com identidades locais distintas.',
    'Em cada componente, informe nome no edital, etapa, grupo de conhecimentos, relevância, peso e quantidade de questões. Deixe a quantidade vazia quando não estiver definida.',
    'Use Adicionar assunto / subassunto e Adicionar assunto para selecionar o conteúdo. Adicionar todos da disciplina inclui todos os assuntos e subassuntos ativos após confirmação; confira se fazem parte da referência.',
    'Disciplinas e assuntos personalizados permitem preparar itens ainda sem associação ao catálogo. Eles não criam uma classificação canônica confirmada para questões.',
    'Durante a preparação, mantenha Pré-edital. Salvar concurso e grade grava a organização sem declarar que existe edital oficial.',
    'Publicar esta grade como edital registra o conteúdo como oficial, com comparação ao histórico. Salve o concurso antes e confira todos os componentes.'
  ],notes:['Alterar o nome de uma disciplina na grade não renomeia a disciplina global do catálogo. Remover um componente não apaga o histórico já salvo do concurso.'],page:'edital',action:'Abrir editais e grades'},
  {id:'troncos',title:'Troncos de carreira',category:'Telas do portal',keywords:'carreira fiscal bancária controle perfil relações disciplinas',intro:'Mostra perfis de carreira, disciplinas relacionadas e concursos da sua conta vinculados ao tronco.',steps:[
    'Busque o tronco desejado e confira as disciplinas relacionadas.',
    'Abra a grade de um concurso listado ou use Criar concurso para iniciar um cadastro com esse tronco.',
    'Use Editar proposta para preparar uma alteração global do tronco no catálogo versionado.'
  ],notes:['Uma relação com o tronco não torna a disciplina obrigatória em todos os concursos da carreira. Alterações globais só entram em vigor após ativação explícita.'],page:'trunks',action:'Abrir troncos de carreira'},
  {id:'disciplinas-assuntos',title:'Disciplinas, assuntos e subassuntos',category:'Telas do portal',keywords:'catálogo alias nome pai identidade desativada subassunto conteúdo',intro:'Consulte os conteúdos reutilizados por grades e questões. A busca considera nomes, aliases e assuntos.',steps:[
    'Busque e expanda uma disciplina para ver escopo, nomes alternativos, troncos relacionados e componentes das suas grades que a utilizam.',
    'Confira o caminho do conteúdo: um subassunto mostra também o nome de seu assunto pai.',
    'Propor nova disciplina, Adicionar assunto na proposta e Propor edição abrem a revisão editorial do catálogo.',
    'Ao renomear, preserve a identidade e o nome anterior como alias. Para mudar o significado ou a filiação de uma entidade, crie uma nova identidade e revise a desativação da anterior.'
  ],notes:['Aliases ajudam a localizar conteúdo; nomes parecidos não confirmam equivalência ou classificação de questões.'],page:'subjects',action:'Abrir disciplinas e assuntos'},
  {id:'banco-questoes',title:'Consultar, editar e converter questões existentes',category:'Telas do portal',keywords:'banco busca pesquisa editar legado conversão gabarito revisão validade',intro:'Use Banco de questões para alterar uma questão já salva no Cogniva, preservando seu ID e seu histórico.',steps:[
    'Carregue as questões ou busque pelo enunciado. O filtro de tipo e Próxima página ajudam a navegar pelo acervo.',
    'Clique em Abrir para revisar enunciado, alternativas, gabarito ou rubrica e classificação principal.',
    'Em registro legado, use Preparar conversão e editar. A conversão prepara um rascunho com o mesmo ID; gabarito ambíguo e classificação precisam de conferência.',
    'Após escolher disciplina e assunto, aplique e confirme a classificação selecionada. Metadados adicionais podem ser inspecionados no contrato completo.',
    'Use Validar edição, confira o contrato final, marque a revisão e confirme a edição.',
    'Se houver conflito, compare com a versão atual do servidor antes de continuar. A edição anterior pode ser preservada em Rascunhos e pendências.'
  ],notes:['Mudanças no conteúdo colocam a validade em revisão. Preparar uma conversão ou validar uma edição ainda não grava a alteração definitiva.'],page:'questions',action:'Abrir banco de questões'},
  {id:'inserir-individual',title:'Inserir uma questão de prova ou de autoria própria',category:'Passo a passo',keywords:'inserir cadastro individual alternativas certo errado discursiva banca ano fonte número explicação origem',intro:'Inserir questões cria novos registros. Para uma questão que já está no acervo, prefira sua edição pelo Banco de questões.',steps:[
    'Escolha Individual, o tipo e a origem. Preencha o enunciado e o gabarito: múltipla escolha exige cinco alternativas distintas; certo/errado usa Certo e Errado.',
    'Para discursiva, informe resposta de referência e critérios da rubrica. No formulário, os critérios somam 100 pontos, o corte é 60 e a confirmação da correção é humana.',
    'Preencha prova/cargo/caderno, instituição, banca, ano, número, nível e fonte. Prova oficial exige identificação, instituição, banca, ano e referência documental.',
    'Na referência, informe URL, título, data de consulta e, se útil, página/localização. Marque a conferência de origem somente após consultar a fonte. Alterar a origem desmarca essa conferência.',
    'Selecione disciplina e assunto ou subassunto e confirme explicitamente a classificação principal. Acrescente a explicação da resolução, se disponível.',
    'Use Validar e revisar questões. Confira conteúdo, gabarito, origem, classificação e validade; marque a revisão e confirme a inserção.',
    'Confira o resultado de cada item. Em envio incerto, use Conferir / reenviar os mesmos comandos.'
  ],notes:['Origem é de onde a questão veio; classificação é o conteúdo cobrado. Conferir origem não confirma validade atual. Novas questões começam com validade desconhecida; inserir não garante elegibilidade em todos os modos de treino.'],page:'import',action:'Abrir inserção de questões'},
  {id:'lote-ia',title:'Inserir em lote e usar assistência de IA',category:'Passo a passo',keywords:'lote JSON JSONL TXT importar arquivo IA Grok gerar estruturar PDF',intro:'Em Inserir questões → Lote / IA, cole material ou abra um arquivo JSON, JSONL ou TXT. O painel não oferece importação direta de PDF para montar grades.',steps:[
    'Prepare de 1 a 50 objetos de questão, até 512 KiB por lote. JSONL contém um objeto por linha; índices de gabarito começam em zero.',
    'Opcionalmente, escolha Estruturar questões de um texto ou Gerar questões autorais a partir do pedido e clique em Preparar com IA.',
    'O botão envia o material à xAI e usa a cota de IA da conta. A resposta é um rascunho editável; não publica questões automaticamente.',
    'Revise o JSON preparado, especialmente alternativas, gabaritos e rubricas. Conteúdo assistido mantém sua proveniência de IA.',
    'Valide, corrija os erros indicados, confira todos os itens e confirme a inserção. Acompanhe o recibo de cada questão.'
  ],notes:['A IA não confirma classificação, origem oficial ou validade por conta própria. O lote não deduplica automaticamente por texto parecido.'],page:'import',action:'Abrir inserção individual e em lote'},
  {id:'revisoes-catalogo',title:'Revisar e ativar o catálogo global',category:'Telas do portal',keywords:'revisões catálogo proposta pronta revisão validar salvar ativar global renomear desativar',intro:'Revisões do catálogo prepara uma nova versão dos troncos, disciplinas e assuntos compartilhados. É diferente de editar a grade de um concurso.',steps:[
    'Inicie uma proposta a partir do catálogo ativo e edite as entidades necessárias.',
    'Valide a proposta. Um rascunho incompleto pode ser salvo como rascunho; para ativar, use Salvar como pronta para revisão após corrigir os problemas.',
    'Informe o motivo da ativação, confira nomes, assuntos, vínculos e situações e marque a confirmação de ativação global.',
    'Use Ativar catálogo global e aguarde a confirmação. Uma proposta apenas salva não muda o catálogo ativo.',
    'Em resposta incerta, confira ou reenvie a mesma ativação. Para novas mudanças após a ativação, inicie outra revisão a partir da base atual.'
  ],notes:['A ativação afeta o catálogo compartilhado. IDs e versões históricas são preservados; desativar conteúdo pode retirar suas questões da seleção atual. Não altera automaticamente as classificações históricas.'],page:'catalog',action:'Abrir revisões do catálogo'},
  {id:'rascunhos',title:'Rascunhos, exportação, restauração e envios pendentes',category:'Telas do portal',keywords:'rascunho pendência pendencias exportar restaurar recuperar backup navegador erro timeout reenviar conflito',intro:'O portal conserva edições neste navegador, separadas por conta. Rascunho local não é o mesmo que conteúdo confirmado no servidor.',steps:[
    'Abra Rascunhos e pendências para continuar concursos, inserções, edições de questões e propostas do catálogo.',
    'Use Exportar todos ou os botões de exportação da tela antes de limpar dados do navegador ou trocar de computador.',
    'Para recuperar, use Restaurar arquivo exportado com um arquivo da mesma conta. A cópia atual é preservada no arquivo local; a restauração não dispara envios.',
    'Em timeout ou conexão perdida após o envio, confira o resultado e reenvie os mesmos comandos. Não inicie outro cadastro para repetir uma operação ainda sem confirmação.',
    'Em conflito de revisão, compare com o servidor. Escolher a versão do servidor preserva a edição substituída no arquivo local.',
    'Se o armazenamento local estiver ilegível ou não puder salvar, siga a orientação de exportação e recuperação exibida antes de continuar.'
  ],notes:['As grades e rascunhos são separados por conta. O portal não garante reabertura offline; login, novas consultas e gravações exigem conexão. Limpar dados do navegador pode apagar rascunhos locais.'],page:'drafts',action:'Abrir rascunhos e pendências'},
  {id:'acesso-sessao',title:'Acesso, sessão de 30 dias e conexão com o Android',category:'Dúvidas frequentes',keywords:'login código acesso sessão 30 dias deslogado entrar sair Chrome navegador cookies Android sincronização conta',intro:'Entre com um código temporário emitido para sua conta administrativa já autorizada. O código vale 10 minutos e tem uso único.',steps:[
    'Após um novo login, a sessão web dura até 30 dias a partir da entrada. Uma sessão criada antes dessa configuração mantém sua expiração original até novo login.',
    'A sessão fica no navegador e perfil usados para entrar. Outro navegador, perfil ou janela anônima não compartilha automaticamente esse login.',
    'Sair encerra a sessão. Limpar cookies ou usar um ambiente que não os preserve exige nova entrada; seus rascunhos locais dependem dos dados mantidos nesse navegador.',
    'Use a mesma conta do Android para administrar seus concursos. Alterações confirmadas usam a mesma base; o aplicativo recebe os dados na sincronização.',
    'Se a sessão expirar, obtenha um novo código com o responsável pelo Cogniva. Seu ID ou nome de usuário sozinho não substitui o código.'
  ],notes:['Um código de acesso não deve ser colocado no nome ou endereço de um atalho. O atalho apenas abre o portal; quem mantém o login é o navegador.'],page:'drafts',action:'Ver rascunhos preservados'}
];

export function searchHelp(query) {
  const normalize = value => value.normalize('NFD').replace(/\p{M}/gu,'').toLocaleLowerCase('pt-BR');
  const words = normalize(query).trim().split(/\s+/).filter(Boolean);
  return helpTopics.filter(topic=>words.every(word=>normalize([topic.title,topic.category,topic.keywords,topic.intro,...topic.steps,...topic.notes].join(' ')).includes(word)));
}
