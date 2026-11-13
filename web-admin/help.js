// Product guidance: keep actions and field names aligned with the portal.
export const helpTopics = [
  {id:'primeiros-passos',title:'Por onde começar no Cogniva 2.0',category:'Começar',keywords:'início tutorial portal administração ajuda templates catálogo',intro:'O portal gerencia os templates globais de concursos, o catálogo canônico unificado (v2), o acervo de questões, a classificação assistida por IA e a moderação de reportes.',steps:[
    'Confira os templates globais já publicados em Concursos (CPNU, RFB, BNDES, AFT e novos cadastros) antes de criar outro.',
    'Abra a grade do template e organize disciplinas, pesos, quantidades e assuntos do catálogo.',
    'Insira ou revise questões, definindo classificação canônica e validade explícita (Atual, Em revisão ou Obsoleta).',
    'Acompanhe Classificação IA e Reportes para manter a curadoria do acervo em dia.'
  ],notes:['No Cogniva 2.0, o painel edita templates globais compartilhados com os alunos. Alunos sem customização recebem atualizações automaticamente; alunos que personalizaram sua grade recebem aviso opcional para adotar a nova versão.'],page:'overview',action:'Ir à visão geral'},
  {id:'estrutura',title:'Tronco, template global, grade pessoal e catálogo unificado',category:'Entender a estrutura',keywords:'hierarquia relação componente disciplinas assuntos subassuntos questões templates user_exams',intro:'A preparação conecta o catálogo canônico unificado (v2) aos templates globais de concursos e às grades dos estudantes.',steps:[
    'Tronco de carreira agrupa uma área (Fiscal, Controle, Jurídica, Policial, Administrativa, Bancária, TI) com ícone e pesos padrão nas relações editoriais.',
    'Template global de concurso define cargo, instituição, situação e grade de referência para todos os usuários do aplicativo.',
    'No aplicativo Android, o estudante pode seguir o template global ou personalizar sua própria cópia em user_exams sem perder seu histórico.',
    'No catálogo unificado, Disciplina → Assunto → Subassunto mantém identidades estáveis em todas as versões.',
    'Questões usam hash canônico único JCS SHA-256 (content_revision) e elegibilidade explícita por validade e classificação.'
  ],notes:['O tronco sugere disciplinas e pesos iniciais, mas cada componente da grade define exatamente os assuntos cobrados na etapa.'],page:'subjects',action:'Explorar disciplinas e assuntos'},
  {id:'visao-geral',title:'Visão geral e indicadores operacionais',category:'Telas do portal',keywords:'resumo contagem atualizar acervo dashboard reportes propostas validade',intro:'Reúne os indicadores em tempo real do banco cogniva: templates globais, saúde de validade das questões, itens sem classificação, propostas de IA e reportes abertos.',steps:[
    'Acompanhe a distribuição de questões atuais, em revisão, obsoletas, sem classificação e discursivas preparatórias.',
    'Use os atalhos rápidos para ir direto à fila de Classificação IA ou à moderação de Reportes.',
    'Clique em Atualizar indicadores para recarregar as contagens do servidor.'
  ],notes:['Questões obsoletas e questões discursivas preparatórias permanecem preservadas no acervo, mas não entram nas filas de prática objetiva.'],page:'overview',action:'Abrir visão geral'},
  {id:'concursos',title:'Criar e gerenciar templates globais de concursos',category:'Telas do portal',keywords:'cadastro cargo instituição banca situação data escada prioridade arquivar template global',intro:'Em Concursos, gerencie os templates oficiais e pré-editais servidos para toda a base de alunos.',steps:[
    'Informe identificador ou crie um novo template com nome completo, nome curto, instituição, cargo e tronco de carreira.',
    'Selecione a situação (Previsto, Autorizado, Banca definida, Edital publicado, Prova realizada ou Arquivado).',
    'Configure previsão da prova (Sem previsão, Data exata ou Janela estimada), corte histórico e penalidade por erro.',
    'Monte os componentes da grade respeitando o limite total de até 500 questões somadas e clique em Salvar concurso e grade.',
    'Arquivar template retira-o das recomendações iniciais sem apagar o histórico nem as grades pessoais já criadas.'
  ],notes:['Alterações confirmadas em um template global atualizam automaticamente estudantes que não customizaram a grade e sinalizam atualização disponível para quem customizou.'],page:'exams',action:'Abrir concursos'},
  {id:'pre-edital-banco-central',title:'Exemplo: Banco Central sem edital novo',category:'Passo a passo',keywords:'pré-edital pre edital último anterior Banco Central BCB BACEN previsto ainda não saiu',intro:'Você pode publicar um template pré-edital baseado na última prova conhecida do cargo, identificando claramente essa referência.',steps:[
    'Em Concursos, procure o Banco Central. Se já houver um template adequado, abra sua grade; caso contrário, crie o template.',
    'Use um nome claro como “Banco Central — Analista — Pré-edital”. Informe instituição, cargo e tronco Bancária.',
    'Use Previsto enquanto não houver confirmação oficial e mantenha banca ou data indefinidas se ainda não anunciadas.',
    'Adicione os componentes do edital anterior, ajustando etapas, pesos, quantidades e tópicos como Pré-edital.',
    'Quando o edital oficial for publicado, revise os componentes e confirme com Publicar esta grade como edital.'
  ],notes:['O painel não importa PDFs automaticamente; a curadoria dos componentes e assuntos é revisada manualmente antes da publicação.'],page:'exams',action:'Organizar concurso pré-edital'},
  {id:'editais-grades',title:'Montar a grade e publicar edital oficial',category:'Telas do portal',keywords:'componente etapa objetiva discursiva peso quantidade assunto personalizado publicar oficial',intro:'Editais e grades permite detalhar os componentes por etapa de cada template global.',steps:[
    'Escolha uma Disciplina ativa do catálogo e clique em Adicionar componente. A mesma disciplina pode aparecer em mais de uma etapa com identidades locais independentes.',
    'Informe nome no edital, etapa, grupo de conhecimentos, relevância, peso e quantidade de questões (ou deixe a quantidade em branco quando indefinida).',
    'Adicione assuntos e subassuntos individualmente ou use Adicionar todos da disciplina após conferência.',
    'Use Salvar concurso e grade durante a preparação pré-edital.',
    'Use Publicar esta grade como edital para registrar oficialmente os tópicos confirmados, novos e arquivados em relação à base anterior.'
  ],notes:['A soma das quantidades de questões de todos os componentes de uma grade não pode ultrapassar 500 questões.'],page:'edital',action:'Abrir editais e grades'},
  {id:'troncos',title:'Troncos de carreira e pesos editoriais',category:'Telas do portal',keywords:'carreira fiscal bancária controle perfil relações disciplinas ícone peso',intro:'Mostra os troncos do catálogo unificado v2, suas disciplinas relacionadas e os templates globais vinculados.',steps:[
    'Busque o tronco desejado e confira o ícone editorial, as disciplinas vinculadas e os templates associados.',
    'Use Criar concurso para iniciar um novo template global naquele tronco ou Editar proposta para revisar o catálogo.',
    'Nas disciplinas do catálogo, ajuste o papel (Conteúdo central ou Conteúdo relacionado) e o peso editorial padrão (default_weight) em cada tronco.'
  ],notes:['Alterações no catálogo global seguem fluxo próprio de validação e ativação versionada em Revisões do catálogo.'],page:'trunks',action:'Abrir troncos de carreira'},
  {id:'disciplinas-assuntos',title:'Disciplinas, assuntos e subassuntos',category:'Telas do portal',keywords:'catálogo alias nome pai identidade desativada subassunto conteúdo',intro:'Consulte as identidades compartilhadas entre templates e questões no catálogo unificado v2.',steps:[
    'Busque e expanda uma disciplina para inspecionar escopo, aliases, vínculos com troncos e uso nos templates globais.',
    'Verifique a hierarquia completa: subassuntos exibem o caminho Assunto Pai → Subassunto.',
    'Ao renomear uma entidade, o nome anterior é preservado como alias sem colisão acentual ou de caixa.'
  ],notes:['Nunca reutilize um ID estável para representar outro conceito; crie uma nova entidade e desative a anterior se necessário.'],page:'subjects',action:'Abrir disciplinas e assuntos'},
  {id:'banco-questoes',title:'Consultar, filtrar e revisar questões e validade',category:'Telas do portal',keywords:'banco busca pesquisa editar gabarito revisão validade atual obsoleta explicação ano nível',intro:'Use Banco de questões para localizar itens por texto, tipo, validade ou estado de classificação e editar seu contrato v2.',steps:[
    'Combine busca textual com os filtros de Tipo, Validade (Atual, Em revisão, Obsoleta, Desconhecida) e Classificação (Confirmada, Sem classificação).',
    'Abra a questão para revisar enunciado, alternativas, gabarito ou rubrica, comentário da resolução (explanation), nível e ano da prova.',
    'Na seção Validade do conteúdo, escolha explicitamente entre Atual, Em revisão, Obsoleta ou Desconhecida, informando motivos normativos ou factuais quando aplicável.',
    'Se você alterou o enunciado ou as alternativas e já conferiu que a questão segue válida, marque Reconfirmar validade para o conteúdo editado antes de validar.',
    'Clique em Validar edição, confira o contrato JCS calculado na prévia e confirme a gravação.'
  ],notes:['Explicação da resolução, nível e ano da prova são preservados nas edições v2. Se o conteúdo mudar sem reconfirmação explícita de validade, a questão entra automaticamente em revisão (UNDER_REVIEW).'],page:'questions',action:'Abrir banco de questões'},
  {id:'inserir-individual',title:'Inserir questões objetivas e discursivas preparatórias',category:'Passo a passo',keywords:'inserir cadastro individual alternativas certo errado discursiva rubrica banca ano fonte número explicação origem',intro:'Inserir questões cadastra novos itens v2 individuais ou em lote com rastreabilidade completa de origem.',steps:[
    'Escolha Individual, selecione o tipo (Múltipla escolha, Certo / Errado ou Discursiva) e a origem.',
    'Para questões discursivas (OPEN_ENDED), informe a resposta de referência e os critérios da rubrica somando exatamente 100 pontos.',
    'Registre prova, instituição, banca, ano, nível e fonte documental; marque a conferência de origem apenas quando tiver verificado a fonte.',
    'Classifique disciplina e assunto/subassunto e adicione a explicação da resolução se disponível.',
    'Valide o contrato, revise cada item na prévia e confirme a inserção.'
  ],notes:['Aviso preparatório: questões discursivas são validadas, versionadas com hash JCS e preservadas no acervo preparatório, mas permanecem fora das filas de prática objetiva até a abertura do módulo discursivo.'],page:'import',action:'Abrir inserção de questões'},
  {id:'lote-ia',title:'Inserir em lote e usar assistência de IA (xAI)',category:'Passo a passo',keywords:'lote JSON JSONL TXT importar arquivo IA xAI gerar estruturar cota',intro:'Em Inserir questões → Lote / IA, estruture material bruto ou gere rascunhos autorais respeitando os tetos diários de IA.',steps:[
    'Envie de 1 a 50 questões em JSON/JSONL (até 512 KiB) ou forneça texto-base para o assistente xAI configurado no servidor.',
    'Clique em Preparar com IA para gerar rascunhos editáveis sem publicação automática.',
    'Edite e confira gabaritos, alternativas, rubricas e metadados antes de validar e confirmar o lote.'
  ],notes:['Falhas de rede ou respostas inválidas do provedor xAI estornam automaticamente a cota reservada. A IA nunca confirma validade nem classificação sozinha.'],page:'import',action:'Abrir inserção individual e em lote'},
  {id:'classificacao-ia',title:'Classificação assistida por IA e curadoria humana',category:'Telas do portal',keywords:'classificação IA propostas pendentes aceitar editar rejeitar adiar não classificadas',intro:'Classificação IA permite gerar propostas de vínculo canônico para questões sem classificação e decidir cada sugestão com auditoria.',steps:[
    'Abra Classificação IA para ver quantas questões ativas estão sem classificação e listar as propostas pendentes.',
    'Use Gerar propostas com IA para solicitar sugestões do modelo xAI para um lote controlado de questões ainda não classificadas.',
    'Confira o enunciado, a disciplina de origem, o tópico sugerido, a justificativa e a confiança informada.',
    'Informe o motivo de revisão (mínimo de 10 caracteres) e escolha Aceitar, Editar e aceitar (ajustando disciplina e tópico), Adiar ou Rejeitar.'
  ],notes:['Propostas pendentes jamais alteram a seleção de estudo dos alunos antes da confirmação humana explícita.'],page:'classification',action:'Abrir classificação IA'},
  {id:'reportes-moderacao',title:'Moderação de reportes de questões',category:'Telas do portal',keywords:'reportes moderação erro gabarito desatualizada resolver alunos',intro:'Reportes centraliza as sinalizações enviadas pelos estudantes sem bloquear globalmente a questão para os demais alunos.',steps:[
    'Filtre os reportes por Abertos, Resolvidos ou Todos para priorizar a fila de moderação.',
    'Confira as categorias apontadas pelo estudante, o trecho da questão e o estado atual de validade.',
    'Clique em Abrir questão no editor para corrigir gabarito, atualizar explicação ou marcar a questão como Obsoleta / Em revisão.',
    'Registre a nota de resolução e conclua o atendimento individual ou de todos os reportes abertos daquela questão.'
  ],notes:['Enquanto um reporte está aberto, a questão fica oculta apenas para o autor daquele reporte, evitando negação de serviço no acervo global.'],page:'reports',action:'Abrir reportes'},
  {id:'revisoes-catalogo',title:'Revisar e ativar o catálogo global unificado',category:'Telas do portal',keywords:'revisões catálogo proposta pronta revisão validar salvar ativar global renomear desativar',intro:'Revisões do catálogo prepara e ativa novas versões do catálogo compartilhado sem reiniciar o backend.',steps:[
    'Inicie uma proposta a partir do catálogo ativo (v2+) e edite troncos, disciplinas ou assuntos.',
    'Valide a proposta e use Salvar como pronta para revisão quando não houver pendências de schema ou identidade.',
    'Descreva o motivo da ativação, marque a confirmação editorial e clique em Ativar catálogo global.'
  ],notes:['A ativação invalida imediatamente o cache em memória do servidor para que API, painel e sincronização Android passem a usar a nova versão.'],page:'catalog',action:'Abrir revisões do catálogo'},
  {id:'rascunhos',title:'Rascunhos locais, exportação e recuperação',category:'Telas do portal',keywords:'rascunho pendência pendencias exportar restaurar recuperar backup navegador erro timeout reenviar conflito',intro:'O portal conserva edições no armazenamento local do navegador separadas por conta administrativa.',steps:[
    'Use Rascunhos e pendências para retomar templates, inserções, edições de questões e propostas do catálogo.',
    'Exporte cópias JSON regularmente antes de limpar dados do navegador ou trocar de estação de trabalho.',
    'Em caso de conflito de revisão com o servidor, compare os documentos e escolha qual base manter preservando o histórico local.'
  ],notes:['Erros de validação (HTTP 4xx) desbloqueiam imediatamente o rascunho para correção; apenas falhas incertas de rede mantêm o mesmo comando pronto para conferência idempotente.'],page:'drafts',action:'Abrir rascunhos e pendências'},
  {id:'acesso-sessao',title:'Acesso com e-mail e senha, sessão de 30 dias e promoção de admin',category:'Dúvidas frequentes',keywords:'login email e-mail senha sessão 30 dias deslogado entrar sair primeiro acesso grant-admin ADMIN_EMAILS cookies',intro:'No Cogniva 2.0, a autenticação administrativa é 100% nativa por e-mail e senha (Argon2id), sem códigos temporários nem vínculo com Telegram.',steps:[
    'Entre com seu e-mail administrativo e senha na aba Entrar. A sessão web dura até 30 dias em cookie HttpOnly.',
    'Para criar a primeira conta administrativa pela web, configure o e-mail em ADMIN_EMAILS no servidor e utilize a aba Primeiro acesso.',
    'Para promover uma conta já existente ao papel administrativo, execute cogniva-api --grant-admin email@dominio.com no servidor.',
    'Ao clicar em Sair, a sessão é revogada imediatamente na coleção sessions do banco de dados.'
  ],notes:['A mesma conta de e-mail pode ser usada no aplicativo Android e no painel administrativo, mantendo rascunhos locais isolados no navegador.'],page:'drafts',action:'Ver rascunhos preservados'}
];

export function searchHelp(query) {
  const normalize = value => value.normalize('NFD').replace(/\p{M}/gu,'').toLocaleLowerCase('pt-BR');
  const words = normalize(query).trim().split(/\s+/).filter(Boolean);
  return helpTopics.filter(topic=>words.every(word=>normalize([topic.title,topic.category,topic.keywords,topic.intro,...topic.steps,...topic.notes].join(' ')).includes(word)));
}
