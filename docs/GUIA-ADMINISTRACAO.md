# Ajuda do portal de administração

Conteúdo compartilhado com a seção Ajuda do portal, disponível no menu lateral e em Ajuda desta tela. Fonte: `web-admin/help.js`. Para atualizar este arquivo, execute `node scripts/export-admin-help.mjs --write`.

## Por onde começar no Cogniva 2.0

O portal gerencia os templates globais de concursos, o catálogo canônico unificado (v2), o acervo de questões, a classificação assistida por IA e a moderação de reportes.

1. Confira os templates globais já publicados em Concursos (CPNU, RFB, BNDES, AFT e novos cadastros) antes de criar outro.
2. Abra a grade do template e organize disciplinas, pesos, quantidades e assuntos do catálogo.
3. Insira ou revise questões, definindo classificação canônica e validade explícita (Atual, Em revisão ou Obsoleta).
4. Acompanhe Classificação IA e Reportes para manter a curadoria do acervo em dia.

No Cogniva 2.0, o painel edita templates globais compartilhados com os alunos. Alunos sem customização recebem atualizações automaticamente; alunos que personalizaram sua grade recebem aviso opcional para adotar a nova versão.

No portal: **Ir à visão geral**.

## Tronco, template global, grade pessoal e catálogo unificado

A preparação conecta o catálogo canônico unificado (v2) aos templates globais de concursos e às grades dos estudantes.

1. Tronco de carreira agrupa uma área (Fiscal, Controle, Jurídica, Policial, Administrativa, Bancária, TI) com ícone e pesos padrão nas relações editoriais.
2. Template global de concurso define cargo, instituição, situação e grade de referência para todos os usuários do aplicativo.
3. No aplicativo Android, o estudante pode seguir o template global ou personalizar sua própria cópia em user_exams sem perder seu histórico.
4. No catálogo unificado, Disciplina → Assunto → Subassunto mantém identidades estáveis em todas as versões.
5. Questões usam hash canônico único JCS SHA-256 (content_revision) e elegibilidade explícita por validade e classificação.

O tronco sugere disciplinas e pesos iniciais, mas cada componente da grade define exatamente os assuntos cobrados na etapa.

No portal: **Explorar disciplinas e assuntos**.

## Visão geral e indicadores operacionais

Reúne os indicadores em tempo real do banco cogniva: templates globais, saúde de validade das questões, itens sem classificação, propostas de IA e reportes abertos.

1. Acompanhe a distribuição de questões atuais, em revisão, obsoletas, sem classificação e discursivas preparatórias.
2. Use os atalhos rápidos para ir direto à fila de Classificação IA ou à moderação de Reportes.
3. Clique em Atualizar indicadores para recarregar as contagens do servidor.

Questões obsoletas e questões discursivas preparatórias permanecem preservadas no acervo, mas não entram nas filas de prática objetiva.

No portal: **Abrir visão geral**.

## Criar e gerenciar templates globais de concursos

Em Concursos, gerencie os templates oficiais e pré-editais servidos para toda a base de alunos.

1. Informe identificador ou crie um novo template com nome completo, nome curto, instituição, cargo e tronco de carreira.
2. Selecione a situação (Previsto, Autorizado, Banca definida, Edital publicado, Prova realizada ou Arquivado).
3. Configure previsão da prova (Sem previsão, Data exata ou Janela estimada), corte histórico e penalidade por erro.
4. Monte os componentes da grade respeitando o limite total de até 500 questões somadas e clique em Salvar concurso e grade.
5. Arquivar template retira-o das recomendações iniciais sem apagar o histórico nem as grades pessoais já criadas.

Alterações confirmadas em um template global atualizam automaticamente estudantes que não customizaram a grade e sinalizam atualização disponível para quem customizou.

No portal: **Abrir concursos**.

## Exemplo: Banco Central sem edital novo

Você pode publicar um template pré-edital baseado na última prova conhecida do cargo, identificando claramente essa referência.

1. Em Concursos, procure o Banco Central. Se já houver um template adequado, abra sua grade; caso contrário, crie o template.
2. Use um nome claro como “Banco Central — Analista — Pré-edital”. Informe instituição, cargo e tronco Bancária.
3. Use Previsto enquanto não houver confirmação oficial e mantenha banca ou data indefinidas se ainda não anunciadas.
4. Adicione os componentes do edital anterior, ajustando etapas, pesos, quantidades e tópicos como Pré-edital.
5. Quando o edital oficial for publicado, revise os componentes e confirme com Publicar esta grade como edital.

