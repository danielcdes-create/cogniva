import {helpTopics,searchHelp} from './help.js';
import {parseQuestions,verifyReceipt,blankExam,newComponent,objectiveDraft,renameEntity,freshImport,exportWorkspace,restoreWorkspace,validityPresentation,legacyQuestionDraft,manualProvenance,topicPath,orderedTopics} from './domain.js';

const $ = selector => document.querySelector(selector);
const uid = () => crypto.randomUUID();
const copy = value => structuredClone(value);
const pretty = value => JSON.stringify(value,null,2);
const labels = {overview:'Visão geral',exams:'Concursos',edital:'Editais e grades',trunks:'Troncos de carreira',subjects:'Disciplinas e assuntos',questions:'Banco de questões',import:'Inserir questões',catalog:'Revisões do catálogo',drafts:'Rascunhos e pendências',help:'Ajuda',exam:'Editar concurso',question:'Editar questão',entity:'Editar catálogo'};
const lifecycle = {RUMOR_PREVISTO:'Previsto',AUTORIZADO:'Autorizado',BANCA_DEFINIDA:'Banca definida',EDITAL_PUBLICADO:'Edital publicado',PROVA_REALIZADA:'Prova realizada',ARQUIVADO:'Arquivado',EXCLUIDO:'Excluído'};
const kinds = {MULTIPLE_CHOICE:'Múltipla escolha',TRUE_FALSE:'Certo / Errado',OPEN_ENDED:'Discursiva'};
const statuses = {UNKNOWN:'Desconhecida',UNDER_REVIEW:'Em revisão',CURRENT:'Atual',OBSOLETE:'Obsoleta'};
let helpFocus = '';
const helpForPage={overview:'visao-geral',exams:'concursos',exam:'editais-grades',edital:'editais-grades',trunks:'troncos',subjects:'disciplinas-assuntos',questions:'banco-questoes',question:'banco-questoes',import:'inserir-individual',catalog:'revisoes-catalogo',entity:'revisoes-catalogo',drafts:'rascunhos'};
let current = 'overview', me = null, data = {}, ws = {}, busy = false, sequence = 0;
let storageBlocked = false, unreadableStorage = null;
let selectedExam = null, selectedQuestion = null, selectedEntity = null, entityGroup = 'trunks';
let questionPage = null, questionSearch = '', questionType = '', catalogTab = 'trunks';

function h(tag, attributes = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key,value] of Object.entries(attributes)) {
    if (key.startsWith('on')) node.addEventListener(key.slice(2),value);
    else if (key === 'class') node.className = value;
    else if (['value','checked','disabled','selected','hidden'].includes(key)) node[key] = value;
    else if (value !== false && value != null) node.setAttribute(key,String(value));
  }
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}
const button = (text,action,style = '') => h('button',{type:'button',class:style,onclick:action},text);
const badge = (text,style = '') => h('span',{class:`badge ${style}`},text);
const card = (...content) => h('section',{class:'card'},...content);
const actions = (...content) => h('div',{class:'actions'},...content);
function field(label,value,change,options = {}) {
  const id = `field-${++sequence}`;
  const input = options.options ? h('select',{id,disabled:options.disabled,onchange:e=>change(e.target.value)},...options.options.map(([v,t])=>h('option',{value:v,selected:String(value ?? '')===String(v)},t)))
    : h(options.multiline ? 'textarea' : 'input',{id,value:value ?? '',type:options.type || 'text',rows:options.rows || 3,required:options.required || false,disabled:options.disabled || false,min:options.min,max:options.max,step:options.step,maxlength:options.maxlength,class:options.code?'json-editor':'',oninput:e=>change(e.target.value)});
  return h('div',{class:`field ${options.wide?'wide':''}`},h('label',{for:id},label),input,options.help?h('small',{},options.help):null);
}
const check = (label,checked,change,disabled=false) => h('label',{},h('input',{type:'checkbox',checked,disabled,onchange:e=>change(e.target.checked)}),h('span',{},label));
function notice(text,kind='') {const node=$('#notice');node.textContent=text;node.className=`notice ${kind}`;node.hidden=!text;}
function persist() {
  if (!me) throw new Error('Entre na conta antes de editar.');
  if (storageBlocked) throw new Error('O arquivo local ilegível foi preservado. Exporte-o e restaure uma cópia válida antes de editar.');
  try {localStorage.setItem(`cogniva-admin:${me.user_id}`,JSON.stringify(ws));}
  catch {throw new Error('Não foi possível salvar o rascunho neste navegador. Exporte sua cópia antes de continuar; o envio foi bloqueado.');}
}
function edit(action) {try {action();persist();notice('Rascunho salvo neste navegador.');return true;} catch(e){notice(e.message,'error');return false;}}
async function api(path,{method='GET',body}={}) {
  if (!/^(admin|catalog|exams|auth)(\/|$)/.test(path) || path.includes('..')) throw new Error('Rota inválida.');
  const controller=new AbortController(), timer=setTimeout(()=>controller.abort(),45000);
  try {
    const headers={};
    if (method!=='GET') headers['X-Cogniva-CSRF']='1';
    if (body!==undefined) headers['Content-Type']='application/json';
    const response=await fetch(`/api/v1/${path}`,{method,headers,credentials:'same-origin',cache:'no-store',body:body===undefined?undefined:JSON.stringify(body),signal:controller.signal});
    const payload=await response.json().catch(()=>({message:`Resposta inesperada (HTTP ${response.status}).`}));
    if (!response.ok) {const error=new Error(payload.message || `HTTP ${response.status}`);error.status=response.status;throw error;}
    return payload;
  } catch(error) {
    if (error.name==='AbortError') throw new Error('Tempo de resposta excedido. Os comandos permanecem salvos; confira ou reenvie a mesma operação.');
    throw error;
  } finally {clearTimeout(timer);}
}
async function run(message,action) {
  if(busy)return;
  busy=true;$('#progress').textContent=message;$('#progress').hidden=false;
  document.querySelectorAll('#app button,#view input,#view select,#view textarea').forEach(node=>node.disabled=true);
  try {await action();} catch(error) {notice(error.status===401?`Sua sessão expirou. Os rascunhos foram preservados. Saia e entre com um novo código. ${error.message}`:error.message,'error');}
  finally {busy=false;$('#progress').hidden=true;document.querySelectorAll('#app button').forEach(node=>node.disabled=false);render();}
}
function download(name,value) {
  downloadText(name,pretty(value),'application/json');
}
function downloadText(name,text,type='text/plain') {
  const url=URL.createObjectURL(new Blob([text],{type}));
  const a=h('a',{href:url,download:name});document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
}
async function confirm(title,text) {
  const dialog=$('#confirm-dialog');$('#confirm-title').textContent=title;$('#confirm-text').textContent=text;
  return new Promise(resolve=>{dialog.addEventListener('close',()=>resolve(dialog.returnValue==='confirm'),{once:true});dialog.returnValue='cancel';dialog.showModal();});
}
function heading(title,subtitle,...buttons) {return h('div',{class:'page-heading'},h('div',{},h('span',{class:'eyebrow'},'COGNIVA · ADMINISTRAÇÃO'),h('h1',{},title),h('p',{},subtitle)),actions(...buttons,current!=='help'?button('Ajuda desta tela',()=>{helpFocus=helpForPage[current] || 'primeiros-passos';navigate('help');}):null));}
function structureGuide(expanded=false) {
  return h('details',{class:'card structure-guide',...(expanded?{open:true}:{})},h('summary',{},'Como troncos, concursos, conteúdo e questões se conectam'),
    h('div',{class:'structure-grid'},
      h('section',{},h('h3',{},'Organização dos estudos'),h('p',{class:'structure-path'},'Tronco → Concurso → Grade do edital → Componentes'),h('p',{},'Tronco agrupa uma área de carreira. O concurso identifica a oportunidade e o cargo. A grade organiza o conteúdo previsto ou publicado no edital; cada componente é uma disciplina em uma etapa, com peso e quantidade próprios.'),actions(button('Troncos',()=>navigate('trunks')),button('Concursos',()=>navigate('exams')),button('Grades',()=>navigate('edital')))),
      h('section',{},h('h3',{},'Catálogo de conteúdo compartilhado'),h('p',{class:'structure-path'},'Disciplina → Assunto → Subassunto'),h('p',{},'A disciplina é reutilizada por vários troncos e concursos. Assuntos pertencem a uma disciplina; subassuntos detalham um assunto. Cada componente da grade escolhe os assuntos e subassuntos que serão estudados.'),button('Explorar disciplinas e assuntos',()=>navigate('subjects')))),
    h('section',{class:'structure-connection'},h('h3',{},'Onde entram as questões?'),h('p',{},'A questão é classificada pelo conteúdo: disciplina e assunto ou subassunto. Banca, prova e ano registram sua origem. O uso em outro concurso depende da correspondência com a grade e das regras de compatibilidade.'),h('p',{},'Exemplo: um concurso do tronco Fiscal pode ter Língua Portuguesa na prova objetiva e na discursiva, em dois componentes. Ambos usam a mesma disciplina do catálogo, com pesos e assuntos próprios. O tronco sugere disciplinas; a grade define o que será cobrado.'),button('Explorar banco de questões',()=>navigate('questions'))));
}
function navigate(page) {if(busy)return;current=page;$('#app').classList.remove('menu-open');$('#menu-toggle').setAttribute('aria-expanded','false');notice('');render();window.scrollTo({top:0});}
async function loadData() {
  const [base,templates,exams,capabilities,summary]=await Promise.all([api('admin/catalog/base'),api('catalog/trunks'),api('exams'),api('admin/questions/import/capabilities'),api('admin/summary')]);
  data={...data,base,templates,exams,capabilities,summary};
}
async function bootstrap() {
  me=await api('admin/web/me');
  $('#login').hidden=true;$('#app').hidden=false;$('#account-name').textContent=me.profile.display_name || `Conta ${me.user_id}`;
  const saved=localStorage.getItem(`cogniva-admin:${me.user_id}`);
  try {ws=saved?JSON.parse(saved):{};if(!ws || typeof ws!=='object' || Array.isArray(ws))throw new Error();}
  catch {storageBlocked=true;unreadableStorage=saved;ws={};}
  ws.exams ||= {};ws.questionEdits ||= {};ws.archives ||= [];
  await loadData();render();
}
function render() {
  if(!me)return;
  $('#breadcrumb').textContent=`Administração / ${labels[current] || current}`;
  document.querySelectorAll('[data-page]').forEach(node=>{const active=node.dataset.page===current || (current==='exam' && node.dataset.page==='exams') || (current==='question' && node.dataset.page==='questions') || (current==='entity' && node.dataset.page==='catalog');node.classList.toggle('active',active);if(active)node.setAttribute('aria-current','page');else node.removeAttribute('aria-current');});
  const view=$('#view');view.replaceChildren();
  if(storageBlocked){view.append(card(h('h2',{},'O rascunho local está ilegível e foi preservado'),h('p',{},'Exporte a cópia original antes de restaurar um arquivo válido da mesma conta. Nenhum envio será iniciado.'),button('Exportar cópia original',()=>downloadText('cogniva-rascunho-recuperacao.txt',unreadableStorage || '')),
    h('input',{type:'file',accept:'.json,application/json','aria-label':'Restaurar arquivo válido',onchange:async e=>{try{const file=e.target.files[0];if(!file)return;if(file.size>4*1024*1024)throw new Error('Arquivo excede 4 MiB.');const recovered=restoreWorkspace(me.user_id,JSON.parse(await file.text()));if(await confirm('Restaurar após exportar a cópia original?','O arquivo válido substituirá a cópia ilegível apenas no armazenamento local desta conta.')){storageBlocked=false;ws=recovered;ws.exams ||= {};ws.questionEdits ||= {};ws.archives ||= [];try{persist();unreadableStorage=null;render();}catch(error){storageBlocked=true;throw error;}}}catch(error){notice(error.message,'error');}}})));return;}
  if(!data.base && current!=='help'){view.append(heading('Conectar ao acervo','Carregue os dados para continuar.'),button('Recarregar',()=>run('Carregando…',loadData),'primary'));return;}
  const views={overview:overviewView,exams:examsView,edital:examsView,exam:examView,trunks:trunksView,subjects:subjectsView,questions:questionsView,import:importView,question:questionView,catalog:catalogView,entity:entityView,drafts:draftsView,help:helpView};
  try {const page=views[current]();if(['overview','exams','edital','exam','trunks','subjects','questions','import','question','catalog','entity'].includes(current))page.firstElementChild.after(structureGuide(current==='overview'));view.append(page);} catch(error) {view.append(card(h('h2',{},'Rascunho preservado'),h('p',{},`Não foi possível abrir esta tela: ${error.message}`),button('Exportar dados locais',()=>download('cogniva-rascunhos.json',exportWorkspace(me.user_id,ws)))));}
  if(busy)view.querySelectorAll('input,select,textarea,button').forEach(node=>node.disabled=true);
}
function helpView() {
  const root=h('div',{},heading('Ajuda do portal','Entenda a estrutura, encontre uma tela e siga os passos para concluir seu trabalho.'));
  const count=h('p',{class:'help-count',role:'status','aria-live':'polite'});
  const articles=h('div',{class:'help-articles'});
  const draw=query=>{
    const found=searchHelp(query);
    count.textContent=query.trim()?`${found.length} de ${helpTopics.length} tópicos encontrados`:`${helpTopics.length} tópicos · escolha um tema ou use a busca`;
    articles.replaceChildren(...found.map(topic=>h('details',{class:'card help-article',id:`help-${topic.id}`,open:!!query.trim() || topic.id===helpFocus},
      h('summary',{},h('span',{class:'eyebrow'},topic.category),h('span',{class:'help-title'},topic.title)),
      h('p',{},topic.intro),h('ol',{},...topic.steps.map(step=>h('li',{},step))),
      ...topic.notes.map(note=>h('p',{class:'help-note'},note)),
      button(topic.action,()=>navigate(topic.page),'primary'))));
    if(!found.length)articles.append(card(h('h2',{},'Nenhum tópico encontrado'),h('p',{},'Tente palavras como concurso, pré-edital, questões, catálogo, rascunho ou sessão.')));
  };
  const search=h('input',{type:'search','aria-label':'Buscar na ajuda',placeholder:'Buscar: pré-edital, Banco Central, questões, sessão…',oninput:e=>draw(e.target.value)});
  const toc=h('nav',{class:'help-index','aria-label':'Tópicos da ajuda'},...helpTopics.map(topic=>button(topic.title,()=>{search.value='';helpFocus=topic.id;draw('');document.getElementById(`help-${topic.id}`)?.scrollIntoView({behavior:'smooth',block:'start'});})));
  root.append(h('section',{class:'card help-search'},h('label',{},'O que você quer fazer?',search),count),
    h('details',{class:'card help-toc'},h('summary',{},'Todos os tópicos da ajuda'),toc),articles);
  draw('');
  if(helpFocus)requestAnimationFrame(()=>document.getElementById(`help-${helpFocus}`)?.scrollIntoView({block:'start'}));
  return root;
}

