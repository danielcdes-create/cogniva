"""Generate the source-linked audit report and validate every code reference."""
from pathlib import Path
import json
import re
import xml.etree.ElementTree as ET
from collections import Counter

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[2]
K = 'android-app/app/src/main/java/br/com/concursoprep/app/'
B = 'backend-rust/src/'
R = K + 'data/repository/ConcursoRepository.kt'
V = K + 'ui/MainViewModel.kt'
Q = K + 'ui/screens/PracticeAndQuestionScreen.kt'
E = K + 'domain/exam/ExamEngine.kt'
findings = []

def ref(path, needle):
    source = OUT / 'source-snapshot' / path
    if not source.exists(): source = ROOT / path
    lines = source.read_text(encoding='utf-8-sig').splitlines()
    hits = [i + 1 for i, line in enumerate(lines) if needle in line]
    if not hits:
        raise ValueError(f'Missing evidence: {path}: {needle}')
    line = hits[0]
    target = source.as_posix()
    return f'[{path}:{line}]({target}:{line})'

def add(number, priority, title, evidence, effect, repair, references, phase, proof='Inspeção de código'):
    findings.append(dict(id=f'C{number:02}', priority=priority, title=title, evidence=evidence,
                         effect=effect, repair=repair, refs=[ref(*r) for r in references], phase=phase, proof=proof))

add(1,'P0','Identidade declarada pelo cliente substitui autenticação e autorização',
    'O vínculo Telegram aceita user_id/username sem desafio que comprove posse. Há emissão de JWT, mas não há verificação do token no router nem envio de Authorization pelo cliente. A manutenção administrativa também é registrada sem proteção.',
    'Conhecer um identificador permite solicitar operações como outra pessoa. Uma chamada sem token sobrescreveu o concurso CPNU no router real da cópia sintética. A vulnerabilidade está confirmada no checkout; não foram executadas operações de escrita contra a produção.',
    'Exigir prova de identidade no vínculo, validar sessão no servidor, derivar o usuário da sessão e proteger funções administrativas. Aceite: identidade ausente/inválida resulta em 401; falta de privilégio em 403; nenhum dado de outro usuário é acessível.',
    [(B+'routes/auth.rs','let mut resolved_user_id'),(B+'main.rs','/api/v1/admin/run-daily-maintenance'),(K+'ConcursoApp.kt','val okHttpClient')],1,'Router sintético: POST sem token retorna 200 e sobrescreve CPNU')
add(2,'P0','A API confirma tentativas que não foram persistidas',
    'handle_submit_batch descarta o Result de replace_one e acrescenta o item em results. No Android, qualquer resposta HTTP convertida em SubmitBatchResponseDto leva à remoção de todos os itens enviados, independentemente dos resultados individuais.',
    'O servidor pode responder processed_count=1 após rejeição da gravação. Com isso, a única cópia local pode ser apagada. O protocolo não distingue recebimento, persistência e aplicação da projeção.',
    'Emitir ACK por attempt_id somente após persistência durável; validar no cliente quais eventos foram confirmados e remover apenas esses eventos. Aceite: rejeição de gravação, ACK vazio ou parcial preserva os itens ainda não confirmados.',
    [(B+'routes/quiz.rs','// Upsert no MongoDB'),(R,'dao.deleteSyncedAttempts')],2,'Mongo sintético rejeita update; handler retorna 200/processed_count=1; probe Android para ACK zero')
add(3,'P1','Reenvio de lote e sincronizações concorrentes duplicam histórico',
    'session_uuid é armazenado, sem deduplicação no servidor. Cada item já é gravado antes de validar/processar o próximo. O cliente permite sync por resposta, pelo botão e pelo Worker, sem exclusão mútua. O Worker recebe retorno normal mesmo quando o repositório capturou uma falha de rede.',
    'A perda do ACK duplica uma tentativa já gravada. Um lote [válido, inválido] retorna 400 após persistir o primeiro; repetir o lote duplica esse primeiro item. O Worker marca sucesso com pendências ainda não transmitidas, adiando a próxima tentativa para a periodicidade normal.',
    'Identificador imutável por tentativa, restrição única por usuário/evento, ACK repetível e processamento incremental seguro; coordenar a fila com um único sincronizador. Devolver resultado de sincronização que diferencie pendência, falha transitória e sucesso.',
    [(B+'routes/quiz.rs','for item in &req.attempts'),(R,'suspend fun syncPendingAttempts'),(K+'data/sync/OfflineSyncWorker.kt','Result.success()')],2,'Dois replays geraram duas sessões; dois lotes inválidos geraram duas gravações parciais; probe concorrente Android')
add(4,'P1','Correção e validade da resposta são confiadas ao cliente',
    'O servidor aceita is_correct, chosen_text, confiança, duração e IDs canônicos do payload. chosen_index não é confrontado com o gabarito. A ausência da questão resulta em disciplina Geral e não interrompe a criação do histórico.',
    'A alternativa errada enviada com is_correct=true tornou-se acerto persistido na reprodução. Clientes antigos, manipulados ou com cache incorreto podem contaminar SRS, pontos, domínio e analytics. Também é possível criar referência histórica sem questão correspondente.',
    'Validar questão/revisão, índice, limites e relação taxonômica; calcular o resultado no servidor a partir de uma versão conhecida da questão. Preservar a versão respondida para lidar com correções futuras do banco.',
    [(B+'routes/quiz.rs','pub struct AnswerAttemptItem'),(B+'routes/quiz.rs','unwrap_or_else(|| "Geral"'),(B+'routes/quiz.rs','statuses.push(item.is_correct)')],2,'Gabarito B, escolha A e is_correct=true produziram dois acertos em dois replays')
add(5,'P1','Gabarito legado textual não é convertido e o Android presume alternativa A',
    'QuestionDocument aceita correct_answers, mas to_dto copia apenas correct_answer_indices. Tela, ViewModel e resultado usam firstOrNull() ?: 0. Se houver vários índices corretos, apenas o primeiro é considerado.',
    'Uma questão legada com answers=[errada, correta] e correct_answers=[correta] produz índices vazios e é corrigida como A no Android. A quantidade de documentos reais afetados não foi medida, pois o Atlas e o bot de origem não foram consultados.',
    'Normalizar e validar o gabarito na fronteira do legado; rejeitar ou encaminhar ambiguidades para revisão, sem escolher A silenciosamente. Definir explicitamente o suporte a respostas múltiplas.',
    [(B+'models/question.rs','correct_answer_indices: self.correct_answer_indices.clone()'),(V,'val correctIndex = question.correctAnswerIndices.firstOrNull() ?: 0'),(Q,'val correctIndex = q.correctAnswerIndices.firstOrNull() ?: 0')],2,'Fixture BSON legada reproduziu perda do índice correto')
add(6,'P1','Usuário padrão compartilhado e fila sem dono permitem mistura de contas',
    'Backend, ViewModel e Worker usam 1001 como fallback. PendingAttemptEntity e o domínio canônico local não têm user_id. O Worker envia toda a fila com o usuário atual das preferências; onboarding e ajustes podem trocar esse usuário.',
    'Pular o onboarding ou informar um username não localizado pode convergir para a mesma identidade 1001. Respostas pendentes de uma conta podem ser atribuídas à conta escolhida depois. A fila não carrega a identidade do momento da resolução.',
    'Separar identidade local ainda não vinculada de uma conta do servidor; vincular cada evento e projeção local ao proprietário. Tratar troca/vínculo de conta como transição explícita e preservar as pendências do dono original.',
    [(B+'routes/auth.rs','resolved_user_id = 1001'),(K+'data/local/AppDatabase.kt','data class PendingAttemptEntity'),(K+'data/sync/OfflineSyncWorker.kt','prefs.getLong("user_id", 1001L)'),(V,'fun skipOnboarding')],1)