O painel não importa PDFs automaticamente; a curadoria dos componentes e assuntos é revisada manualmente antes da publicação.

No portal: **Organizar concurso pré-edital**.

## Montar a grade e publicar edital oficial

Editais e grades permite detalhar os componentes por etapa de cada template global.

1. Escolha uma Disciplina ativa do catálogo e clique em Adicionar componente. A mesma disciplina pode aparecer em mais de uma etapa com identidades locais independentes.
2. Informe nome no edital, etapa, grupo de conhecimentos, relevância, peso e quantidade de questões (ou deixe a quantidade em branco quando indefinida).
3. Adicione assuntos e subassuntos individualmente ou use Adicionar todos da disciplina após conferência.
4. Use Salvar concurso e grade durante a preparação pré-edital.
5. Use Publicar esta grade como edital para registrar oficialmente os tópicos confirmados, novos e arquivados em relação à base anterior.

A soma das quantidades de questões de todos os componentes de uma grade não pode ultrapassar 500 questões.

No portal: **Abrir editais e grades**.

## Troncos de carreira e pesos editoriais

Mostra os troncos do catálogo unificado v2, suas disciplinas relacionadas e os templates globais vinculados.

1. Busque o tronco desejado e confira o ícone editorial, as disciplinas vinculadas e os templates associados.
2. Use Criar concurso para iniciar um novo template global naquele tronco ou Editar proposta para revisar o catálogo.
3. Nas disciplinas do catálogo, ajuste o papel (Conteúdo central ou Conteúdo relacionado) e o peso editorial padrão (default_weight) em cada tronco.

Alterações no catálogo global seguem fluxo próprio de validação e ativação versionada em Revisões do catálogo.

No portal: **Abrir troncos de carreira**.

## Disciplinas, assuntos e subassuntos

Consulte as identidades compartilhadas entre templates e questões no catálogo unificado v2.

1. Busque e expanda uma disciplina para inspecionar escopo, aliases, vínculos com troncos e uso nos templates globais.
2. Verifique a hierarquia completa: subassuntos exibem o caminho Assunto Pai → Subassunto.
3. Ao renomear uma entidade, o nome anterior é preservado como alias sem colisão acentual ou de caixa.

Nunca reutilize um ID estável para representar outro conceito; crie uma nova entidade e desative a anterior se necessário.

No portal: **Abrir disciplinas e assuntos**.

## Consultar, filtrar e revisar questões e validade

Use Banco de questões para localizar itens por texto, tipo, validade ou estado de classificação e editar seu contrato v2.

1. Combine busca textual com os filtros de Tipo, Validade (Atual, Em revisão, Obsoleta, Desconhecida) e Classificação (Confirmada, Sem classificação).
2. Abra a questão para revisar enunciado, alternativas, gabarito ou rubrica, comentário da resolução (explanation), nível e ano da prova.
3. Na seção Validade do conteúdo, escolha explicitamente entre Atual, Em revisão, Obsoleta ou Desconhecida, informando motivos normativos ou factuais quando aplicável.
4. Se você alterou o enunciado ou as alternativas e já conferiu que a questão segue válida, marque Reconfirmar validade para o conteúdo editado antes de validar.
5. Clique em Validar edição, confira o contrato JCS calculado na prévia e confirme a gravação.

Explicação da resolução, nível e ano da prova são preservados nas edições v2. Se o conteúdo mudar sem reconfirmação explícita de validade, a questão entra automaticamente em revisão (UNDER_REVIEW).

No portal: **Abrir banco de questões**.

## Inserir questões objetivas e discursivas preparatórias

Inserir questões cadastra novos itens v2 individuais ou em lote com rastreabilidade completa de origem.

1. Escolha Individual, selecione o tipo (Múltipla escolha, Certo / Errado ou Discursiva) e a origem.
2. Para questões discursivas (OPEN_ENDED), informe a resposta de referência e os critérios da rubrica somando exatamente 100 pontos.
3. Registre prova, instituição, banca, ano, nível e fonte documental; marque a conferência de origem apenas quando tiver verificado a fonte.
4. Classifique disciplina e assunto/subassunto e adicione a explicação da resolução se disponível.
5. Valide o contrato, revise cada item na prévia e confirme a inserção.

Aviso preparatório: questões discursivas são validadas, versionadas com hash JCS e preservadas no acervo preparatório, mas permanecem fora das filas de prática objetiva até a abertura do módulo discursivo.

No portal: **Abrir inserção de questões**.

## Inserir em lote e usar assistência de IA (xAI)