function overviewView() {
  const root=h('div',{},heading('Seu acervo, em perspectiva.','Organize a base de estudos e continue de onde parou.',button('＋ Inserir questões',()=>navigate('import'),'primary')));
  const summary=data.summary || {};
  const stats=[['CONCURSOS',summary.exams ?? data.exams.length,'Na sua conta'],['TRONCOS',data.base.catalog.trunks.length,'Carreiras no catálogo'],['DISCIPLINAS',data.base.catalog.subjects.length,'Identidades compartilhadas'],['QUESTÕES',summary.questions ?? '—','No acervo compartilhado']];
  root.append(h('div',{class:'stat-grid'},...stats.map(([label,value,note])=>h('section',{class:'card stat'},h('span',{},label),h('strong',{},value),h('small',{},note)))));
  root.append(h('section',{class:'card hero'},h('div',{},h('span',{class:'eyebrow'},'CONSTRUA SUA PRÓXIMA ETAPA'),h('h2',{},'Um edital bem organizado começa por uma boa base.'),h('p',{},'Monte a grade, ajuste pesos e etapas e aproveite as disciplinas que conectam seus concursos.'),button('Criar concurso',()=>openExam(),'primary')),h('div',{class:'hero-art','aria-hidden':'true'},'↗')));
  root.append(h('div',{class:'split'},card(h('h2',{},'Acesso rápido'),h('div',{class:'catalog-row'},h('div',{},h('strong',{},'Concursos e editais'),h('small',{},'Status, cronograma, componentes e conteúdo programático.')),button('Organizar →',()=>navigate('exams'))),h('div',{class:'catalog-row'},h('div',{},h('strong',{},'Banco de questões'),h('small',{},'Consulte o acervo, revise contratos e cadastre em lote.')),button('Explorar →',()=>navigate('questions'))),h('div',{class:'catalog-row'},h('div',{},h('strong',{},'Revisões do catálogo'),h('small',{},'Prepare alterações versionadas sem perder identidades.')),button('Revisar →',()=>navigate('catalog')))),card(h('h2',{},'Uma estrutura que se conecta'),h('p',{},'Troncos organizam carreiras. Concursos têm etapas, disciplinas e assuntos próprios. Questões se vinculam ao conteúdo, com classificação revisada.'),badge(`Catálogo v${data.base.catalog.catalog_version}`,'good'),h('hr',{class:'divider'}),h('p',{},'As grades e questões usam a mesma API do Android. O catálogo global pode ser revisado e ativado pelo painel, com versões e histórico preservados.'),button('Atualizar indicadores',()=>run('Atualizando indicadores…',async()=>{data.summary=await api('admin/summary');notice('Indicadores atualizados.');})))));
  return root;
}
function searchable(placeholder,rows) {
  const input=h('input',{type:'search',placeholder,'aria-label':placeholder,oninput:e=>{const query=e.target.value.toLocaleLowerCase('pt-BR');rows.forEach(([node,text])=>node.hidden=!text.toLocaleLowerCase('pt-BR').includes(query));}});
  return h('div',{class:'toolbar'},input);
}
function examsView() {
  const root=h('div',{},heading(current==='edital'?'Editais e grades':'Concursos',current==='edital'?'Cada concurso tem uma grade: disciplinas por etapa, com pesos, quantidades e assuntos. Prepare a base e confirme o conteúdo do edital publicado.':'O concurso identifica a oportunidade e o cargo dentro de um tronco de carreira. Abra sua grade para organizar o conteúdo cobrado.',button('＋ Criar concurso',()=>openExam(),'primary')));
  const tbody=h('tbody'),rows=[];
  for(const exam of data.exams){
    const node=h('tr',{},h('td',{},h('strong',{},exam.short_name),h('span',{class:'secondary'},exam.title)),h('td',{},data.base.catalog.trunks.find(t=>t.trunk_id===exam.trunk_slug)?.label || exam.trunk_slug),h('td',{},badge(lifecycle[exam.status] || exam.status,exam.status==='EDITAL_PUBLICADO'?'good':'')),h('td',{},`${exam.subjects.length} componentes por etapa`,h('span',{class:'secondary'},`Revisão ${exam.revision}`)),h('td',{},button('Abrir grade →',()=>openExam(exam))));
    tbody.append(node);rows.push([node,`${exam.title} ${exam.short_name} ${exam.trunk_slug} ${lifecycle[exam.status]}`]);
  }
  root.append(searchable('Buscar concurso, tronco ou status…',rows),h('div',{class:'table-wrap'},h('table',{},h('thead',{},h('tr',{},...['Concurso','Tronco','Situação','Grade',''].map(t=>h('th',{},t)))),tbody)));
  return root;
}
function openExam(exam=null,trunk='fiscal') {
  const model=exam || blankExam(uid(),trunk);selectedExam=model._id;
  if(!Object.hasOwn(ws.exams,selectedExam)){ws.exams={...ws.exams,[selectedExam]:{document:copy(model),original:copy(model),isNew:!exam,pending:null}};persist();}
  navigate('exam');
}
function setExam(mutator,rerender=false) {edit(()=>mutator(ws.exams[selectedExam].document));if(rerender)render();}
function examView() {
  const draft=ws.exams[selectedExam],exam=draft.document,locked=!!draft.pending;
  const root=h('div',{},heading(exam.short_name || 'Novo concurso','Metadados e grade por etapa. Componentes mantêm identidade própria.',button('← Concursos',()=>navigate('exams')),button('Exportar rascunho',()=>download('concurso-rascunho.json',draft))));
  if(locked)root.append(card(badge('Envio pendente','warning'),h('p',{},'O comando foi preservado. Confira ou reenvie antes de editar.'),actions(button('Conferir / reenviar',()=>run('Conferindo concurso…',sendExam),'primary'),button('Comparar com servidor',()=>run('Consultando versão atual…',async()=>{const exams=await api('exams');data.exams=exams;draft.server=exams.find(e=>e._id===exam._id) || null;persist();})))));
  if(draft.server)root.append(card(h('h2',{},'Versão atual do servidor'),h('p',{},'Sua edição continua preservada. Confira as diferenças antes de escolher uma versão.'),h('pre',{},pretty(draft.server)),button('Usar esta versão e preservar minha edição no arquivo local',async()=>{if(await confirm('Usar a versão do servidor?','A edição atual será mantida em Rascunhos e pendências.')){edit(()=>{ws.archives.push({type:'exam',saved_at:new Date().toISOString(),value:copy(draft)});ws.exams[selectedExam]={document:copy(draft.server),original:copy(draft.server),isNew:false,pending:null};});render();}})));
  const form=h('form',{onsubmit:e=>{e.preventDefault();run('Salvando concurso…',async()=>{draft.pending={kind:draft.isNew?'create':'update',body:copy(exam)};persist();await sendExam();});}});
  const meta=h('fieldset',{disabled:locked},h('legend',{},'Dados do concurso'),h('div',{class:'form-grid'},field('Nome completo',exam.title,v=>setExam(e=>e.title=v),{required:true,maxlength:300}),field('Nome curto',exam.short_name,v=>setExam(e=>e.short_name=v),{required:true,maxlength:100}),field('Instituição',exam.institution,v=>setExam(e=>e.institution=v)),field('Cargo',exam.role,v=>setExam(e=>e.role=v)),field('Tronco de carreira',exam.trunk_slug,v=>setExam(e=>e.trunk_slug=v),{options:data.templates.trunks.map(t=>[t.slug,t.name])}),field('Situação',exam.status,v=>setExam(e=>e.status=v),{options:Object.entries(lifecycle).filter(([v])=>v!=='EXCLUIDO')}),field('Bancas (separe por vírgula)',exam.exam_boards.join(', '),v=>setExam(e=>e.exam_boards=v.split(',').map(t=>t.trim()).filter(Boolean))),field('Prioridade na escada',exam.ladder_priority,v=>setExam(e=>e.ladder_priority=Number(v)),{type:'number',min:1,max:100,required:true}),field('Corte histórico (%)',exam.historical_cutoff_score,v=>setExam(e=>e.historical_cutoff_score=Number(v)),{type:'number',min:0,max:100,step:'0.01'}),field('Penalidade por erro',exam.wrong_answer_penalty,v=>setExam(e=>e.wrong_answer_penalty=Number(v)),{type:'number',min:0,max:1,step:'0.01'}),field('Previsão da prova',exam.target_date.type,v=>setExam(e=>{e.target_date=v==='EXACT_DATE'?{type:v,iso_date:''}:v==='ESTIMATED_WINDOW'?{type:v,label:'',estimated_sort_date:''}:{type:v};},true),{options:[['UNDEFINED','Sem previsão'],['EXACT_DATE','Data exata'],['ESTIMATED_WINDOW','Janela estimada']]}),exam.target_date.type==='EXACT_DATE'?field('Data da prova',exam.target_date.iso_date,v=>setExam(e=>e.target_date.iso_date=v),{type:'date',required:true}):null,exam.target_date.type==='ESTIMATED_WINDOW'?field('Janela de previsão',exam.target_date.label,v=>setExam(e=>e.target_date.label=v),{required:true}):null,exam.target_date.type==='ESTIMATED_WINDOW'?field('Data estimada para ordenação',exam.target_date.estimated_sort_date,v=>setExam(e=>e.target_date.estimated_sort_date=v),{type:'date',required:true}):null),check('Ativo na minha escada de concursos',exam.is_active_in_ladder,v=>setExam(e=>e.is_active_in_ladder=v)));
  form.append(card(meta));
  const grade=h('fieldset',{disabled:locked},h('legend',{},'Grade do edital · componentes por etapa'),h('p',{class:'intro-note'},'Cada componente associa uma disciplina do catálogo a uma etapa deste concurso. A mesma disciplina pode aparecer em várias etapas. Pesos, quantidades e assuntos são definidos por componente. A grade pode ser preparada antes do edital oficial.'));
  let addSubject=data.base.catalog.subjects.find(s=>s.status==='ACTIVE')?.subject_id || '';
  grade.append(actions(field('Disciplina do catálogo',addSubject,v=>addSubject=v,{options:data.base.catalog.subjects.filter(s=>s.status==='ACTIVE').map(s=>[s.subject_id,s.label])}),button('＋ Adicionar componente',()=>{const subject=data.base.catalog.subjects.find(s=>s.subject_id===addSubject);if(subject)setExam(e=>e.subjects.push(newComponent(subject,uid())),true);}),button('＋ Disciplina personalizada',()=>setExam(e=>e.subjects.push(newComponent({subject_id:`custom-${uid()}`,label:'Nova disciplina'},uid())),true))));
  exam.subjects.forEach((subject,index)=>{
    const update=(fn,redraw=false)=>setExam(e=>fn(e.subjects[index]),redraw);
    const box=h('section',{class:'component'},h('div',{class:'component-head'},h('div',{},h('h3',{},`${index+1}. ${subject.display_name}`),badge(subject.canonical_subject_id)),button('Remover componente',async()=>{if(await confirm('Remover este componente?','O histórico do concurso já salvo permanece preservado.'))setExam(e=>e.subjects.splice(index,1),true);},'danger')));
    box.append(h('div',{class:'form-grid'},field('Nome no edital',subject.display_name,v=>update(s=>s.display_name=v),{required:true}),field('Etapa',subject.stage,v=>update(s=>s.stage=v),{maxlength:120}),field('Grupo de conhecimentos',subject.category,v=>update(s=>s.category=v),{options:[['CONHECIMENTOS_GERAIS','Conhecimentos gerais'],['CONHECIMENTOS_ESPECIFICOS','Conhecimentos específicos'],['DISCURSIVA_ESTUDO_DE_CASO','Discursiva / estudo de caso'],['OUTROS','Outros']]}),field('Relevância',subject.relevance_tier,v=>update(s=>s.relevance_tier=v),{options:[['CORE','Central'],['COMPLEMENTARY','Complementar'],['SPECIFIC','Específica']]}),field('Peso',subject.weight,v=>update(s=>s.weight=Number(v)),{type:'number',min:0.01,max:100,step:0.01,required:true}),field('Quantidade de questões',subject.question_count,v=>update(s=>s.question_count=v===''?null:Number(v)),{type:'number',min:0,max:500,help:'Deixe vazio quando ainda não definida.'})));
    let topicChoice='';
    const available=data.base.catalog.topics.filter(t=>t.subject_id===subject.canonical_subject_id && t.status==='ACTIVE' && !subject.topics.some(old=>old.canonical_topic_id===t.topic_id));
    const appendTopic=(s,t)=>s.topics.push({local_topic_id:uid(),canonical_topic_id:t.topic_id,canonical_subject_id:s.canonical_subject_id,display_title:t.label,diff_status:'PRE_EDITAL_BASE'});
    box.append(actions(field('Adicionar assunto / subassunto','',v=>topicChoice=v,{options:[['','Selecione…'],...available.map(t=>[t.topic_id,topicPath(data.base.catalog,t)])]}),button('Adicionar assunto',()=>{const topic=available.find(t=>t.topic_id===topicChoice);if(topic)update(s=>appendTopic(s,topic),true);}),button('Adicionar todos da disciplina',async()=>{if(await confirm('Adicionar todos os assuntos?','Inclui explicitamente assuntos e subassuntos ativos desta disciplina.'))update(s=>available.forEach(t=>appendTopic(s,t)),true);})));
    subject.topics.forEach((topic,ti)=>box.append(h('div',{class:'topic-row'},h('span',{},topic.display_title,h('small',{class:'secondary'},` · ${topic.canonical_topic_id}`)),h('select',{'aria-label':`Situação de ${topic.display_title}`,onchange:e=>update(s=>s.topics[ti].diff_status=e.target.value)},...Object.entries({PRE_EDITAL_BASE:'Pré-edital',OFICIAL_CONFIRMADO:'Oficial confirmado',OFICIAL_NOVO:'Oficial novo',ARQUIVADO:'Arquivado'}).map(([v,t])=>h('option',{value:v,selected:v===topic.diff_status},t))),button('Remover',()=>update(s=>s.topics.splice(ti,1),true)))));
    let customName='';box.append(actions(field('Assunto personalizado','',v=>customName=v),button('Adicionar personalizado',()=>{if(customName.trim())update(s=>appendTopic(s,{topic_id:`custom-${uid()}`,label:customName.trim()}),true);})));grade.append(box);
  });
  form.append(card(grade),actions(h('button',{type:'submit',class:'primary',disabled:locked},'Salvar concurso e grade'),button('Publicar esta grade como edital',async()=>{if(locked)return;if(await confirm('Confirmar publicação do edital?','A grade ativa será comparada ao histórico e registrada como conteúdo oficial deste concurso. Confira todos os componentes antes de prosseguir.'))run('Publicando edital…',async()=>{if(draft.isNew)throw new Error('Salve o concurso antes de publicar o edital.');draft.pending={kind:'publish',body:{expected_revision:exam.revision,published_topics:exam.subjects.flatMap(s=>s.topics.filter(t=>t.diff_status!=='ARQUIVADO').map(t=>({canonical_topic_id:t.canonical_topic_id,canonical_subject_id:t.canonical_subject_id,display_title:t.display_title})))}};persist();await sendExam();});}),button('Arquivar concurso',async()=>{if(!locked && await confirm('Arquivar concurso?','Ele sai da escada ativa. A grade e o histórico continuam disponíveis.')){setExam(e=>{e.status='ARQUIVADO';e.is_active_in_ladder=false;});run('Arquivando concurso…',async()=>{draft.pending={kind:draft.isNew?'create':'update',body:copy(exam)};persist();await sendExam();});}},'danger')));
  root.append(form);return root;
}
async function sendExam() {
  const draft=ws.exams[selectedExam],pending=draft.pending;if(!pending)return;
  if(!['create','update','publish'].includes(pending.kind))throw new Error('Comando de concurso inválido.');
  const id=draft.document._id;if(!/^[A-Za-z0-9_-]{1,100}$/.test(id))throw new Error('Identidade de concurso inválida.');
  const path=pending.kind==='create'?'exams':`exams/${encodeURIComponent(id)}${pending.kind==='publish'?'/publish-edital':''}`;
  let result;
  try {result=await api(path,{method:pending.kind==='update'?'PUT':'POST',body:pending.body});}
  catch(error){if(error.status===400){ws.archives.push({type:'rejected-exam-command',value:copy(pending),saved_at:new Date().toISOString()});draft.pending=null;persist();}throw error;}
  const expected=pending.kind==='publish'?pending.body.expected_revision:pending.body.revision;
  if(result._id!==id || result.revision!==expected+1 || result.owner_user_id!==me.user_id)throw new Error('O recibo do concurso não corresponde ao comando. A pendência foi preservada.');
  ws.exams[selectedExam]={document:copy(result),original:copy(result),isNew:false,pending:null};persist();data.exams=await api('exams');notice('Concurso e grade confirmados no servidor.','');
}