add(7,'P1','Substituição integral do documento ameaça compatibilidade histórica e concorrência',
    'O histórico BSON é convertido em UserActivityDocument e regravado integralmente com replace_one. Campos desconhecidos pelo struct desaparecem. A leitura seguida de substituição também não possui versão esperada nem operação atômica de acréscimo.',
    'A fixture comprovou descarte de um campo legado extra. Dois escritores podem ler o mesmo estado e substituir um ao outro, perdendo uma tentativa. O bot original, amostras reais e seus contratos não estão no repositório: a alegação de compatibilidade total permanece sem demonstração.',
    'Preservar campos legados com atualizações aditivas ou um log de eventos separado, usar controle de concorrência e estabelecer fixtures reais anonimizadas. Aceite: campos desconhecidos sobrevivem e dois eventos concorrentes aparecem uma única vez cada.',
    [(B+'models/activity.rs','pub struct UserActivityDocument'),(B+'routes/quiz.rs','.replace_one(')],2,'Roundtrip BSON tipado descartou legacy_extra; perda concorrente identificada por inspeção')
add(8,'P1','Data de sincronização substitui a data de estudo e os dias usam fusos diferentes',
    'A fila registra createdAtEpochMs, mas AnswerAttemptPayload não transmite esse instante. A API usa Utc::now() como data da sessão. Hoje e heatmap no backend agrupam em UTC; o fallback Android usa o dia no fuso local.',
    'Uma sessão feita offline ontem e sincronizada hoje passa a contar como estudo de hoje. No Brasil, respostas próximas das 21h podem cair no dia seguinte no backend. Isso altera metas, heatmap, revisão e cálculo de intervalos.',
    'Transmitir occurred_at com offset e receber received_at separadamente; definir fuso de estudo por usuário e aplicar a mesma regra em agregações, SRS e app. Validar relógio fora da faixa sem reescrever silenciosamente o evento.',
    [(K+'data/local/AppDatabase.kt','val createdAtEpochMs'),(B+'routes/quiz.rs','let now = Utc::now()'),(B+'routes/home.rs','.with_ymd_and_hms'),(B+'routes/analytics.rs','"$dateToString"')],2)
add(9,'P1','Concursos são confirmados em memória e não são reidratados do MongoDB',
    'AppState inicia ExamMemory::seeded(). Os endpoints leem e alteram esse mapa. try_persist_exam ignora falha de escrita e tem limite de 300 ms. Não há leitura de user_exams no bootstrap para reconstruir o mapa.',
    'A criação retorna 200 com Mongo indisponível; um novo AppState perde o concurso. Mesmo uma escrita que tenha sido persistida não é recuperada pelo caminho de inicialização atual. Reinício, deploy e múltiplas réplicas produzem estados diferentes.',
    'Fazer da persistência durável a autoridade, propagar falhas e reidratar corretamente. Aceite: criar, encerrar processo, reiniciar e ler preserva o mesmo concurso; instâncias diferentes observam a mesma revisão.',
    [(B+'main.rs','exams: Mutex::new(ExamMemory::seeded())'),(B+'routes/exams.rs','async fn try_persist_exam'),(B+'routes/exams.rs','Duration::from_millis(300)')],3,'Banco indisponível -> 200; recriação de AppState elimina o concurso criado')
add(10,'P1','Concursos e prioridades formam um mapa global sem isolamento por usuário',
    'create_exam preenche owner_user_id ausente com local. list_exams retorna todo o mapa, e update/publish localizam apenas pelo ID. Analytics e sugestão usam esses mesmos concursos globais.',
    'Um concurso particular pode alterar escada, prontidão ou sugestão de outro usuário, mesmo depois de acrescentar autenticação ao router se os filtros de propriedade continuarem ausentes. IDs de templates podem ser sobrescritos por POST.',
    'Separar templates oficiais de instâncias do usuário; filtrar leitura e mutação pelo proprietário autenticado e impedir edição indevida do catálogo. Aceite: usuários A e B não alteram nem projetam a escada um do outro.',
    [(B+'routes/exams.rs','owner_user_id = Some("local"'),(B+'routes/exams.rs','guard.exams.values().cloned().collect()'),(B+'routes/analytics.rs','guard.exams.values().cloned().collect')],1,'Sobrescrita de template sem token confirmada; vazamento entre proprietários constatado pelo fluxo global')
add(11,'P1','Sincronização de concursos não possui caminho de retorno ao Android',
    'O serviço Retrofit possui createExam/updateExam, mas não GET exams, GET catalog/trunks ou publish-edital. loadStoredExams lê apenas Room; o seed é compilado no aplicativo. syncPendingExams ignora o corpo retornado e marca a linha como SYNCED.',
    'O Android não restaura concursos cadastrados em outro aparelho, não recebe alteração de catálogo ou publicação remota e não reconcilia estado normalizado pelo servidor. A sincronização atual é envio de parte das entidades, não convergência bidirecional.',
    'Implementar contrato de leitura/revisão, pull inicial e incremental, merge com mudanças locais pendentes e tratamento explícito de conflitos. Aceite: criar/editar no aparelho A chega ao B e mudanças remotas aparecem após sincronizar.',
    [(K+'data/remote/ConcursoApiService.kt','interface ConcursoApiService'),(R,'suspend fun loadStoredExams'),(R,'examDao.markSynced(exam.examId)')],3)
add(12,'P1','Dois modelos de concurso e vários catálogos descrevem preparações diferentes',
    'O seletor de quiz e /contests usam concursos/editais_topicos; /exams, prontidão e parte do app usam ExamBlueprint. O CPNU antigo inclui SST e Direito do Trabalho; o blueprint novo contém FIN_PUB, AFO, DIR_CONST e PORT. Não há migração de questões/histórico legado para a taxonomia canônica: campos apenas opcionais e inferência local não resolvem essa ligação.',
    'A árvore apresentada, o universo de questões sorteado e o denominador de prontidão podem representar provas diferentes com o mesmo ID. Conteúdo legado sem IDs canônicos fica fora do novo domínio. Datas/status/fontes do seed são tratados como oficiais sem uma cadeia de importação verificável neste checkout.',
    'Eleger um modelo canônico, versionar catálogos e criar uma ponte auditável do legado, sem inferir tópicos automaticamente por nome. Distinguir template ilustrativo de edital validado e anexar fonte/revisão aos fatos oficiais.',
    [(B+'services/seed.rs','"Segurança e Saúde do Trabalhador"'),(B+'services/exam_engine.rs','&["FIN_PUB", "AFO", "DIR_CONST", "PORT"]'),(B+'services/exam_engine.rs','if subject.is_empty() && topic.is_empty()'),(B+'models/question.rs','pub canonical_topic_id')],3)
add(13,'P1','Escolha de concursos nos ajustes não controla a escada efetiva',
    'Ajustes salva activeContestIds no perfil. overlayLadder seleciona por StoredExam.isActiveInLadder e ignora essa preferência. Todos os templates locais nascem ativos; não há ligação do toggle dos ajustes com esse atributo.',
    'Desmarcar um concurso não o retira necessariamente de Hoje, da prioridade de prontidão ou do tronco comum. O backend Hoje também lista todos os concursos da coleção em vez de limitar pelos IDs ativos do usuário.',
    'Usar uma única associação usuário-concurso para seleção, prioridade, projeção e persistência local/remota. Aceite: desativar concurso retira seu peso de todos os fluxos e sobrevive ao reinício.',
    [(K+'ui/screens/SettingsScreen.kt','val isSelected = selectedContests.contains'),(V,'val active = exams.filter { it.isActiveInLadder }'),(B+'routes/home.rs','.collection::<ContestDocument>("concursos")')],3)