Em Inserir questões → Lote / IA, estruture material bruto ou gere rascunhos autorais respeitando os tetos diários de IA.

1. Envie de 1 a 50 questões em JSON/JSONL (até 512 KiB) ou forneça texto-base para o assistente xAI configurado no servidor.
2. Clique em Preparar com IA para gerar rascunhos editáveis sem publicação automática.
3. Edite e confira gabaritos, alternativas, rubricas e metadados antes de validar e confirmar o lote.

Falhas de rede ou respostas inválidas do provedor xAI estornam automaticamente a cota reservada. A IA nunca confirma validade nem classificação sozinha.

No portal: **Abrir inserção individual e em lote**.

## Classificação assistida por IA e curadoria humana

Classificação IA permite gerar propostas de vínculo canônico para questões sem classificação e decidir cada sugestão com auditoria.

1. Abra Classificação IA para ver quantas questões ativas estão sem classificação e listar as propostas pendentes.
2. Use Gerar propostas com IA para solicitar sugestões do modelo xAI para um lote controlado de questões ainda não classificadas.
3. Confira o enunciado, a disciplina de origem, o tópico sugerido, a justificativa e a confiança informada.
4. Informe o motivo de revisão (mínimo de 10 caracteres) e escolha Aceitar, Editar e aceitar (ajustando disciplina e tópico), Adiar ou Rejeitar.

Propostas pendentes jamais alteram a seleção de estudo dos alunos antes da confirmação humana explícita.

No portal: **Abrir classificação IA**.

## Moderação de reportes de questões

Reportes centraliza as sinalizações enviadas pelos estudantes sem bloquear globalmente a questão para os demais alunos.

1. Filtre os reportes por Abertos, Resolvidos ou Todos para priorizar a fila de moderação.
2. Confira as categorias apontadas pelo estudante, o trecho da questão e o estado atual de validade.
3. Clique em Abrir questão no editor para corrigir gabarito, atualizar explicação ou marcar a questão como Obsoleta / Em revisão.
4. Registre a nota de resolução e conclua o atendimento individual ou de todos os reportes abertos daquela questão.

Enquanto um reporte está aberto, a questão fica oculta apenas para o autor daquele reporte, evitando negação de serviço no acervo global.

No portal: **Abrir reportes**.

## Revisar e ativar o catálogo global unificado

Revisões do catálogo prepara e ativa novas versões do catálogo compartilhado sem reiniciar o backend.

1. Inicie uma proposta a partir do catálogo ativo (v2+) e edite troncos, disciplinas ou assuntos.
2. Valide a proposta e use Salvar como pronta para revisão quando não houver pendências de schema ou identidade.
3. Descreva o motivo da ativação, marque a confirmação editorial e clique em Ativar catálogo global.

A ativação invalida imediatamente o cache em memória do servidor para que API, painel e sincronização Android passem a usar a nova versão.

No portal: **Abrir revisões do catálogo**.

## Rascunhos locais, exportação e recuperação

O portal conserva edições no armazenamento local do navegador separadas por conta administrativa.

1. Use Rascunhos e pendências para retomar templates, inserções, edições de questões e propostas do catálogo.
2. Exporte cópias JSON regularmente antes de limpar dados do navegador ou trocar de estação de trabalho.
3. Em caso de conflito de revisão com o servidor, compare os documentos e escolha qual base manter preservando o histórico local.

Erros de validação (HTTP 4xx) desbloqueiam imediatamente o rascunho para correção; apenas falhas incertas de rede mantêm o mesmo comando pronto para conferência idempotente.

No portal: **Abrir rascunhos e pendências**.

## Acesso com e-mail e senha, sessão de 30 dias e promoção de admin

No Cogniva 2.0, a autenticação administrativa é 100% nativa por e-mail e senha (Argon2id), sem códigos temporários nem vínculo com Telegram.

1. Entre com seu e-mail administrativo e senha na aba Entrar. A sessão web dura até 30 dias em cookie HttpOnly.
2. Para criar a primeira conta administrativa pela web, configure o e-mail em ADMIN_EMAILS no servidor e utilize a aba Primeiro acesso.
3. Para promover uma conta já existente ao papel administrativo, execute cogniva-api --grant-admin email@dominio.com no servidor.
4. Ao clicar em Sair, a sessão é revogada imediatamente na coleção sessions do banco de dados.

A mesma conta de e-mail pode ser usada no aplicativo Android e no painel administrativo, mantendo rascunhos locais isolados no navegador.

No portal: **Ver rascunhos preservados**.