function trunksView() {
  const root=h('div',{},heading('Troncos de carreira','Perfis editoriais que conectam disciplinas e oportunidades.',button('Propor alteração no catálogo',()=>startCatalog(),'primary'))),grid=h('div',{class:'cards'}),rows=[];
  for(const trunk of data.base.catalog.trunks){
    const subjects=data.base.catalog.subjects.filter(s=>s.trunk_links.some(l=>l.trunk_id===trunk.trunk_id));
    const linkedExams=data.exams.filter(exam=>exam.trunk_slug===trunk.trunk_id);
    const box=card(badge(trunk.status==='ACTIVE'?'Ativo':'Desativado',trunk.status==='ACTIVE'?'good':''),h('h2',{},trunk.label),h('p',{},`${subjects.length} disciplinas relacionadas`),h('small',{},subjects.slice(0,4).map(s=>s.label).join(' · ')),h('p',{},`${linkedExams.length} concurso(s) da sua conta neste tronco`),actions(...linkedExams.map(exam=>button(`${exam.short_name} → grade`,()=>openExam(exam)))),h('hr',{class:'divider'}),actions(button('Criar concurso',()=>openExam(null,trunk.trunk_id)),button('Editar proposta',()=>editEntity('trunks',trunk.trunk_id))));
    grid.append(box);rows.push([box,`${trunk.label} ${trunk.trunk_id} ${trunk.aliases.join(' ')}`]);
  }
  root.append(searchable('Buscar tronco…',rows),grid);return root;
}
function subjectsView() {
  const root=h('div',{},heading('Disciplinas e assuntos','Identidades compartilhadas, assuntos e subassuntos do catálogo.',button('Propor nova disciplina',()=>editEntity('subjects',null),'primary'))),list=card(),rows=[];
  for(const subject of data.base.catalog.subjects){
    const topics=orderedTopics(data.base.catalog,subject.subject_id);
    const detail=h('details',{},h('summary',{},h('strong',{},subject.label),' ',badge(subject.subject_id),badge(`${topics.length} assuntos / subassuntos`)),h('p',{},subject.scope_note),h('p',{class:'review-meta'},`Troncos relacionados: ${subject.trunk_links.map(link=>data.base.catalog.trunks.find(t=>t.trunk_id===link.trunk_id)?.label || link.trunk_id).join(' · ') || 'Nenhum vínculo editorial'}`),h('small',{},`Aliases: ${subject.aliases.join('; ') || '—'}`),h('div',{class:'actions'},button('Editar proposta de disciplina',()=>editEntity('subjects',subject.subject_id)),button('Adicionar assunto na proposta',()=>editEntity('topics',null,subject.subject_id))));
    const uses=data.exams.flatMap(exam=>exam.subjects.filter(component=>component.canonical_subject_id===subject.subject_id).map(component=>[exam,component]));
    detail.append(h('p',{class:'review-meta'},'Componentes nas grades da sua conta:'),uses.length?actions(...uses.map(([exam,component])=>button(`${exam.short_name} · ${component.stage || 'etapa não definida'}`,()=>openExam(exam)))):h('small',{},'Esta disciplina ainda não foi adicionada às suas grades.'));
    for(const topic of topics)detail.append(h('div',{class:'catalog-row'},h('div',{},h('strong',{},topicPath(data.base.catalog,topic)),h('small',{},`${topic.topic_id}${topic.parent_topic_id?' · subassunto de '+(data.base.catalog.topics.find(t=>t.topic_id===topic.parent_topic_id)?.label || topic.parent_topic_id):''}`)),button('Propor edição',()=>editEntity('topics',topic.topic_id))));
    const row=h('section',{class:'component'},detail);list.append(row);rows.push([row,`${subject.label} ${subject.subject_id} ${subject.aliases.join(' ')} ${topics.map(t=>t.label).join(' ')}`]);
  }
  root.append(searchable('Buscar disciplina, alias ou assunto…',rows),list);return root;
}
function questionsView() {
  const root=h('div',{},heading('Banco de questões','Busque o acervo e revise questões mantendo seu histórico.',button('＋ Inserir questões',()=>navigate('import'),'primary')));
  const searchForm=h('form',{class:'toolbar',onsubmit:e=>{e.preventDefault();fetchQuestions();}},h('input',{type:'search',placeholder:'Buscar no enunciado…','aria-label':'Buscar no enunciado',value:questionSearch,oninput:e=>questionSearch=e.target.value}),h('select',{'aria-label':'Tipo de questão',onchange:e=>questionType=e.target.value},h('option',{value:''},'Todos os tipos'),...Object.entries(kinds).map(([v,t])=>h('option',{value:v,selected:questionType===v},t))),h('button',{type:'submit'},'Buscar'));
  root.append(searchForm);
  if(!questionPage){root.append(card(h('p',{},'Carregue uma página do banco para começar.'),button('Carregar questões',()=>fetchQuestions(),'primary')));return root;}
  const tbody=h('tbody');
  for(const q of questionPage.items)tbody.append(h('tr',{},h('td',{},h('p',{},q.question.length>220?q.question.slice(0,220)+'…':q.question),h('span',{class:'secondary'},q._id.$oid)),h('td',{},badge(kinds[q.question_type] || 'Legado'),h('span',{class:'secondary'},q.source || 'Origem não informada')),h('td',{},badge(q.schema_version===2?'Schema v2':'Legado',q.schema_version===2?'good':'warning'),h('span',{class:'secondary'},validityPresentation(q.validity,new Date().toLocaleDateString('en-CA',{timeZone:'America/Sao_Paulo'})))),h('td',{},button('Abrir →',()=>run('Abrindo questão…',async()=>{selectedQuestion=q._id.$oid;if(!ws.questionEdits[selectedQuestion]){const document=await api(`admin/questions/v2/${selectedQuestion}`);ws.questionEdits[selectedQuestion]={original:copy(document),text:pretty(document),operationId:uid(),preview:null,submitted:false,result:null};persist();}current='question';})))));
  root.append(h('div',{class:'count'},`${questionPage.items.length} questões nesta página`),questionPage.items.length?h('div',{class:'table-wrap'},h('table',{},h('thead',{},h('tr',{},...['Enunciado','Tipo / origem','Contrato / validade',''].map(t=>h('th',{},t)))),tbody)):h('p',{class:'empty'},'Nenhuma questão encontrada.'),h('div',{class:'actions'},button('Recarregar início',()=>fetchQuestions()),questionPage.after?button('Próxima página →',()=>fetchQuestions(questionPage.after)):null));return root;
}
function fetchQuestions(after=null) {run('Consultando questões…',async()=>{const query=new URLSearchParams({search:questionSearch,question_type:questionType,limit:'25'});if(after)query.set('after',after);questionPage=await api(`admin/questions?${query}`);notice('');});}
function questionView() {
  const draft=ws.questionEdits[selectedQuestion],original=draft.original;
  const root=h('div',{},heading('Revisar questão',selectedQuestion,button('← Banco de questões',()=>navigate('questions')),button('Exportar contrato',()=>download('questao-v2.json',original))));
  if(draft.submitted && !draft.result)root.append(card(badge('Edição aguardando confirmação','warning'),h('p',{},'Os comandos permanecem congelados. Se houver conflito de revisão, compare a versão atual antes de continuar.'),button('Comparar versão do servidor',()=>run('Conferindo a questão…',async()=>{draft.server=await api(`admin/questions/v2/${selectedQuestion}`);persist();}))));
  if(draft.server)root.append(card(h('h2',{},'Versão atual no servidor'),h('pre',{},pretty(draft.server)),button('Usar versão do servidor e preservar minha edição',async()=>{if(await confirm('Continuar a partir do servidor?','A edição e os comandos atuais ficarão preservados em Rascunhos e pendências.')){edit(()=>{ws.archives.push({type:'question-edit',saved_at:new Date().toISOString(),value:copy(draft)});ws.questionEdits[selectedQuestion]={original:copy(draft.server),text:pretty(draft.server),operationId:uid(),preview:null,submitted:false,result:null};});render();}})));
  if(original.schema_version!==2 && !draft.converted){root.append(card(badge('Registro legado','warning'),h('h2',{},original.question),h('p',{},'Converta explicitamente para editar no contrato v2, mantendo o ID, o documento anterior e seus campos adicionais. Gabarito e classificação precisam ser conferidos; dados ausentes não serão inventados.'),button('Preparar conversão e editar',()=>{edit(()=>{const q=legacyQuestionDraft(original,data.base.catalog,me.user_id,uid());draft.text=pretty(q);draft.converted=true;draft.operationId=uid();draft.classificationChoice={subjectId:original.canonical_subject_id || '',topicId:original.canonical_topic_id || '',confirmed:!original.canonical_topic_id};});render();},'primary'),h('pre',{},pretty(original))));return root;}
  let parsed;
  try{parsed=JSON.parse(draft.text);if(!parsed || !Array.isArray(parsed.answers) || !Array.isArray(parsed.classification?.links))throw new Error('Preserve alternativas e os blocos do contrato.');}
  catch(error){root.append(card(h('h2',{},'Corrigir o contrato JSON'),h('p',{},error.message),field('Documento JSON em correção',draft.text,value=>edit(()=>{if(!draft.submitted){draft.text=value;draft.preview=null;draft.operationId=uid();}}),{multiline:true,rows:20,code:true,disabled:draft.submitted}),button('Reabrir formulário',render)));return root;}
  const raw=field('Documento JSON',draft.text,v=>edit(()=>{if(draft.submitted)return;draft.text=v;draft.preview=null;draft.operationId=uid();}),{multiline:true,rows:22,code:true,disabled:draft.submitted});
  const update=fn=>{if(draft.submitted)return;edit(()=>{const q=JSON.parse(draft.text);fn(q);if(q.question_type!=='OPEN_ENDED')q.correct_answers=q.correct_answer_indices.map(index=>q.answers[index]);draft.text=pretty(q);draft.preview=null;draft.operationId=uid();raw.querySelector('textarea').value=draft.text;});};
  const fields=h('fieldset',{disabled:draft.submitted},h('legend',{},'Conteúdo da questão'),badge(kinds[parsed.question_type]),field('Enunciado',parsed.question,v=>update(q=>q.question=v),{multiline:true,rows:4,maxlength:20000}));
  if(parsed.question_type!=='OPEN_ENDED'){
    parsed.answers.forEach((answer,index)=>fields.append(field(`Alternativa ${String.fromCharCode(65+index)}`,answer,v=>update(q=>q.answers[index]=v))));
    fields.append(field('Gabarito',parsed.correct_answer_indices.length?String(parsed.correct_answer_indices[0]):'',v=>update(q=>q.correct_answer_indices=v===''?[]:[Number(v)]),{options:[['','Selecione…'],...parsed.answers.map((_,i)=>[String(i),`Alternativa ${String.fromCharCode(65+i)}`])]}));
  }else fields.append(field('Correção e rubrica (JSON)',pretty(parsed.grading),v=>{try{update(q=>q.grading=JSON.parse(v));}catch(error){notice(error.message,'error');}},{multiline:true,rows:12}));
  const primary=parsed.classification.links.find(link=>link.role==='PRIMARY');
  const choice=draft.classificationChoice || {subjectId:primary?.subject_id || '',topicId:primary?.topic_id || '',confirmed:true};
  const choose=(fn)=>{if(draft.submitted)return;edit(()=>{fn();choice.confirmed=false;draft.classificationChoice=choice;draft.preview=null;});render();};
  fields.append(h('h3',{},'Classificação principal'),field('Disciplina da questão',choice.subjectId,value=>choose(()=>{choice.subjectId=value;choice.topicId='';}),{options:[['','Sem classificação'],...data.base.catalog.subjects.filter(s=>s.status==='ACTIVE' || s.subject_id===choice.subjectId).map(s=>[s.subject_id,s.status==='ACTIVE'?s.label:`Desativada · ${s.label}`])]}),field('Assunto da questão',choice.topicId,value=>choose(()=>choice.topicId=value),{options:[['','Selecione…'],...data.base.catalog.topics.filter(t=>t.subject_id===choice.subjectId && (t.status==='ACTIVE' || t.topic_id===choice.topicId)).map(t=>[t.topic_id,t.status==='ACTIVE'?topicPath(data.base.catalog,t):`Desativado · ${topicPath(data.base.catalog,t)}`])]}),check('Aplicar e confirmar a classificação principal selecionada',false,value=>{if(!value)return;if(choice.subjectId && !choice.topicId){notice('Selecione um assunto da disciplina.','error');return;}update(q=>{const links=q.classification.links.filter(link=>link.role!=='PRIMARY' && link.topic_id!==choice.topicId);if(choice.subjectId)links.unshift({link_id:uid(),subject_id:choice.subjectId,topic_id:choice.topicId,role:'PRIMARY',coverage:'DIRECT',provenance:manualProvenance(me.user_id,'Classificação principal revisada explicitamente na administração web.')});q.classification={revision:q.classification.revision,catalog_version:data.base.catalog.catalog_version,state:choice.subjectId?'CONFIRMED':links.length?'PARTIAL':'UNCLASSIFIED',links};q.canonical_subject_id=choice.subjectId || null;q.canonical_topic_id=choice.topicId || null;});choice.confirmed=true;draft.classificationChoice=choice;persist();notice('Classificação preparada para conferência na prévia.');}));
  const validate=button('Validar edição',()=>run('Validando contrato…',async()=>{if(draft.classificationChoice && !draft.classificationChoice.confirmed)throw new Error('Confira e aplique a classificação selecionada antes de validar.');draft.preview=await api(`admin/questions/${selectedQuestion}/prepare-edit`,{method:'POST',body:{operation_id:draft.operationId,expected_revision:original.revision || 0,document:JSON.parse(draft.text)}});persist();notice('Prévia validada. Confira o contrato final antes de confirmar.');}),'primary');validate.disabled=draft.submitted;
  root.append(card(h('p',{},'A prévia calcula o hash e as revisões. Mudanças de conteúdo colocam a validade em revisão.'),fields,h('details',{},h('summary',{},'Metadados e contrato completo'),raw,button('Atualizar formulário a partir do JSON',()=>{try{JSON.parse(draft.text);render();}catch(error){notice(error.message,'error');}})),validate));
  if(draft.preview){let reviewed=false;const send=button(draft.submitted?'Conferir / reenviar edição':'Confirmar edição',()=>run('Gravando edição…',async()=>{draft.submitted=true;persist();const result=await api('admin/questions/v2',{method:'POST',body:draft.preview.command});if(!['applied','already_applied'].includes(result.status)||result.revision!==(original.revision || 0)+1)throw new Error('Recibo de revisão divergente.');draft.result=result;persist();notice('Edição confirmada no servidor.');}),'primary');send.disabled=true;root.append(card(draft.preview.content_changed?badge(`Validade: ${draft.preview.command.document.validity.status}`,'warning'):badge('Conteúdo preservado','good'),h('pre',{},pretty(draft.preview.command.document)),check('Revisei o contrato final, a classificação e os metadados.',reviewed,value=>{reviewed=value;send.disabled=!value || !!draft.result;}),send));}
  if(draft.result)root.append(card(badge('Edição confirmada','good'),h('p',{},`Revisão ${draft.result.revision}`),button('Carregar revisão atual',()=>run('Carregando revisão…',async()=>{ws.archives.push({type:'question-edit',saved_at:new Date().toISOString(),value:copy(draft)});const document=await api(`admin/questions/v2/${selectedQuestion}`);ws.questionEdits[selectedQuestion]={original:copy(document),text:pretty(document),operationId:uid(),preview:null,submitted:false,result:null};persist();}))));return root;
}