add(14,'P1','Falhas de rede são convertidas em desempenho e pendências inventados',
    'Os fallbacks adicionam 18 questões do dia, 14 revisões, 23 erros, 412 tentativas e 128 dominadas a uma base local vazia. MainActivity também injeta valores de demonstração enquanto os dados ainda carregam. Não há modo de demonstração explícito nesses DTOs.',
    'Uma pessoa sem histórico vê conquistas, precisão, disciplinas e tarefas que não realizou. Erros de contrato HTTP/JSON também podem ser disfarçados como esses números. Após sincronizar, a base calculada apenas da fila perde os eventos removidos.',
    'Apresentar histórico local real, snapshot remoto com data e estado de atualização, ou indisponibilidade. Manter fixtures exclusivamente em previews/testes ou em um modo de demonstração claramente sinalizado.',
    [(R,'val baseAnswered = 18 + localCountToday'),(R,'val totalPracticed = 412 + localTotal'),(K+'MainActivity.kt','private fun buildOfflineFallbackDashboard()')],4,'Probe Android: banco/fila vazios produzem 412 tentativas, 128 dominadas e 18 respostas hoje')
add(15,'P1','Modos especializados mudam silenciosamente de significado offline',
    'SRS_REVIEW e MOST_BADASS offline caem na consulta genérica de cache. ERROR_NOTEBOOK usa questões comuns quando a lista de erros está vazia. SIMULADO e COMMON_TRUNK partem de um pool parcial de questões aleatórias, sem garantir disponibilidade da disciplina/tópico escolhido.',
    'Revisar pode apresentar questão que nunca venceu; refazer erros pode apresentar uma questão sem erro; tronco comum pode indicar uma sugestão sem oferecer nenhuma questão correspondente. O modo na interface deixa de corresponder ao conjunto selecionado.',
    'Persistir os metadados necessários a cada modo e aplicar o mesmo predicado online/offline. Se não houver itens, mostrar vazio ou opção explícita de treino alternativo. Preparar os pools relevantes antes de prometer uso offline.',
    [(R,'private suspend fun loadFromLocalRoomOrSeed'),(R,'errList.ifEmpty { dao.getCachedQuestions'),(R,'val pool = loadFromLocalRoomOrSeed(null')],4,'Probe Android: caderno de erros vazio devolve questão comum com inErrorNotebook=false')
add(16,'P1','Inferência textual fabrica vínculos canônicos e muda a questão no roundtrip',
    'QuestionCacheMapping associa qualquer disciplina contendo finan a FIN_PUB_01, e várias outras disciplinas a um único tópico por substring. O DTO remoto original é usado online, mas o DTO reconstruído do cache recebe a inferência.',
    'Matemática Financeira e Mercado Financeiro viram Finanças Públicas/Orçamento; questões distintas de uma disciplina viram o mesmo tópico. A mesma questão pode avançar o domínio canônico apenas quando resolvida offline.',
    'Manter IDs ausentes como desconhecidos até haver mapeamento validado por questão/tópico, com proveniência e versão. Garantir roundtrip sem alteração de significado e oferecer uma fila de revisão de mapeamento.',
    [(K+'data/local/QuestionCacheMapping.kt','name.contains("finan")'),(K+'data/local/QuestionCacheMapping.kt','dto.canonicalTopicId ?: inferCanonical')],4,'Probe Android: Matemática Financeira e Mercado Financeiro são mapeados a FIN_PUB_01')
add(17,'P1','Domínio local é a última resposta; domínio remoto usa outro agregado',
    'O repositório grava mastery=confiança/3 para acerto, 0 para erro com confiança 3 e 0.25 para os demais erros, sobrescrevendo canonical_mastery por tópico. O backend calcula média de acurácias por questão, promove dominada a 1 e corta pela metade erros com confiança >=2.',
    'Uma resposta errada de baixa confiança cria 25% de domínio local e 0% remoto. Um acerto com confiança 0 produz 0 local e pode produzir 1 remoto. A sugestão em Hoje é recalculada com o domínio local, enquanto Progresso online vem do servidor.',
    'Formalizar um único contrato de projeção canônica a partir de eventos, com mesma versão e fixtures nos dois runtimes. O cache deve armazenar a projeção e seus eventos pendentes, sem substituir um histórico agregado por uma única resposta.',
    [(R,'val mastery = if (attempt.isCorrect)'),(R,'CanonicalMasteryEntity(topicId, subjectId'),(B+'services/exam_engine.rs','pub fn mastery_fraction')],4)
add(18,'P1','Cobertura praticada é confundida com domínio maior que zero',
    'Prontidão e projeção de cobertura consideram practiced apenas quando mastery > 0. O campo practiced existe no banco local, mas essa informação independente não é usada na fórmula.',
    'Um tópico efetivamente praticado com todos os erros ou um ponto cego pode perder a própria cobertura. A fórmula documentada separa 70% de domínio de 30% de cobertura; a implementação elimina ambos quando o domínio é zero.',
    'Guardar e usar evidência de prática separada do desempenho. Aceite: tópico praticado com domínio 0 tem cobertura 1 e contribuição de 30% antes da ponderação; tópico nunca praticado tem cobertura 0.',
    [(B+'services/exam_engine.rs','practiced: mastery_by_topic'),(E,'practiced = (masteryByTopic[topic.canonicalTopicId]'),(K+'data/local/ExamLocal.kt','val practiced: Boolean')],4,'Probe Rust: um tópico presente com mastery=0 gera prontidão 0 em vez de cobertura 30')
add(19,'P1','Acurácia agregada pode estar matematicamente incorreta',
    'Analytics calcula $avg do percentual de cada questão na disciplina, soma as quantidades de práticas e depois pondera essa média pelo total da disciplina. A árvore antiga também repete a média da disciplina em todos os tópicos.',
    'Exemplo reproduzível da fórmula: uma questão com 1/1 e outra com 0/9 dão 50% na disciplina, embora a precisão das dez tentativas seja 10%. Replicar esse valor em cada tópico sugere cobertura e domínio não demonstrados.',
    'Definir o grão de cada indicador e calcular acertos/tentativas para precisão por tentativa. Para domínio por questão, usar outro nome e denominador. Projetar tópicos apenas por vínculos reais, preservando ausência de evidência.',
    [(B+'routes/analytics.rs','"avg_acc": { "$avg": "$percentual_acertos" }'),(B+'routes/analytics.rs','weighted_acc_sum += accuracy_pct'),(B+'routes/contests.rs','.get(&topic.discipline)')],4)
add(20,'P1','Resultado do simulado ignora os pesos e escolhe penalidade pelo tipo da questão',
    'A tela calcula acertos/total ou acertos-erros/total se existir qualquer TRUE_FALSE no conjunto. Não recebe os pesos nem wrong_answer_penalty. O helper weighted_net_score usa média ponderada das taxas por disciplina e não trata erros/penalidades. Referências de corte são fixas e divergentes: seed CPNU 76, blueprint 78, padrão da sessão 78.5 e analytics 74.',
    'A mesma prova pode receber notas diferentes no helper e na interface. Uma única questão C/E ativa desconto também para erros de outras modalidades. O app não tem informação suficiente para afirmar uma nota oficial ou comparar corretamente com o corte do concurso escolhido.',
    'Formalizar política de pontuação por prova/bloco/questão, levar pesos, contagens, penalidades e fonte do corte até a sessão e reutilizar fixtures de cálculo. Comparar o resultado apresentado com o servidor em cenários mistos e pesos desiguais.',
    [(Q,'val hasCebraspeQuestions'),(Q,'val netScorePct: Double'),(B+'services/exam_engine.rs','pub fn weighted_net_score'),(B+'routes/analytics.rs','historical_cutoff_reference: 74.0')],4)
