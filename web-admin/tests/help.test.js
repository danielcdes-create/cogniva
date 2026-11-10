import test from 'node:test';
import assert from 'node:assert/strict';
import {helpTopics,searchHelp} from '../help.js';

test('help search handles accents, case and multiple terms used in real tasks',()=>{
  assert.deepEqual(searchHelp('BANCO CENTRAL pré-edital').map(topic=>topic.id),['pre-edital-banco-central']);
  assert.ok(searchHelp('sessao 30 dias').some(topic=>topic.id==='acesso-sessao'));
  assert.ok(searchHelp('restaurar rascunhos').some(topic=>topic.id==='rascunhos'));
  assert.ok(searchHelp('rubrica discursiva').some(topic=>topic.id==='inserir-individual'));
});

test('empty and unmatched help searches are safe and do not mutate the guide',()=>{
  const original=structuredClone(helpTopics);
  assert.deepEqual(searchHelp('   '),helpTopics);
  assert.deepEqual(searchHelp('termo-inexistente-87654'),[]);
  assert.deepEqual(helpTopics,original);
});

test('every help action opens a supported portal view and article ids are unique',()=>{
  const destinations=new Set(['overview','exams','edital','trunks','subjects','questions','import','catalog','drafts']);
  assert.equal(new Set(helpTopics.map(topic=>topic.id)).size,helpTopics.length);
  for(const topic of helpTopics)assert.ok(destinations.has(topic.page),topic.id);
});