function importView() {
  ws.import ||= freshImport(uid(),uid());
  const draft=ws.import,locked=draft.submitted;
  const update=(fn,redraw=false)=>{if(locked)return;edit(()=>{fn();draft.preview=null;draft.result=null;draft.operationId=uid();draft.aiOperationId=uid();});const originBox=$('#origin-confirmation input');if(originBox)originBox.checked=!!draft.form.confirmOrigin;if(redraw)render();};
  const root=h('div',{},heading('Inserir questões','Individual ou em lote, com revisão e assistência opcional de IA.',button('Exportar rascunho',()=>download('cogniva-rascunhos.json',exportWorkspace(me.user_id,ws)))));
  root.append(h('div',{class:'tabs'},...[['single','Individual'],['bulk','Lote / IA']].map(([v,t])=>{const b=button(t,()=>update(()=>draft.mode=v,true),v===draft.mode?'active':'');b.disabled=locked || (v==='single' && !!draft.assistanceId);return b;})));
  const inputSection=card();const inputs=h('fieldset',{disabled:locked});
  if(draft.mode==='single'){
    const f=draft.form;
    inputs.append(h('legend',{},'1. Conteúdo e gabarito'),h('p',{class:'intro-note'},'Transcreva a questão existente e registre a prova de origem abaixo. Para alterar uma questão já salva no Cogniva, abra o Banco de questões.'),h('div',{class:'form-grid'},field('Tipo',f.kind,v=>update(()=>{f.kind=v;f.correctIndex='';},true),{options:Object.entries(kinds)}),field('Origem',f.originKind,v=>update(()=>{f.originKind=v;f.confirmOrigin=false;},true),{options:[['LEGACY_IMPORT','Transcrição / importação'],['OFFICIAL_EXAM','Questão de prova oficial'],['AUTHOR_CREATED','Autoria própria']]}),field('Enunciado',f.question,v=>update(()=>f.question=v),{multiline:true,rows:4,wide:true,maxlength:20000})));
    if(f.kind!=='OPEN_ENDED'){
      const options=f.kind==='TRUE_FALSE'?['Certo','Errado']:f.answers;
      options.forEach((answer,index)=>{if(f.kind==='MULTIPLE_CHOICE')inputs.append(field(`Alternativa ${String.fromCharCode(65+index)}`,answer,v=>update(()=>f.answers[index]=v),{maxlength:20000}));});
      inputs.append(field('Gabarito',f.correctIndex,v=>update(()=>f.correctIndex=v),{options:[['','Selecione o gabarito…'],...options.map((a,i)=>[String(i),f.kind==='TRUE_FALSE'?a:`Alternativa ${String.fromCharCode(65+i)}`])]}));
    }else{inputs.append(field('Resposta de referência',f.reference,v=>update(()=>f.reference=v),{multiline:true,rows:4}),field('Critérios da rubrica (array JSON)',f.criteria,v=>update(()=>f.criteria=v),{multiline:true,rows:5,help:'Ex.: [{"criterion_id":"c1","description":"Explicar corretamente o conceito","max_points":100,"essential":true}]. Os pontos devem somar 100; corte 60, com revisão humana.'}));}
    inputs.append(h('hr',{class:'divider'}),h('h2',{},'2. Prova de origem e referência'),h('p',{class:'intro-note'},'A prova de origem identifica de onde veio a questão. Ela não limita seu uso a um concurso da sua grade. Na origem oficial, informe prova, instituição, banca, ano e fonte.'),h('div',{class:'form-grid'},
      ...[['Identificação da prova / cargo / caderno','sourceExamCode'],['Instituição / órgão','institution'],['Banca organizadora','organizer'],['Número da questão','questionNumber']].map(([label,key])=>field(label,f[key],v=>update(()=>{f[key]=v;f.confirmOrigin=false;}),{maxlength:300})),
      field('Ano da prova',f.examYear,v=>update(()=>{f.examYear=v;f.confirmOrigin=false;}),{type:'number',min:1900,max:2200}),field('Nível / dificuldade (opcional)',f.level,v=>update(()=>f.level=v),{maxlength:300}),
      field('Endereço da fonte (URL)',f.sourceUri,v=>update(()=>{f.sourceUri=v;f.confirmOrigin=false;}),{type:'url',wide:true,help:'Link do caderno, gabarito ou página de referência.'}),field('Título do documento / fonte',f.sourceTitle,v=>update(()=>{f.sourceTitle=v;f.confirmOrigin=false;}),{maxlength:300}),field('Página / localização no documento',f.sourceLocator,v=>update(()=>{f.sourceLocator=v;f.confirmOrigin=false;}),{maxlength:300}),field('Data em que consultei a fonte',f.sourceDate,v=>update(()=>{f.sourceDate=v;f.confirmOrigin=false;}),{type:'date'})),
      h('div',{id:'origin-confirmation'},check('Consultei a fonte e conferi a origem informada.',!!f.confirmOrigin,v=>update(()=>f.confirmOrigin=v))),h('p',{class:'status-line'},'Sem esta conferência, a origem transcrita fica pendente. Origem conferida não significa validade atual do conteúdo.'),
      h('hr',{class:'divider'}),h('h2',{},'3. Classificação por conteúdo'),h('p',{class:'intro-note'},'Disciplina → Assunto → Subassunto. Escolha o conteúdo mais específico cobrado; a classificação é compartilhada entre concursos.'));
    inputs.append(h('div',{class:'form-grid'},field('Disciplina (opcional)',f.subjectId,v=>update(()=>{f.subjectId=v;f.topicId='';f.confirmClassification=false;},true),{options:[['','Sem classificação'],...data.base.catalog.subjects.filter(s=>s.status==='ACTIVE').map(s=>[s.subject_id,s.label])]}),field('Assunto / subassunto',f.topicId,v=>update(()=>{f.topicId=v;f.confirmClassification=false;},true),{options:[['','Selecione…'],...data.base.catalog.topics.filter(t=>t.subject_id===f.subjectId && t.status==='ACTIVE').map(t=>[t.topic_id,topicPath(data.base.catalog,t)])]})),check('Conferi e confirmo este vínculo principal direto.',f.confirmClassification,v=>update(()=>f.confirmClassification=v),!f.topicId));
    inputs.append(h('hr',{class:'divider'}),h('h2',{},'4. Comentário da resolução'),field('Explicação / justificativa do gabarito (opcional)',f.explanation,v=>update(()=>f.explanation=v),{multiline:true,rows:4,maxlength:20000,help:'Comentário que acompanha a resolução da questão.'}));
  }else{
    inputs.append(field('JSON / JSONL ou material para a IA',draft.source,v=>update(()=>draft.source=v),{multiline:true,rows:14,help:'Até 50 questões / 512 KiB. Gabaritos usam índices a partir de zero.'}));
    const upload=h('input',{type:'file',accept:'.json,.jsonl,.txt,application/json,text/plain','aria-label':'Importar arquivo de questões',onchange:async e=>{const file=e.target.files[0];if(!file)return;try{if(file.size>512*1024)throw new Error('Arquivo excede 512 KiB.');const text=(await file.text()).replace(/^\uFEFF/,'');update(()=>{draft.source=text;draft.assistanceId=null;},true);}catch(error){notice(error.message,'error');}}});
    inputs.append(upload,h('p',{class:'status-line'},'Aceita objetos simplificados ou documentos v2 completos com identidade nova.'));
    if(!draft.assistanceId)inputs.append(h('hr',{class:'divider'}),h('h3',{},'Assistência opcional · Grok 4.7'),h('p',{},'O botão envia o material acima à xAI. A resposta será um rascunho editável; nenhuma questão será inserida automaticamente.'),field('Como usar a IA',draft.aiMode,v=>update(()=>draft.aiMode=v),{options:[['STRUCTURE','Estruturar questões de um texto'],['GENERATE','Gerar questões autorais a partir do pedido']]}),(()=>{const b=button('Preparar com IA',()=>run('Preparando rascunhos com IA…',async()=>{persist();const result=await api('admin/questions/import/assist',{method:'POST',body:{operation_id:draft.aiOperationId,mode:draft.aiMode,text:draft.source}});draft.source=pretty(result.items);draft.assistanceId=result.assistance_id;draft.preview=null;persist();notice('A IA preparou rascunhos. Confira e edite o conteúdo antes de validar.');}));b.disabled=!data.capabilities.ai_available;return b;})(),!data.capabilities.ai_available?h('small',{},'IA não configurada no servidor. A inserção manual continua disponível.'):null);
    else inputs.append(badge('Rascunho assistido por IA','warning'),h('p',{},'Você pode editar o JSON. A proveniência de IA será preservada; classificação e validade não serão confirmadas pelo modelo.'));
  }
  inputs.append(h('hr',{class:'divider'}),button('Validar e revisar questões',()=>run('Validando o lote…',async()=>{const items=draft.mode==='single'?[objectiveDraft(draft.form)]:parseQuestions(draft.source);persist();draft.preview=await api('admin/questions/import/preview',{method:'POST',body:{operation_id:draft.operationId,items,assistance_id:draft.assistanceId}});persist();notice(draft.preview.ready?'Contrato validado. Confira todas as questões abaixo.':'Corrija as questões indicadas antes de enviar.',draft.preview.ready?'':'warning');}),'primary'));inputSection.append(inputs,h('p',{class:'status-line'},'Novas questões simplificadas têm validade desconhecida. Classificação só é confirmada por decisão explícita.'));root.append(inputSection);
  if(draft.preview){
    root.append(h('h2',{},'Revisão antes de inserir'));
    draft.preview.items.forEach((item,index)=>{if(!item.valid){root.append(card(badge(`Questão ${index+1}`,'danger'),h('p',{},item.message)));return;}
      const q=item.command.document;
      root.append(card(h('div',{class:'question-review'},badge(`Questão ${index+1}`),badge(kinds[q.question_type]),h('h3',{},q.question),h('ol',{type:'A'},...q.answers.map(a=>h('li',{},a))),h('strong',{},q.correct_answers.length?`Gabarito: ${q.correct_answers.join(', ')}`:'Discursiva · correção por rubrica'),q.question_type==='OPEN_ENDED'?h('pre',{},pretty(q.grading)):null,h('p',{class:'review-meta'},`Classificação: ${q.classification.state} · Validade: ${statuses[q.validity.status]} · Contexto: ${q.validity.scope}${q.validity.context_date?' / '+q.validity.context_date:''}`),h('p',{class:'review-meta'},`Vínculos: ${q.classification.links.map(l=>`${l.subject_id}/${l.topic_id} (${l.role}, ${l.coverage})`).join('; ')||'Sem classificação confirmada'}`),h('p',{class:'review-meta'},`Origem: ${q.origins.map(o=>`${o.kind} · ${o.verification} · ${[o.source_exam_code,o.institution,o.organizer,o.exam_year,o.question_number?'Questão '+o.question_number:null].filter(Boolean).join(' · ') || 'Sem identificação da prova'}${o.source_reference?' · Fonte: '+o.source_reference.title+' ('+o.source_reference.uri+')':''}`).join('; ')}`),h('details',{},h('summary',{},'Ver contrato completo'),h('pre',{},pretty(q))))));
    });
    let reviewed=false;
    const commit=button(locked?'Conferir / reenviar os mesmos comandos':`Confirmar inserção de ${draft.preview.items.length} questão(ões)`,()=>run('Inserindo questões…',async()=>{draft.submitted=true;persist();draft.result=verifyReceipt(draft.preview,await api('admin/questions/import/commit',{method:'POST',body:{reviewed:true,commands:draft.preview.items.map(item=>item.command)}}));persist();notice(draft.result.complete?'Todas as questões foram confirmadas no servidor.':'Alguns itens ainda não foram confirmados. Confira os resultados e reenvie os mesmos comandos.',draft.result.complete?'':'warning');}),'primary');
    commit.disabled=true;
    root.append(card(check('Revisei enunciados, alternativas, gabaritos, classificação, origem e validade.',reviewed,value=>{reviewed=value;commit.disabled=!value || !draft.preview.ready || draft.result?.complete===true;}),commit,locked && !draft.result?.complete?h('p',{class:'status-line'},'O envio foi iniciado. A edição fica bloqueada até a confirmação; a retentativa não duplica itens já gravados.'):null));
  }
  if(draft.result)root.append(card(h('h2',{},`${draft.result.items.filter(r=>r.saved).length} de ${draft.result.items.length} questões confirmadas`),...draft.result.items.map((item,i)=>h('div',{class:'result-item'},badge(`${i+1}`,item.saved?'good':'danger'),item.saved?`Confirmada · ${item.id.$oid}`:item.message))));
  const next=button('Iniciar novo cadastro',async()=>{if(await confirm('Iniciar novo cadastro?','O rascunho atual será guardado no arquivo local de Rascunhos e pendências.')){edit(()=>{ws.archives.push({type:'question-import',saved_at:new Date().toISOString(),value:copy(draft)});ws.import=freshImport(uid(),uid());});render();}});next.disabled=locked && !draft.result?.complete;root.append(next);return root;
}