add(21,'P1','Sorteio de simulado e tronco comum não converge ao blueprint escolhido',
    'fetch_simulado_mode usa proporções de concursos ou fallback CPNU. O Android depois refiltra esse pool por IDs canônicos do blueprint, sem requisitar um conjunto suficiente. O helper admite qualquer tópico da mesma disciplina não marcado como arquivado no payload. commonTrunkIds devolve todos os IDs elegíveis do cache, sem aplicar take(quantity).',
    'Um concurso criado pelo usuário pode receber pool de CPNU e terminar vazio; a quantidade pedida pode não ser respeitada. Questões fora dos tópicos ativos entram no simulado. A seleção ponderada ordena IDs e pega os primeiros, repetindo a mesma seleção se o pool não mudar.',
    'Gerar a bateria a partir do blueprint e de tópicos ativos, com contrato explícito de quantidade, déficit e sorteio. Aplicar exclusões e regras de validade a todos os modos, não apenas a parte dos seletores.',
    [(B+'services/quiz_selector.rs','async fn fetch_simulado_mode'),(R,'val ids = QuizSelection.weightedSimuladoIds'),(B+'services/exam_engine.rs','question.canonical_subject_id == quota.canonical_subject_id && !question.archived'),(E,'?.questionIds.orEmpty()')],4,'Probe Rust seleciona tópico OUTSIDE-EXAM; score sem validação admite 200%')
add(22,'P1','SRS padrão ignora o erro e tem descontinuidade e faixa diferentes do contrato',
    'No modo não simplificado, is_correct não participa do cálculo do intervalo. A fórmula muda bruscamente quando score chega a 0.9. Como score fica em 0..1, o intervalo efetivo é 10..20 dias, apesar dos limites nominais 3..90. O cliente fixa useSimplifiedSrs=false.',
    'Com mesmos parâmetros, acerto e erro recebem o mesmo intervalo. Elevar score de aproximadamente 0.8998 para 0.9 reduz o intervalo de 19 para 10 dias. O comportamento não atende nem a faixa anunciada 3–90 nem os degraus 1/7/30 da especificação posterior.',
    'Escolher e versionar a política de SRS, preservar o histórico de intervalos e testar monotonicidade, erro, baixa confiança, limites e migração entre políticas. Evitar mudar retrospectivamente estados históricos para acomodar uma fórmula nova.',
    [(B+'services/srs_engine.rs','let raw_interval = if score >= 0.9'),(B+'services/srs_engine.rs','if use_simplified_steps'),(R,'useSimplifiedSrs = false')],4,'Probe enumera faixa 10..20 e confirma 19 -> 10 dias e intervalo igual em erro/acerto')
add(23,'P2','Vencimento de revisão e relatório de manutenção não refletem execução exata',
    'A rotina marca revisão com dias_desde_ultima > intervalo, e não no vencimento >=. Roda 30 segundos após boot e a cada 24 horas, sem um horário de negócio definido. Falhas individuais de update são descartadas, mas o contador decay_updated é incrementado.',
    'A revisão pode aparecer um dia civil depois do intervalo, mais o atraso do próximo ciclo. O relatório pode dizer que atualizou documentos cuja escrita falhou. O flag dominada usa pontos sem decaimento, o que exige decisão explícita sobre o significado de retenção.',
    'Definir due_at ou uma regra de vencimento civil consistente, derivar pendência no instante da consulta e contar somente escritas confirmadas. Documentar se domínio permanente e retenção com decaimento são indicadores distintos.',
    [(B+'services/scheduler.rs','dias_desde_ultima >'),(B+'services/scheduler.rs','decay_updated += 1'),(B+'services/scheduler.rs','Duration::from_secs(24 * 60 * 60)')],4)
add(24,'P1','Sessão não é restaurável e cronômetro não representa um prazo persistente',
    'userAnswers e as seleções usam remember, enquanto apenas alguns flags/índices usam rememberSaveable. Questões e session_uuid vivem no ViewModel sem SavedStateHandle ou entidade de sessão. Continuar sessão inicia um novo quiz STANDARD pela disciplina. O cronômetro subtrai 1 após delay, sem deadline persistido.',
    'Recriar a Activity pode manter o índice e perder as respostas; morte do processo perde a bateria. Continuar não retoma a sessão original. Suspensão/recriação pode distorcer o tempo disponível e o resultado do simulado. São defeitos de estado constatados no código; não houve teste em aparelho.',
    'Persistir identidade, snapshot, modo, contexto, respostas, posição e deadline da sessão; usar restauração coerente em Activity/processo e distinguir pausa de novo treino. Aceite: recriação, processo encerrado e retomada offline preservam a sessão e o prazo.',
    [(Q,'val userAnswers = remember'),(Q,'var selectedOptionIndex by remember('),(Q,'secondsRemaining -= 1'),(K+'ui/screens/TodayScreen.kt','onStartQuizMode(QuizMode.STANDARD, pausedDiscipline)')],5)
add(25,'P1','Aplicar atualização de edital só recarrega a árvore e anuncia alteração',
    'applyEditalDiffUpdate chama getContestSyllabus e emite mensagem de cronograma atualizado. Não grava tópicos, não executa publish-edital nem recalcula um plano. A projeção local transforma todos os tópicos em CONFIRMED e aplica o mesmo domínio da disciplina a cada linha.',
    'O consentimento da tela não efetiva a transição prometida. A tela de diff antiga e o blueprint novo não compartilham a mesma atualização. Tópicos novos/arquivados e fontes podem não representar o estado efetivo do plano.',
    'Implementar uma transição versionada de edital com prévia, confirmação, persistência e projeções reconciliadas. A UI deve informar exatamente o que foi aplicado e só anunciar sucesso após o ACK durável.',
    [(V,'fun applyEditalDiffUpdate'),(R,'diffStatus = TopicDiffStatus.CONFIRMED'),(K+'data/remote/ConcursoApiService.kt','suspend fun updateExam')],3)
add(26,'P2','Gravação local de concurso é parcial e sincronização pode confirmar uma revisão antiga',
    'saveExamLocal escreve exame, disciplinas e tópicos em chamadas separadas, sem transação e sem remover/arquivar linhas substituídas. syncPendingExams lê um snapshot e depois executa markSynced apenas pelo examId, sem comparar conteúdo/revisão. A UI dispõe de criação, mas não fecha o fluxo de edição/arquivamento descrito nas notas.',
    'Uma interrupção pode deixar um exame parcialmente salvo. Alteração de grade pode manter linhas antigas; uma edição nova durante o envio pode ser marcada como sincronizada pelo ACK de um snapshot anterior. Parte desses caminhos ainda não está exposta pela UI, mas a base atual não oferece a garantia de revisão.',
    'Transação Room para o agregado e uma revisão/hash para comandos/ACK. Aplicar troca de grade e arquivamento de forma explícita e concluir o fluxo de edição. Testar crash entre escritas e edição enquanto a sincronização aguarda resposta.',
    [(R,'suspend fun saveExamLocal'),(K+'data/local/ExamLocal.kt','suspend fun markSynced(examId: String)'),(K+'ui/screens/ExamBuilderSheet.kt','fun ExamFormBottomSheet')],3)
add(27,'P1','Reporte falho é anunciado como registrado localmente sem uma fila',
    'reportQuestionProblem retorna false em qualquer exceção. O ViewModel transforma false em Reporte registrado localmente. Sincronizará quando online. Não há entidade, DAO ou comando pendente de reporte.',
    'A pessoa acredita ter concluído o reporte, mas a informação existe apenas nos parâmetros da chamada que falhou. No servidor, repetir a mesma categoria faz $addToSet no reporte, porém incrementa reports novamente.',
    'Persistir um evento de reporte antes de confirmar registro local, sincronizá-lo com idempotência e atualizar os contadores a partir de identidade estável; ou informar claramente que o envio falhou.',
    [(R,'suspend fun reportQuestionProblem'),(V,'Reporte registrado localmente.'),(B+'routes/quiz.rs','doc! { "$inc": { "reports": 1 } }')],5)
