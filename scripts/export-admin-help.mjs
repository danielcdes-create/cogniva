import {helpTopics} from '../web-admin/help.js';
import {readFileSync,writeFileSync} from 'node:fs';

const path=new URL('../docs/GUIA-ADMINISTRACAO.md',import.meta.url);
const guide='# Ajuda do portal de administração\n\n'+
  'Conteúdo compartilhado com a seção Ajuda do portal, disponível no menu lateral e em Ajuda desta tela. Fonte: `web-admin/help.js`. Para atualizar este arquivo, execute `node scripts/export-admin-help.mjs --write`.\n\n'+
  helpTopics.map(topic=>`## ${topic.title}\n\n${topic.intro}\n\n${topic.steps.map((step,index)=>`${index+1}. ${step}`).join('\n')}\n\n${topic.notes.join('\n\n')}\n\nNo portal: **${topic.action}**.\n`).join('\n');
if(process.argv.includes('--write'))writeFileSync(path,guide,'utf8');
else if(readFileSync(path,'utf8').replace(/\r\n/g,'\n')!==guide)throw new Error('Guia divergente da Ajuda. Execute node scripts/export-admin-help.mjs --write.');
console.log(process.argv.includes('--write')?'Guia de administração atualizado.':'Guia de administração alinhado à Ajuda.');