function ensureCatalog() {
  if(ws.catalog)return;
  const catalog=copy(data.base.catalog);catalog.catalog_version++;catalog.updated_at=new Date().toLocaleDateString('en-CA',{timeZone:'America/Sao_Paulo'});
  if(!catalog.sources.some(s=>s.source_id==='WEB_EDITOR'))catalog.sources.push({source_id:'WEB_EDITOR',kind:'EDITORIAL_PROPOSAL',reference:'Cogniva Admin · proposta editorial',note:'Alterações propostas pelo responsável autenticado, sujeitas à revisão e ativação versionada.'});
  ws.catalog={draft_id:uid(),revision:0,base_catalog_sha256:data.base.sha256,catalog,status:'DRAFT',validation:null,pending:null};persist();
}
function startCatalog(){ensureCatalog();navigate('catalog');}
function catalogLocked(draft){return !!(draft.pending || draft.activationPending || draft.activationResult || draft.catalog.catalog_version<=data.base.catalog.catalog_version);}
function catalogActivationPanel(draft){
  const box=card(h('h2',{},'Ativar no catálogo global'));
  const newRevision=button('Iniciar nova revisão',async()=>{if(draft.activationPending || draft.pending){notice('Confira os comandos pendentes antes de iniciar outra revisão.','warning');return;}if(await confirm('Iniciar uma revisão da versão ativa?','A edição atual será preservada no arquivo local.')){edit(()=>{ws.archives.push({type:'catalog',saved_at:new Date().toISOString(),value:copy(draft)});delete ws.catalog;delete ws.entityEditing;ensureCatalog();});render();}});
  if(draft.activationResult || (draft.catalog.catalog_version<=data.base.catalog.catalog_version && !draft.activationPending)){box.append(badge(`Catálogo ativo v${data.base.catalog.catalog_version}`,'good'),h('p',{},'As próximas consultas usam a versão ativa. O Android recebe o catálogo na sincronização.'),newRevision);return box;}
  box.append(h('p',{},'A ativação passa a valer para todos os usuários, sem reiniciar o servidor. IDs e versões anteriores são preservados; renomeações não reescrevem questões nem tentativas.'),h('p',{class:'status-line'},'A automação restrita ao catálogo legado v1 será pausada em versões posteriores. O acervo, as cotas e a fila são preservados; revisão manual continua disponível.'));
  let reviewed=false;
  const activate=button(draft.activationPending?'Conferir / reenviar ativação':'Ativar catálogo global',()=>run('Ativando catálogo…',async()=>{
    if(!draft.activationPending){
      if(draft.status!=='REVIEW_READY' || draft.dirty)throw new Error('Salve primeiro a revisão atual como pronta para revisão.');
      if((draft.activationReason || '').trim().length<10)throw new Error('Descreva o motivo da ativação com pelo menos 10 caracteres.');
      draft.activationPending={operation_id:uid(),draft_id:draft.draft_id,expected_draft_revision:draft.revision,expected_catalog_hash:draft.base_catalog_sha256,reason:draft.activationReason || '',reviewed:true};persist();
    }
    const command=draft.activationPending;
    let result;try{result=await api('admin/catalog/activate',{method:'POST',body:command});}catch(error){if([400,409].includes(error.status)){const check=await api(`admin/catalog/activations/${command.operation_id}`).catch(()=>null);if(check?.status==='absent'){ws.archives.push({type:'catalog-activation-rejected',saved_at:new Date().toISOString(),value:copy(command)});draft.activationPending=null;persist();}}throw error;}
    if(result.operation_id!==command.operation_id || result.activated_version!==draft.catalog.catalog_version || !['applied','already_applied'].includes(result.status))throw new Error('Recibo de ativação divergente. O comando foi preservado.');
    draft.activationResult=result;draft.activationPending=null;persist();await loadData();notice(`Catálogo v${result.activated_version} ativado e confirmado no servidor.`);
  }),'primary');
  activate.disabled=true;
  box.append(field('Motivo da ativação',draft.activationReason || '',value=>{if(!draft.activationPending)edit(()=>draft.activationReason=value);},{multiline:true,rows:2,disabled:!!draft.activationPending,help:'Descreva a revisão realizada (mínimo de 10 caracteres).'}),check('Revisei os nomes, assuntos, vínculos e situações e confirmo a ativação global.',reviewed,value=>{reviewed=value;activate.disabled=!value || (!draft.activationPending && (draft.status!=='REVIEW_READY' || draft.dirty===true || !!draft.pending));}),activate);
  if(draft.status!=='REVIEW_READY' || draft.dirty)box.append(h('p',{class:'status-line'},'Use “Salvar como pronta para revisão” antes de ativar.'));
  if(draft.activationPending)box.append(button('Conferir situação da ativação',()=>run('Conferindo ativação…',async()=>{draft.activationCheck=await api(`admin/catalog/activations/${draft.activationPending.operation_id}`);persist();await loadData();})),draft.activationCheck?h('pre',{},pretty(draft.activationCheck)):null);
  if(draft.activationCheck && draft.activationCheck.status!=='applied' && draft.activationCheck.current_hash!==draft.activationPending?.expected_catalog_hash)box.append(button('Preservar comando não aplicado e iniciar nova revisão',async()=>{if(await confirm('Continuar com a versão ativa?','O servidor confirmou que este comando não foi aplicado e que outra versão já está ativa. O comando original será preservado no arquivo local.')){edit(()=>{ws.archives.push({type:'catalog-activation-not-applied',saved_at:new Date().toISOString(),value:copy(draft)});delete ws.catalog;ensureCatalog();});render();}}));
  if(!draft.activationPending)box.append(newRevision);
  return box;
}
function editEntity(group,id,subjectId='') {ensureCatalog();if(catalogLocked(ws.catalog)){notice('Inicie uma nova revisão ou resolva a ativação pendente antes de editar.','warning');return;}entityGroup=group;const key=group==='trunks'?'trunk_id':group==='subjects'?'subject_id':'topic_id';const existing=ws.catalog.catalog[group].find(row=>row[key]===id);selectedEntity=existing?copy(existing):group==='trunks'?{trunk_id:'',label:'',aliases:[],source_ids:['WEB_EDITOR'],status:'ACTIVE'}:group==='subjects'?{subject_id:'',label:'',aliases:[],scope_note:'',status:'ACTIVE',revision:1,source_ids:['WEB_EDITOR'],trunk_links:[]}:{topic_id:'',subject_id:subjectId,parent_topic_id:null,kind:'TOPIC',label:'',aliases:[],status:'ACTIVE',revision:1,source_ids:['WEB_EDITOR']};selectedEntity._existingId=id;ws.entityEditing={group,row:selectedEntity,aliasText:selectedEntity.aliases.join('\n')};persist();catalogTab=group;navigate('entity');}
function entityView() {
  const group=entityGroup,row=selectedEntity,key=group==='trunks'?'trunk_id':group==='subjects'?'subject_id':'topic_id',catalog=ws.catalog.catalog,prior=catalog[group].find(r=>r[key]===row._existingId),baseline=data.base.catalog[group].some(r=>r[key]===row._existingId),locked=catalogLocked(ws.catalog);
  let aliasText=ws.entityEditing?.aliasText ?? row.aliases.join('\n');
  const change=(fn,redraw=false)=>{edit(()=>{fn();ws.entityEditing={group,row,aliasText};});if(redraw)render();};
  const root=h('div',{},heading(prior?'Editar proposta':'Nova entidade no catálogo','Edite a revisão e ative pelo painel após conferir todas as alterações.',button('← Revisões do catálogo',()=>navigate('catalog'))));
  const form=h('form',{onsubmit:e=>{e.preventDefault();if(locked)return;const success=edit(()=>{let saved=copy(row);delete saved._existingId;saved.label=saved.label.trim();saved.aliases=aliasText.split(/\n/).map(a=>a.trim()).filter(Boolean);if(prior && prior.label!==saved.label){saved=renameEntity({...saved,label:prior.label},saved.label);}saved.source_ids=[...new Set([...saved.source_ids,'WEB_EDITOR'])];if(saved.revision!=null && prior)saved.revision=prior.revision+1;const index=catalog[group].findIndex(r=>r[key]===row._existingId);if(index>=0)catalog[group][index]=saved;else{if(catalog[group].some(r=>r[key]===saved[key]))throw new Error('Este ID já existe na proposta.');catalog[group].push(saved);}ws.catalog.validation=null;ws.catalog.dirty=true;delete ws.entityEditing;});if(success)navigate('catalog');}});
  const fields=h('fieldset',{disabled:locked},h('legend',{},'Identidade e descrição'),h('div',{class:'form-grid'},field('ID estável',row[key],v=>change(()=>row[key]=v),{required:true,disabled:!!prior,help:'Não depende do nome. IDs existentes não podem ser reutilizados.'}),field('Nome',row.label,v=>change(()=>row.label=v),{required:true,maxlength:1000}),field('Aliases (um por linha)',aliasText,v=>change(()=>aliasText=v),{multiline:true,wide:true,help:'Ao renomear, o nome anterior é mantido como alias.'}),field('Situação',row.status,v=>change(()=>row.status=v),{options:[['ACTIVE','Ativo'],['DEPRECATED','Desativado, com identidade preservada']]})));
  if(group==='subjects'){
    fields.append(field('Escopo da disciplina',row.scope_note,v=>change(()=>row.scope_note=v),{multiline:true,required:true}),h('h3',{},'Relações com troncos'));
    for(const trunk of catalog.trunks){
      const link=row.trunk_links.find(l=>l.trunk_id===trunk.trunk_id);
      fields.append(check(trunk.label,!!link,value=>change(()=>{if(value)row.trunk_links.push({trunk_id:trunk.trunk_id,role:'RELATED',basis:'EDITORIAL_SUGGESTION',note:'Relação editorial conferida no painel.'});else row.trunk_links=row.trunk_links.filter(l=>l.trunk_id!==trunk.trunk_id);},true)));
      if(link)fields.append(field(`Papel em ${trunk.label}`,link.role,v=>change(()=>{link.role=v;link.basis='EDITORIAL_SUGGESTION';}),{options:[['FOUNDATIONAL','Conteúdo central'],['RELATED','Conteúdo relacionado']]}),field(`Justificativa em ${trunk.label}`,link.note,v=>change(()=>{link.note=v;link.basis='EDITORIAL_SUGGESTION';})));
    }
  }
  if(group==='topics')fields.append(field('Disciplina',row.subject_id,v=>change(()=>{row.subject_id=v;row.parent_topic_id=null;row.kind='TOPIC';},true),{options:[['','Selecione…'],...catalog.subjects.map(s=>[s.subject_id,s.label])],disabled:baseline}),field('Assunto pai (vazio para assunto de primeiro nível)',row.parent_topic_id || '',v=>change(()=>{row.parent_topic_id=v || null;row.kind=v?'SUBTOPIC':'TOPIC';}),{options:[['','Assunto de primeiro nível'],...catalog.topics.filter(t=>t.kind==='TOPIC' && t.subject_id===row.subject_id && t.topic_id!==row[key]).map(t=>[t.topic_id,topicPath(data.base.catalog,t)])],disabled:baseline}));
  form.append(card(fields),h('button',{type:'submit',class:'primary',disabled:locked},'Guardar alteração na proposta'));root.append(form);return root;
}
function catalogView() {
  const root=h('div',{},heading('Revisões do catálogo','Edite troncos, disciplinas e assuntos com identidades preservadas.',button('Consultar propostas salvas',()=>run('Carregando propostas…',async()=>{data.catalogDrafts=await api('admin/catalog/drafts');}))));
  root.append(card(badge(`Catálogo ativo v${data.base.catalog.catalog_version}`,'good'),h('p',{},'O catálogo é compartilhado pelo Android e pelas questões. Salve uma revisão, confira os vínculos e ative a nova versão aqui. As versões anteriores e os registros existentes permanecem preservados.')));
  root.append(button('Consultar histórico de ativações',()=>run('Carregando histórico…',async()=>{data.catalogHistory=await api('admin/catalog/history');})));
  if(data.catalogHistory){const rows=data.catalogHistory.versions;root.append(card(h('h2',{},'Ativações confirmadas'),button('Exportar catálogo original v1',()=>run('Exportando versão…',async()=>download('catalogo-v1.json',await api('admin/catalog/versions/1')))),...rows.map(row=>h('div',{class:'catalog-row'},h('div',{},h('strong',{},`Versão ${row.version}`),h('small',{},row.reason)),button('Exportar versão',()=>run('Exportando versão…',async()=>download(`catalogo-v${row.version}.json`,await api(`admin/catalog/versions/${row.version}`)))))),rows.length===50?button('Consultar versões anteriores',()=>run('Carregando histórico…',async()=>{data.catalogHistory=await api(`admin/catalog/history?before=${rows.at(-1).version}`);})):null));}
  if(!ws.catalog){root.append(button('Criar proposta a partir da versão atual',startCatalog,'primary'));}
  else {
    const draft=ws.catalog,catalog=draft.catalog;
    root.append(catalogActivationPanel(draft));
    if(draft.pending)root.append(card(badge('Salvamento aguardando confirmação','warning'),h('p',{},'Reenvie o comando original ou confira a revisão salva antes de editar.'),button('Comparar proposta do servidor',()=>run('Conferindo proposta…',async()=>{draft.server=await api(`admin/catalog/drafts/${encodeURIComponent(draft.draft_id)}`);persist();}))));
    if(draft.server)root.append(card(h('h2',{},`Servidor · revisão ${draft.server.revision}`),h('pre',{},pretty(draft.server.catalog)),button('Usar revisão do servidor e preservar minha edição',async()=>{if(await confirm('Continuar a partir da proposta salva?','Sua edição e seu comando pendente ficarão preservados no arquivo local.')){edit(()=>{const server=draft.server;ws.archives.push({type:'catalog',saved_at:new Date().toISOString(),value:copy(draft)});ws.catalog={draft_id:server.draft_id,revision:server.revision,base_catalog_sha256:server.base_catalog_sha256,status:server.status,catalog:copy(server.catalog),pending:null,validation:null};});render();}})));
    root.append(card(h('h2',{},`Proposta v${catalog.catalog_version}`),h('p',{},`Revisão salva: ${draft.revision} · ${draft.status}`),draft.pending?badge('Comando de salvamento pendente','warning'):badge('Edição local'),h('div',{class:'tabs'},...['trunks','subjects','topics'].map(group=>button({trunks:'Troncos',subjects:'Disciplinas',topics:'Assuntos e subassuntos'}[group],()=>{catalogTab=group;render();},catalogTab===group?'active':''))),button('＋ Nova entidade',()=>{if(!catalogLocked(draft))editEntity(catalogTab,null);}),...catalog[catalogTab].map(row=>h('div',{class:'catalog-row'},h('div',{},h('strong',{},row.label),h('small',{},row.trunk_id || row.subject_id && catalogTab==='subjects'?row.trunk_id || row.subject_id:row.topic_id)),actions(badge(row.status==='ACTIVE'?'Ativo':'Desativado',row.status==='ACTIVE'?'good':''),button('Editar proposta',()=>{if(!catalogLocked(draft))editEntity(catalogTab,row.trunk_id || (catalogTab==='subjects'?row.subject_id:row.topic_id));}))))));
    root.append(card(h('h2',{},'Validar e salvar versão'),h('p',{},'Rascunhos incompletos podem ser salvos. Para marcar como pronta para revisão, a proposta precisa passar pelo schema e pelas regras de identidade e referência.'),actions(button('Validar proposta',()=>run('Validando catálogo…',async()=>{draft.validation=await api('admin/catalog/validate',{method:'POST',body:catalog});persist();notice('Revisão validada. Salve como pronta para revisão e ative pelo painel.');})),button(draft.pending?'Conferir / reenviar salvamento':'Salvar rascunho no servidor',()=>saveCatalog('DRAFT'),'primary'),button('Salvar como pronta para revisão',()=>saveCatalog('REVIEW_READY'))),draft.validation?h('pre',{},pretty({...draft.validation,registry:undefined})):null,h('hr',{class:'divider'}),actions(button('Exportar knowledge-catalog.json',()=>download('knowledge-catalog.json',catalog)),button('Exportar registro de IDs',()=>run('Validando registro…',async()=>{const result=await api('admin/catalog/validate',{method:'POST',body:catalog});download('knowledge-id-registry.json',result.registry);}))),h('details',{},h('summary',{},'Edição avançada em JSON'),field('Catálogo completo',draft.advancedText ?? pretty(catalog),text=>{if(!catalogLocked(draft))edit(()=>{draft.advancedText=text;draft.dirty=true;});},{multiline:true,rows:15,code:true,disabled:catalogLocked(draft)}),button('Aplicar JSON à proposta',()=>{if(catalogLocked(draft))return;try{const parsed=JSON.parse(draft.advancedText || pretty(catalog));if(!parsed || ['trunks','subjects','topics','sources','relations'].some(key=>!Array.isArray(parsed[key])))throw new Error('Preserve as listas do catálogo no documento JSON.');edit(()=>{draft.catalog=parsed;draft.validation=null;draft.dirty=true;delete draft.advancedText;});render();}catch(error){notice(error.message,'error');}}))));
  }
  if(data.catalogDrafts)root.append(card(h('h2',{},'Propostas salvas nesta conta'),...data.catalogDrafts.map(draft=>h('div',{class:'catalog-row'},h('div',{},h('strong',{},draft.draft_id),h('small',{},`Revisão ${draft.revision} · ${draft.status}`)),button('Carregar',async()=>{if(ws.catalog?.pending || ws.catalog?.activationPending){notice('Resolva o salvamento pendente antes de carregar outra proposta.','warning');return;}if(await confirm('Carregar proposta salva?','A edição local atual será preservada em Rascunhos e pendências.'))run('Carregando proposta…',async()=>{const result=await api(`admin/catalog/drafts/${encodeURIComponent(draft.draft_id)}`);if(ws.catalog)ws.archives.push({type:'catalog',saved_at:new Date().toISOString(),value:copy(ws.catalog)});ws.catalog={draft_id:result.draft_id,revision:result.revision,base_catalog_sha256:result.base_catalog_sha256,status:result.status,catalog:result.catalog,pending:null,validation:null};persist();});})))));
  return root;
}
function saveCatalog(status) {run('Salvando proposta versionada…',async()=>{const draft=ws.catalog;if(draft.activationPending || (catalogLocked(draft) && !draft.pending))throw new Error('Inicie uma nova revisão antes de salvar outra versão.');draft.pending ||= {draft_id:draft.draft_id,expected_revision:draft.revision,base_catalog_sha256:draft.base_catalog_sha256,status,catalog:copy(draft.catalog)};persist();let result;try{result=await api('admin/catalog/drafts',{method:'POST',body:draft.pending});}catch(error){if(error.status===400){draft.pending=null;persist();}throw error;}const saved=result.draft;if(saved.draft_id!==draft.draft_id || saved.revision!==draft.pending.expected_revision+1)throw new Error('Recibo da proposta divergente.');draft.revision=saved.revision;draft.status=saved.status;draft.pending=null;draft.validation=result.validation;draft.dirty=false;persist();notice('Versão editorial salva. O catálogo ativo permanece na versão publicada.');});}