add(28,'P1','Fallback de tutor pré-resposta entrega e armazena o gabarito',
    'Quando o provedor falha e há explanation na questão, contextual-hint retorna Atente-se ao gabarito oficial: seguido da resposta; child-friendly-pre inclui Gabarito Correto. Esse fallback é persistido no mesmo campo de cache utilizado em chamadas futuras.',
    'Pedir dica ou simplificação antes de responder pode revelar a solução e contaminar a tentativa. A indisponibilidade temporária do provedor pode criar um vazamento permanente no cache. O comportamento contradiz a própria instrução de dica sem entregar a resposta.',
    'Separar recursos pré/pós-resposta, proibir fallback revelador no caminho pré-resposta e não promover contingência a cache pedagógico definitivo. Adicionar testes de não revelação e proveniência/versão do conteúdo gerado.',
    [(B+'services/ai_tutor.rs','Atente-se ao gabarito oficial:'),(B+'services/ai_tutor.rs','**Gabarito Correto:**'),(B+'services/ai_tutor.rs','// 3. Persistir no MongoDB')],4)
add(29,'P2','Análise de plausibilidade é gravada em outro campo de cache',
    'A leitura de plausibility consulta plausibility_analysis. A geração desse tipo escreve explanationExtra, que é o cache de extra-concepts. Também não há coordenação de chamadas concorrentes nem timeout explícito no cliente HTTP do backend.',
    'Sem um cache legado já estruturado, pedir plausibilidade novamente pode gerar outra chamada e sobrescrever os conceitos extras. Consultas concorrentes podem gastar tokens repetidos; uma chamada lenta pode durar além do timeout Android e provocar novos pedidos.',
    'Persistir o mesmo tipo no campo correspondente, com esquema/versão e estado de geração. Usar timeout e deduplicação de trabalho concorrente. Verificar duas chamadas sequenciais e paralelas sem aumentar custo ou trocar o conteúdo de outro tutor.',
    [(B+'services/ai_tutor.rs','AiAssistKind::Plausibility => doc! { "$set": { "explanationExtra"'),(B+'services/ai_tutor.rs','if let Some(pa) = &questao.plausibility_analysis'),(B+'services/ai_tutor.rs','let client = reqwest::Client::new()')],4)
add(30,'P2','Carregamentos assíncronos não protegem o contexto selecionado',
    'selectContest e refreshAllData lançam coroutines sem token/cancelamento de resultado anterior. startQuizSession permite múltiplas buscas e troca o UUID antes de a busca terminar. submitAnswer usa o concurso selecionado no momento do envio, não o contexto congelado da sessão.',
    'Uma resposta lenta de concurso A pode substituir a árvore do B. Dois inícios de quiz podem associar perguntas de uma busca ao modo/UUID da outra. Chamadas da tela de simulado durante seleção/confiança podem gerar sucessivos envios da mesma questão, amplificando C03.',
    'Congelar contexto da sessão, impedir inícios concorrentes e aceitar resultados apenas da operação/revisão vigente. Definir edição de resposta como evento versionado ou envio final único, conforme a semântica escolhida.',
    [(V,'fun selectContest'),(V,'fun startQuizSession'),(V,'contestId = _selectedContestId.value'),(Q,'onSubmitAnswer(question, selectedOptionIndex!!, level, elapsed)')],5)
add(31,'P1','Transporte HTTP e assinatura de desenvolvimento enfraquecem a distribuição',
    'O app usa HTTP, permite cleartext e o Nginx fornecido só escuta porta 80. O hash do APK e o binário vêm desse mesmo canal. O pipeline publica assembleDebug assinado com debug.keystore versionado e senha padrão.',
    'O SHA-256 verifica consistência com o metadado recebido, mas não prova autenticidade de um metadado adulterado no transporte. A chave de desenvolvimento compartilhada não deve servir como segredo de assinatura de produção. Não foi demonstrada interceptação nem comprometimento de chave nesta auditoria.',
    'HTTPS com identidade estável, proteção da chave de release fora do repositório e um canal de atualização coerente com a distribuição pretendida. Validar pacote/certificado/versão e planejar a transição para não impedir a atualização das instalações existentes.',
    [('android-app/gradle.properties','DO_API_BASE_URL=http://'),('deploy/nginx-concursoprep.conf','listen 80'),('android-app/app/build.gradle.kts','storeFile = file("debug.keystore")')],1)
add(32,'P1','Deploy não depende dos testes e gates que a documentação promete',
    'deploy.yml constrói e publica a imagem e compila assembleDebug. Não executa cargo test, testes Android, verifyPaparazziDebug ou lintDebug. O job de insets é apenas manual e independente do deploy. A imagem latest é publicada antes de saber se o Android compila.',
    'Uma tag pode publicar uma versão com defeitos de domínio ou regressão visual. A existência dos testes e a instrução de rodá-los localmente não tornam o deploy condicionado à aprovação desses testes.',
    'Criar uma etapa de validação da revisão exata antes de promover artefatos; usar gates proporcionais e manter o job físico/emulado explicitamente separado quando não disponível. Aceite: introduzir falha intencional em um gate bloqueia publicação da release.',
    [('.github/workflows/deploy.yml','Build e Push da Imagem Rust'),('.github/workflows/deploy.yml','./gradlew assembleDebug'),('.github/workflows/emulator-insets.yml','workflow_dispatch:')],6)
add(33,'P1','Publicação não é atômica e não permite identificar/reverter o conjunto implantado',
    'O workflow usa uma única tag latest, sem concurrency, digest imutável ou manifesto de release. Copia APK e metadados com comandos separados, recria o container e não faz smoke test ou rollback. O script remoto não estabelece set -e.',
    'Leituras durante a cópia podem combinar versão/hash/bytes de artefatos diferentes. Releases simultâneas podem misturar imagem e APK; falhas intermediárias não têm recuperação especificada. Não há evidência que permita chamar esse processo de sem downtime.',
    'Publicar diretórios versionados com manifesto e promoção atômica; fixar imagem por digest, serializar deploy e verificar readiness/versão após troca, com rollback conhecido. Manter a regra de não compilar na VPS.',
    [('.github/workflows/deploy.yml','SERVICE_NAME}:latest'),('.github/workflows/deploy.yml','cp -f /opt/apps/deploy_bundle/android-app/dist/app-debug.apk'),('.github/workflows/deploy.yml','docker compose up -d --no-deps --force-recreate')],6)
add(34,'P2','Script de build local também publica por padrão e usa nome fixo antigo',
    'build-android.ps1 assume SkipVpsPublish=false, copia o APK para Cogniva-v1.0.0.apk, aceita SSH com StrictHostKeyChecking=no e anuncia publicação após scp sem verificar seu código de saída. O script não foi executado na auditoria para evitar sua publicação implícita.',
    'Um comando entendido como compilação pode alterar a distribuição remota. Nome do arquivo, versão interna e mensagem de sucesso podem divergir. O checkout já contém Cogniva-v1.2.2.apk enquanto a configuração e o servidor anunciam 1.3.0.',
    'Separar build de publicação, tornar publicação uma opção explícita e verificar cada etapa. Derivar nome/manifesto da versão interna e usar verificação de identidade SSH configurada.',
    [('android-app/build-android.ps1','[switch]$SkipVpsPublish = $false'),('android-app/build-android.ps1',"'Cogniva-v1.0.0.apk'"),('android-app/build-android.ps1','& scp -o BatchMode=yes')],6)
add(35,'P2','Healthcheck e alegações operacionais não demonstram saúde e capacidade',
    'O healthcheck devolve JSON fixo e não testa MongoDB, filas ou revisão da aplicação. O Dockerfile usa Debian/OpenSSL; o Cargo.toml não contém o perfil release/LTO/strip anunciado. Não há aqui medição de tamanho/RAM, backup/restore, limites de carga nem alerta de falha do scheduler.',
    'Health 200 é compatível com banco indisponível. Não se pode confirmar a implantação do commit auditado nem validar as garantias de imagem <40 MB, ~15 MB de RAM, binário estático e restauração operacional a partir desses arquivos.',
    'Separar liveness/readiness, expor revisão e versão de contratos, registrar falhas reais de escrita e definir backup/restore e smoke tests. Medir imagem e recursos no ambiente-alvo, sem transformar estimativa em garantia.',
    [(B+'main.rs','"status": "ok"'),('backend-rust/Cargo.toml','[dependencies]'),('backend-rust/Dockerfile','FROM debian:bookworm-slim')],6,'Produção: health 200; nenhuma consulta a dados de usuário ou ao Atlas foi feita')
add(36,'P2','Matriz visual nomeia geometrias que o teste não aplica',
    'PreviewMatrix declara 360, 411 e 700 dp e alturas distintas. deviceConfigFor só usa fontScale e nightMode ao copiar DeviceConfig.PIXEL. Os sete PNGs aprovados têm exatamente 562×1000 pixels.',
    'Os nomes tablet, telefone pequeno e S23 não comprovam que essas larguras foram renderizadas. O teste cobre variações de fonte/tema, mas a cobertura de geometria anunciada está superestimada. O job de insets emulado tem outro escopo e não substitui essa matriz.',
    'Converter widthDp/heightDp e densidade da preview para a configuração real, verificar dimensões e renovar baselines mediante revisão visual. Validar telas com conteúdo longo e estado submetido, além da fixture curta atual.',
    [('android-app/app/src/test/java/br/com/concursoprep/app/QuestionPreviewMatrixScreenshotTest.kt','return DeviceConfig.PIXEL.copy('),(K+'ui/theme/PreviewMatrix.kt','widthDp = 700')],6,'Leitura dos headers dos sete PNGs e inspeção visual do baseline Font Scale 2.0')
add(37,'P2','Documentação não distingue visão, baseline antigo e comportamento entregue',
    'README descreve automação integral, compatibilidade total, recursos/caches/retomada e garantias operacionais com mais certeza que a implementação permite. Ainda aponta para nomes de notas removidos no working tree e para Cogniva-v1.0.0.apk. As notas têm decisões concorrentes, como quatro abas versus cinco e dois contratos de SRS, sem registro de precedência.',
    'Não há uma referência única para decidir se uma divergência é defeito, escopo futuro ou mudança deliberada. Os sete pares de notas removidas/novas já estavam no working tree antes da auditoria; não são alterações desta auditoria. Cinco abas pode ser uma decisão válida: não é tratada como defeito funcional isolado.',
    'Criar registro de decisões e matriz requisito/implementação/evidência/status; atualizar links e instruções de JDK e separar explicitamente planejamento de entrega validada. Manter histórico das notas, registrando substituição e data.',
    [('README.md','preservando **100% do histórico'),('README.md','Cogniva-v1.0.0.apk'),('README.md','4 Abas + Full-Screen Quiz')],6)
add(38,'P2','Valores inválidos de domínio são aceitos e data UTF-8 pode causar panic',
    'create_exam valida apenas a desserialização. Título vazio, tronco inexistente, peso negativo, corte 900 e data inválida foram aceitos. parse_iso_date fatia trimmed[..10] por bytes; uma string com três emojis tem tamanho suficiente, mas o byte 10 não é fronteira UTF-8.',
    'É possível persistir um agregado semanticamente impossível e provocar panic em consultas que avaliem a data. O processo inteiro não foi comprovado como encerrado: a reprodução confirma panic na função, não indisponibilidade total do serviço.',
    'Validar o agregado e transições de estado antes de mutar memória/banco; usar parse de data seguro e regras explícitas para pesos, cortes, relações e cardinalidades. Aceite: entradas inválidas devolvem 4xx, sem mutação nem panic.',
    [(B+'routes/exams.rs','let mut exam: ExamBlueprint = serde_json::from_value'),(B+'services/exam_engine.rs','&trimmed[..10]')],3,'POST inválido -> 200; catch_unwind confirma panic com data UTF-8')
add(39,'P1','Cache de questões perde metadados de aprendizagem e não incorpora o ACK',
    'QuestionCacheMapping.toDto não restaura user_stats, syllabus_topic_ids, origem análoga nem flags de tutores. O envio de tentativas usa apenas processed_count: ignora o resultado que informa domínio, caderno e próxima revisão. O cache só adiciona flag de erro local; não a reconcilia quando o servidor informa que a questão saiu do caderno.',
    'A questão em cache perde seu histórico visível e contexto. Caderno local e servidor permanecem diferentes mesmo após sincronização; limpar o cache pode eliminar as únicas questões disponíveis para resolver erros pendentes. Não há uma projeção SRS local suficiente para modo avião.',
    'Definir cache como snapshot semântico completo e atualizar projeções a partir de ACKs identificados/revisionados, preservando eventos locais ainda não confirmados. Testar equivalência online/cache e entrada/saída do caderno offline.',
    [(K+'data/local/QuestionCacheMapping.kt','fun toDto(entity:'),(R,'response.processedCount'),(R,'dao.updateQuestionErrorNotebookFlag(attempt.questionId, true)')],4,'Probe Android confirma perda de user_stats e syllabus_topic_ids no roundtrip')

evidence = json.loads((OUT / 'evidence.json').read_text(encoding='utf-8'))
counts = Counter(f['priority'] for f in findings)
test_dir = OUT / 'android-test-results'
android_tests = []
for path in sorted(test_dir.glob('TEST-*.xml')):
    try:
        suite = ET.parse(path).getroot()
        android_tests.append({k: suite.attrib.get(k, '') for k in ['name','tests','failures','errors','skipped']})
    except ET.ParseError:
        pass
(OUT / 'android-test-summary.json').write_text(json.dumps(android_tests,ensure_ascii=False,indent=2),encoding='utf-8')
(OUT / 'findings.json').write_text(json.dumps(findings,ensure_ascii=False,indent=2),encoding='utf-8')

def log_status(filename):
    p = OUT / filename
    if not p.exists(): return 'não executado'
    s = p.read_text(encoding='utf-8-sig',errors='replace')
    if 'BUILD SUCCESSFUL' in s: return 'PASSOU'
    if 'BUILD FAILED' in s: return 'FALHOU (ver log)'
    return 'em execução ou sem conclusão registrada'