function draftsView() {
  const root=h('div',{},heading('Rascunhos e pendências','Dados locais separados por conta. Envios incertos conservam seus comandos.',button('Exportar todos',()=>download('cogniva-rascunhos.json',exportWorkspace(me.user_id,ws)),'primary')));
  if(ws.entityEditing)root.append(card(h('h2',{},'Entidade do catálogo em edição'),h('p',{},ws.entityEditing.row.label || 'Nova entidade'),button('Continuar edição',()=>{entityGroup=ws.entityEditing.group;selectedEntity=ws.entityEditing.row;navigate('entity');})));
  root.append(card(h('h2',{},'Continuar um trabalho'),ws.import?h('div',{class:'catalog-row'},h('div',{},h('strong',{},'Inserção de questões'),h('small',{},ws.import.submitted && !ws.import.result?.complete?'Envio aguardando confirmação':'Rascunho local')),button('Abrir',()=>navigate('import'))):null,...Object.entries(ws.exams).map(([id,draft])=>h('div',{class:'catalog-row'},h('div',{},h('strong',{},draft.document.short_name || draft.document.title || 'Concurso sem nome'),h('small',{},draft.pending?'Comando pendente':'Edição local')),button('Abrir',()=>{selectedExam=id;navigate('exam');}))),...Object.entries(ws.questionEdits).map(([id,draft])=>h('div',{class:'catalog-row'},h('div',{},h('strong',{},`Questão ${id}`),h('small',{},draft.submitted && !draft.result?'Envio aguardando confirmação':'Contrato em edição')),button('Abrir',()=>{selectedQuestion=id;navigate('question');}))),ws.catalog?button('Continuar proposta do catálogo',()=>navigate('catalog')):null));
  const file=h('input',{type:'file',accept:'.json,application/json','aria-label':'Restaurar rascunhos desta conta',onchange:async e=>{try{const selected=e.target.files[0];if(!selected)return;if(selected.size>4*1024*1024)throw new Error('Arquivo excede 4 MiB.');const recovered=restoreWorkspace(me.user_id,JSON.parse(await selected.text()));if(await confirm('Restaurar este arquivo?','A cópia atual será preservada dentro do arquivo local. Comandos de envio do arquivo restaurado continuarão com as mesmas identidades.')){recovered.archives ||= [];recovered.archives.push({type:'workspace-backup',saved_at:new Date().toISOString(),value:copy(ws)});ws=recovered;ws.exams ||= {};ws.questionEdits ||= {};persist();render();notice('Rascunhos restaurados para esta conta.');}}catch(error){notice(error.message,'error');}}});
  root.append(card(h('h2',{},'Restaurar arquivo exportado'),h('p',{},'Restaure apenas um arquivo desta conta. Nenhum envio é disparado ao abrir o arquivo.'),file),card(h('h2',{},`Arquivo local · ${ws.archives.length} registros preservados`),h('p',{},'Edições substituídas ficam disponíveis para exportação. Elas não são reenviadas automaticamente.'),...ws.archives.map((item,index)=>h('div',{class:'catalog-row'},h('div',{},h('strong',{},`${index+1}. ${item.type}`),h('small',{},item.saved_at)),button('Exportar',()=>download(`cogniva-arquivo-${index+1}.json`,item))))));return root;
}