lines = [
'# Cogniva — auditoria de consistência, coerência e convergência',
'',
f'**Data:** 1º de novembro de 2026 · **Checkout:** `{evidence["head"]["stdout"].strip()}` · **Escopo:** working tree local, incluindo as notas renomeadas ainda não commitadas.',
'',
'## Parecer',
'',
'O projeto tem uma direção de produto consistente: transformar o histórico do QuizBot em uma preparação contínua, orientada por edital, com revisão, metacognição e aproveitamento entre concursos. Rust/Axum, MongoDB e Kotlin/Compose/Room são escolhas compatíveis com essa direção, e há implementação substancial dos fluxos e motores.',
'',
'**A implementação ainda não sustenta as garantias de integridade, isolamento e convergência descritas.** A prioridade é estabilizar identidade, registro durável de tentativas e uma única autoridade para concursos e projeções, antes de ampliar funcionalidades. A classificação é: protótipo funcional com componentes de produto, sem evidência suficiente para aceitar a operação como uma plataforma multiusuário com histórico preservado e sincronização confiável.',
'',
f'Foram registrados **{len(findings)} achados: {counts["P0"]} P0, {counts["P1"]} P1 e {counts["P2"]} P2**. P0 bloqueia a confiança básica no sistema; P1 compromete dados ou comportamento central; P2 compromete robustez, verificabilidade ou partes incompletas do contrato. A prioridade se refere ao checkout auditado; não é uma declaração de exploração remota comprovada.',
'',
'Os dois bloqueadores são: autenticação/autorização ausente na prática e confirmação de respostas que o MongoDB rejeitou. A combinação de retry sem idempotência, fila sem proprietário e documento substituído integralmente cria risco tanto de duplicação quanto de perda de histórico.',
'',
'## O projeto que foi compreendido',
'',
'A proposta não é apenas um banco de questões. O edital organiza a preparação; o histórico de resolução alimenta revisão espaçada e domínio; o domínio deve ser canônico e reaproveitado por diferentes concursos; a escada e os pesos de cada concurso projetam prioridades e prontidão. O app deve continuar útil offline e reconciliar o que aconteceu assim que a rede voltar. Tutores ampliam o estudo, e a atualização OTA distribui o cliente.',
'',
'As responsabilidades pretendidas são claras, mas existem quatro planos de estado pouco reconciliados: legado em `concursos/editais_topicos/userActivityLog`, `ExamMemory` no Rust, catálogos compilados no Android e dados no Room. Uma parte da interface usa cada plano. Algumas falhas são ocultadas por fixtures e mensagens otimistas.',
'',
'```mermaid',
'flowchart LR',
'  L["Mongo legado: concursos e editais_topicos"] --> S["Seletor antigo de quiz"]',
'  M["ExamMemory: seed por processo"] --> A["API /exams e prontidão"]',
'  M -. "escrita best effort; sem reidratação" .-> D["Mongo user_exams"]',
'  K["Catálogo compilado Kotlin"] --> R["Room: escada e domínio local"]',
'  R --> U["Hoje e edital local"]',
'  R -. "POST/PUT; sem pull" .-> A',
'  S --> Q["Sessão Android"]',
'  Q --> O["Fila local de tentativas"]',
'  O --> H["userActivityLog"]',
'  H --> P["Analytics remoto"]',
'```',
'',
'### Critérios usados',
'',
'- **Consistência:** o mesmo fato conserva identidade, valor, dono, data e versão nos contratos e persistências.',
'- **Coerência:** a regra aplicada corresponde à promessa pedagógica, à política da prova e ao significado comunicado pela interface.',
'- **Convergência:** após retry, reconexão, reinício ou segundo aparelho, os estados chegam à mesma verdade sem perder ou duplicar eventos.',
'',
'## Método, evidência e limites',
'',
'Foram examinados README e notas conceituais/arquiteturais, modelos BSON/JSON, todos os módulos de rotas e serviços do backend, persistência e sincronização Android, navegação/ViewModel/telas, testes, Gradle, manifest, scripts e workflows de distribuição. Os vínculos abaixo apontam para linhas reais do working tree.',
'',
'A auditoria não alterou código de produção, preferências de usuário, banco remoto ou deployment. Os probes Rust usam uma cópia temporária do código e um servidor Mongo sintético em loopback; os probes Android usam proxies de API/DAO e são adicionados apenas à execução de teste por um init script. As mudanças pré-existentes de nomes das notas foram preservadas.',
'',
'Não foram lidos segredos de `.env`, acessados históricos pessoais no Atlas ou executados endpoints mutáveis na produção. Somente health e metadados públicos de APK foram consultados remotamente. Não há ADB conectado. Acessibilidade interativa, rotação real, instalação OTA, notificações e comportamento em Samsung/One UI permanecem sem validação física. A veracidade externa de datas e editais do seed não foi validada; fonte genérica embutida não foi tratada como comprovação de edital.',
'',
'Não se declara esta auditoria como prova de ausência de outros defeitos. Riscos concorrentes inferidos do código são identificados como tais; as reproduções foram limitadas a dados sintéticos. O servidor Mongo mínimo valida o tratamento do protocolo e das respostas pelo driver/handler, mas não substitui ensaios de transação, índices e concorrência em MongoDB real isolado.',
'',
'## Validação executada',
'',
'| Verificação | Resultado / alcance |',
'|---|---|',
'| `cargo test --locked` no checkout | 18/18 testes passaram. |',
'| Probes adversariais Rust | 12/12 passaram confirmando defeitos; assertions descrevem o comportamento observado, não a correção desejada. |',
'| Primeiro gate Android com JBR/JDK 25 | Build do APK passou; suíte total 20 testes, sete screenshots falharam por versão de bytecode não suportada. O resultado não comprova regressão visual do app. |',
'| Repetição com JDK 17 e verify visual | 27 testes: 26 passaram; uma comparação visual em Font Scale 2.0 falhou, com diferença de 0,222396%. Os sete probes Android passaram. |',
f'| `lintDebug assembleDebug` com JDK 17, execução complementar | {log_status("android-lint-build.log")}. Lint: 0 erros, 37 warnings e 1 hint. |',
'| Headers dos baselines visuais | Sete arquivos com 562×1000 pixels; larguras declaradas de preview não são aplicadas pela configuração do teste. |',
'| ADB | Nenhum dispositivo conectado. |',
'| API publicada | Health e dois endpoints de metadados responderam HTTP 200 na captura. Não foi feito teste de escrita remoto. |',
'',
'Os sete probes Android investigam ACK zero, retorno normal após falha de rede, envio duplicado em duas sincronizações concorrentes, números fictícios com histórico vazio, perda de metadados no cache, inferência canônica indevida e caderno de erros substituído por questões comuns.',
'',
'A primeira versão do probe SRS utilizou um parâmetro de tempo que permanecia na faixa de clamp do fator; esse assert falhou no harness. A fixture foi corrigida para os valores 1501/1500 segundos, sem alteração do produto, e a execução final confirmou a descontinuidade. O log inicial foi retido para rastreabilidade.',
'',
'A diferença visual foi inspecionada: concentra-se no texto/contorno do CTA Confirmar Resposta. A causa ainda não foi isolada entre baseline e ambiente de renderização; não é apresentada como prova de um defeito funcional de layout. O baseline original foi preservado. [Comparação visual retida](font-scale-2-diff.png). A nota recomenda JDK 21 como baseline histórico; nesta máquina foi localizado JDK 17 compatível para eliminar a falha de bytecode do JDK 25. A reprodução futura deve padronizar também esse ambiente.',
'',
'### Snapshot dos artefatos e do servidor',
'',
f'Captura: `{evidence["captured_at"]}`. O arquivo raiz é APK **1.2.2 / build 5**; o Gradle e o APK compilado localmente são **1.3.0 / build 6**. A API publicada anuncia **1.3.0 / build 6**. O hash do APK local compilado difere do hash anunciado remotamente: isso, por si só, não prova adulteração, pois builds distintos podem ter bytes diferentes; falta um manifesto que vincule revisão, build e artefato.',
'',
'| Artefato | SHA-256 observado |',
'|---|---|',
]
for item in evidence.get('apk_artifacts',[]):
    lines.append(f'| `{item["path"]}` | `{item["sha256"]}` |')
for item in evidence.get('public_read_only_probes',[]):
    if item['url'].endswith('/api/v1/apk/info') and item.get('status') == 200:
        remote = json.loads(item['body'])
        lines.append(f'| Hash anunciado pelo servidor, build {remote["version_code"]} | `{remote["sha256_checksum"]}` |')
lines += ['', '## Registro dos achados', '', '| ID | Prioridade | Achado |', '|---|---|---|']
for f in findings:
    lines.append(f'| {f["id"]} | {f["priority"]} | {f["title"]} |')
for f in findings:
    lines += ['', f'### {f["id"]} · {f["priority"]} · {f["title"]}', '',
              '**Evidência.** ' + f['evidence'], '', '**Efeito.** ' + f['effect'], '',
              '**Correção e aceite.** ' + f['repair'], '', '**Base:** ' + ' · '.join(f['refs']) + '.', '',
              f'**Validação:** {f["proof"]}. **Fase de recuperação:** {f["phase"]}.']
lines += [
'', '## Plano de convergência por dependência', '',
'A ordem reduz o risco de corrigir projeções sobre dados que continuam sendo gravados incorretamente. Não requer trocar Rust, Kotlin ou MongoDB, nem reconstruir ou apagar o histórico existente.', '',
'### Antes de migrar dados', '',
'Preservar exportação/backup verificável e fingerprints por coleção relevante; identificar o código e os formatos legados; recolher amostras anonimizadas e definir como comprovar invariantes antes/depois. Não limpar filas ou recriar históricos para fazer uma validação passar. Não promover correções inferidas de nome de disciplina a fatos históricos.', '',
'### Fase 1 — Identidade, isolamento e canal confiável', '',
'Fechar C01, C06, C10 e C31. Definir identidade local provisória, vínculo autenticado, sessão verificada e proprietário em todo evento/agregado. Separar catálogo oficial de instâncias privadas. Estabelecer HTTPS e plano de assinatura/distribuição compatível com instalações atuais.', '',
'**Saída exigida:** A e B possuem dados e escadas isolados; token inválido não lê/escreve; trocar conta não transfere a fila; ações administrativas exigem privilégio.', '',
'### Fase 2 — Registro durável de tentativas e fronteira do legado', '',
'Fechar C02–C05, C07 e C08. Criar identidade imutável por evento, instante de ocorrência, revisão da questão e validação autoritativa. Preferir evento durável com projeção recuperável; se houver materialização, fazê-la de forma atômica/versionada. ACK deve significar persistência confirmada. Atualizações legadas devem ser aditivas e preservar campos desconhecidos.', '',
'**Saída exigida:** repetir dez vezes um evento conta uma; perder o ACK e reenviar não altera o histórico; falha no segundo item não duplica o primeiro; rejeição no banco mantém a fila; duas tentativas simultâneas sobrevivem. Estudos offline conservam o dia original.', '',
'### Fase 3 — Um contrato de concurso e reconciliação de estado', '',
'Fechar C09, C11–C13, C25, C26 e C38. Tornar persistência/revisão obrigatória; recuperar estado após restart; unificar Concurso/ExamBlueprint e mapear o legado com revisão humana de ambiguidades. Completar pull/push, conflitos, seleção da escada, publicação de edital e edição. Validar o agregado antes de mutá-lo.', '',
'**Saída exigida:** criar no A, reiniciar servidor e abrir no B conserva a revisão; desativar concurso muda todas as projeções; publicar edital arquiva sem apagar histórico e chega a ambos os clientes; ACK antigo não confirma edição nova.', '',
'### Fase 4 — Contratos pedagógicos e projeções reprodutíveis', '',
'Fechar C14–C23, C28, C29 e C39. Formalizar SRS, domínio, cobertura, grão das métricas, pontuação e fontes dos cortes. Usar fixtures contratuais idênticas nos dois runtimes. Preservar ausências como ausência, sem números de demonstração ou tópicos inventados. Completar projeções offline e contratos dos tutores.', '',
'**Saída exigida:** uma sequência de eventos tem o mesmo resultado online/offline e em Rust/Kotlin; cobertura distingue erro de ausência de prática; o simulado respeita edital, quantidade, pesos e penalidade; tutor pré-resposta não revela gabarito em erro de provedor.', '',
'### Fase 5 — Sessão e interface resistentes a interrupção', '',
'Fechar C24, C27 e C30. Persistir sessão/deadline/contexto; proteger callbacks e respostas assíncronas; distinguir pendente, falhou e confirmado. Tornar reporte uma operação durável. Exibir estado de rede e origem dos dados onde isso muda a interpretação do usuário.', '',
'**Saída exigida:** responder, girar, encerrar processo, retomar e sincronizar mantém os mesmos eventos; voltar de background não aumenta o tempo da prova; seleção rápida de concursos não mistura árvores; reporte local tem registro inspecionável.', '',
'### Fase 6 — Entrega verificável e operação', '',
'Fechar C32–C37. Gates executados antes de promoção, manifesto de release, artefatos imutáveis, promoção atômica, smoke/readiness e rollback. Corrigir matriz de geometrias e registrar a validação física separadamente. Atualizar README e decisões conforme comportamento comprovado. Builds permanecem locais ou no CI, nunca na VPS.', '',
'**Saída exigida:** uma release identifica commit, contratos, imagem/digest, APK/versão/hash/certificado e resultados dos gates; falha bloqueia promoção e rollback é testado. Claims de consumo/tamanho têm medição.', '',
'## Matriz de recuperação e ensaios obrigatórios', '',
'| Cenário | Invariante que precisa ser provada |',
'|---|---|',
'| ACK perdido após persistir | Um evento, um efeito, ACK repetível. |',
'| Mongo rejeita a escrita | Nenhum ACK durável; evento permanece local. |',
'| Lote parcialmente inválido | Resultado identificável por item; retry não duplica prefixo. |',
'| Dois sincronizadores | Uma aplicação por event_id; nenhuma escrita perdida. |',
'| Troca/vínculo de conta com fila | Proprietário original preservado e transição explícita. |',
'| Questão legada e gabarito ambíguo | Conversão comprovada ou revisão; nunca alternativa A implícita. |',
'| Sessão offline atravessando meia-noite | occurred_at e dia de estudo preservados. |',
'| Dois aparelhos e restart do servidor | Mesma revisão de escada e edital, com conflito explícito. |',
'| Tópico arquivado | Sai da seleção/denominador correspondente, mantém histórico canônico. |',
'| Recriação e morte do processo | Mesma sessão, respostas, posição e prazo. |',
'| Provedor IA indisponível | Não revela solução em dica e não envenena cache. |',
'| Release incompleta | Conjunto anterior continua íntegro ou rollback verificável. |',
'',
'## Decisões que precisam ser registradas', '',
'1. Qual contrato de SRS é vigente e como se versiona sua substituição: legado ou degraus 1/7/30?',
'2. Qual é o significado de domínio, prática, retenção e prontidão? São projeções distintas e precisam de denominadores explícitos.',
'3. Qual é a política oficial de pontuação por concurso/bloco e a proveniência de data, banca e nota de corte?',
'4. O que é template ilustrativo, estimativa editorial e edital oficialmente validado? O estado deve viajar no contrato, não só no texto da tela.',
'5. O produto já precisa operar com múltiplos usuários/aparelhos e qual é o modelo de vínculo ao Telegram? O código atual não oferece isolamento confiável.',
'6. Quais partes das notas são roadmap e quais constituem aceite da release atual? Cinco abas, por exemplo, pode ser decisão de produto, sem ser defeito.',
'',
'Essas decisões não impedem corrigir imediatamente os bloqueadores de integridade. Servem para evitar que cada camada implemente uma interpretação diferente da mesma regra.', '',
'## Materiais reproduzíveis', '',
'- [Evidência de ambiente, hashes, status e probes públicos](evidence.json).',
'- [Registro estruturado dos 39 achados](findings.json).',
'- [Suíte Rust original](cargo-test.log).',
'- [Probes Rust: resultado final](backend-probes.log), [código](backend-probes.rs) e [execução em cópia temporária](run-backend-probes.ps1).',
'- [Primeiro resultado Android/JDK 25](android-validation.log), [repetição JDK 17](android-jdk17-validation.log), [resumo XML](android-test-summary.json).',
'- [Build e lint complementares](android-lint-build.log), [resultado do lint](lint-results-debug.txt) e [diferença visual fonte 2.0](font-scale-2-diff.png).',
'- [Verificação final de fingerprints e artefato local](final-verification.json).',
'- [Probes Android](android-probes/AuditRepositoryTest.kt), [init script](audit.init.gradle) e [comando reproduzível](run-android-probes.ps1).',
'- [Coletor de evidência somente leitura](collect-evidence.py) e [gerador deste relatório com validação de referências](build-report.py).',
'',
'Os probes mantêm asserts do defeito observado para tornar a auditoria reproduzível. Durante a correção, seus cenários devem ser convertidos em testes com os resultados desejados. Eles não foram incorporados à suíte de produção nem executados contra os dados reais.',
]
(OUT / 'AUDITORIA-COGNIVA.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print(json.dumps({'findings':len(findings),'priorities':dict(counts),'code_references':sum(len(f['refs']) for f in findings), 'android_tests':android_tests, 'android_status':log_status('android-jdk17-validation.log')},ensure_ascii=False,indent=2))