document.querySelectorAll('[data-page]').forEach(node=>node.addEventListener('click',()=>{if(node.dataset.page==='help')helpFocus='';navigate(node.dataset.page);}));
$('#menu-toggle').addEventListener('click',()=>{const open=$('#app').classList.toggle('menu-open');$('#menu-toggle').setAttribute('aria-expanded',String(open));});
$('#logout').addEventListener('click',()=>run('Encerrando sessão…',async()=>{if(!storageBlocked)persist();await api('auth/web-logout',{method:'POST'});me=null;ws={};data={};storageBlocked=false;unreadableStorage=null;$('#app').hidden=true;$('#login').hidden=false;$('#login-message').textContent='Sessão encerrada. Seus rascunhos foram preservados nesta conta.';}));
$('#login-form').addEventListener('submit',async event=>{event.preventDefault();const control=$('#login-code'),submit=$('#login-form button');submit.disabled=true;$('#login-message').textContent='Verificando acesso…';try{await api('auth/web-session',{method:'POST',body:{code:control.value.trim()}});control.value='';await bootstrap();$('#login-message').textContent='';}catch(error){control.value='';$('#login-message').textContent=error.message;notice(error.message,'error');}finally{submit.disabled=false;}});
window.addEventListener('online',()=>$('#connection').textContent='Conexão disponível');window.addEventListener('offline',()=>$('#connection').textContent='Offline · rascunhos preservados');
window.addEventListener('beforeunload',event=>{if(busy){event.preventDefault();event.returnValue='';}});
bootstrap().catch(error=>{if(error.status!==401)$('#login-message').textContent=error.message;});
