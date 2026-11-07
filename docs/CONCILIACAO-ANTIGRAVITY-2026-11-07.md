# Conciliação das correções do Antigravity — 07/11/2026

O material fornecido foi comparado ao commit `1ca40910902e437f0e5414349b5c2ceafdfcc31b`, ao checkout local com as ampliações do painel/importador e à execução real no GitHub. O commit do Antigravity é o `HEAD` e a base das alterações locais: as correções não estavam em um branch separado aguardando merge.

## Publicação e causa da interrupção

A [run 42331321184](https://github.com/danielcdes-create/cogniva/actions/runs/42331321184), da tag `v1.4.12`, terminou com **falha no backend**. O teste `editable_exam_components_and_deletion_survive_sync_without_touching_history` esperava `tombstone.subjects.is_empty()`. Resultado registrado: 54 testes passaram e um falhou. Os jobs `build` e `promote` foram **pulados**. Não houve APK release nem promoção produzidos por essa execução. Os artefatos disponíveis são relatórios de validação Android/emulador.

Os jobs Android, contratos Python e emulador de insets passaram naquela execução. Isso não substitui a construção/publicação do APK nem comprova interação física com o editor de disciplinas.

Na consulta pública desta revisão, `/api/v1/health` e `/api/v1/ready` responderam 200 com revisão `4d790fc3df3701447333e34eabef3870b11b58b3`; `/api/app/latest-apk` anunciou **1.4.11/build 18**, 46.101.553 bytes e SHA-256 `f020d1f6ecba659b323257468647657fc1cd9af8ba63c4171e9b81f5156bf049`. O APK inteiro não foi baixado novamente nesta auditoria.

`OPERACAO.md` chamava essa combinação de hash/tamanho de “1.4.12/build 19”. A documentação foi corrigida para distinguir a versão efetivamente anunciada pelo servidor da tentativa interrompida.

## Conferência do código

| Correção relatada | Implementação encontrada e conciliação |
| --- | --- |
| Motivo real da indisponibilidade da IA | `error.rs` conserva a mensagem de `ExternalAiError` no HTTP 502. `ai_tutor::call_xai_text` extrai mensagem simples, `error.message` ou `message` da xAI, com status original e detalhe limitado a 280 caracteres. Preservado; o importador novo usa a mesma função. |
| Mensagem no Android sem revelar a resposta na dica | `ConcursoRepository.requestAiTutor` já lê a mensagem HTTP e impede fallback para explicação nos modos pré-resposta. Preservado e ampliado com testes 403/429/502/503, corpo vazio/inválido e cancelamento. |
| Rolagem e ações acessíveis no cadastro | `ExamBuilderSheet.kt` contém uma única `Column` com `weight(1f)` e `verticalScroll`; cabeçalho e ações de navegação/salvamento ficam fora da área rolável. O arquivo continua idêntico ao commit do Antigravity. |
| Editor de disciplinas e tópicos | `ExamSubjectEditor.kt` contém edição de nome, etapa, grupo, peso, quantidade, tópicos, associação, arquivamento e exclusão confirmada. O arquivo continua idêntico ao commit do Antigravity. |
| Disciplinas já inseridas saem do catálogo disponível | `availableCatalogSubjects` filtra IDs já presentes na grade; `Adicionar outra etapa` cria outro componente com identidade local distinta. Preservado e coberto pelo teste de catálogo/associação. |
| Exclusão imediata e sincronização | Android oculta imediatamente `EXCLUIDO`, encerra localmente criações nunca enviadas e trata 409/404. O backend transforma a intenção de excluir no registro anterior, mantendo sua última grade; a repetição da exclusão devolve o tombstone existente. Preservado. |
| OTA íntegro | As rotas de download continuam fora da compressão, com tamanho/hash calculados do arquivo; o Android pede `Accept-Encoding: identity`. Os testes existentes de metadados/download continuam passando na suíte Rust. |

Há duas diferenças materiais entre o anúncio e o código, registradas sem atribuir ao código uma interface inexistente:

- A tela de cadastro usa `Column` com rolagem, **não `LazyColumn`**.
- O editor de disciplina foi extraído para outro arquivo, mas continua sendo um **cartão expansível na grade**, sem subetapa dedicada. O filtro de duplicatas considera a grade inteira, não uma etapa selecionada. A repetição em outra etapa é uma ação explícita do cartão.

Essas estratégias de interface foram mantidas nesta conciliação. Os campos e as operações existentes não foram substituídos pelo painel web.

## Ajustes realizados na conciliação

1. Extração da mensagem HTTP centralizada em `data/remote/ApiErrorMessage.kt`, compartilhada pelo tutor e pelo módulo novo de inserção. Mensagens nulas, vazias ou JSON inválido usam um fallback com o status HTTP.
2. O tutor diferencia `IOException` de erros inesperados de processamento. Uma falha inesperada deixa de recomendar conexão como se uma queda de rede tivesse sido comprovada. Dicas pré-resposta continuam sem expor a explicação em cache.
3. O módulo de inserção também deixa de usar “falha de conexão” como fallback para qualquer exceção sem mensagem; mantém o rascunho atual na tela.
4. Teste Rust com provedor HTTP sintético comprova que motivos 403/429/503 chegam ao contrato JSON 502. Erro HTML de proxy mantém apenas o status; não exige chave real, Mongo ou chamada paga.
5. Teste Android adicional cobre exclusão cujo servidor responde 404: a pendência encerra, o concurso permanece oculto e a grade local continua preservada.

O teste que interrompeu a publicação já havia sido conciliado durante a implementação do painel: ele exige agora que a grade do tombstone seja igual à última grade salva. Isso corresponde ao código do Antigravity em `routes/exams.rs`; a rotina de exclusão não foi alterada para apagar os dados e satisfazer a expectativa antiga.

As ampliações de catálogo preservam IDs e versões históricas. A mesma disciplina em duas etapas mantém identidades, tópicos e pesos independentes. As rotas e os componentes Android existentes continuam junto do novo módulo de questões e do painel web.

## Validação e limites

O resultado final está no [registro de validação](remediation-validation/antigravity-reconciliation-2026-11-07/validation.json). A suíte Rust integral com Mongo loopback aprovou **69 testes**, incluindo a regressão da exclusão que falhou no CI e o novo teste do provedor. O Android aprovou **73 testes JVM**, lint e compilação dos testes instrumentados. Os testes instrumentados não foram executados em aparelho/emulador nesta conciliação.

Não houve commit, push, nova tag, publicação, geração paga de IA ou alteração no banco de produção nesta conciliação. As modificações continuam locais e preservam o trabalho anterior. Um novo deploy precisa incluir o conjunto conciliado em um novo commit; repetir a tag antiga aponta apenas para o commit original, sem as ampliações locais.

Evidências: [jobs e falha da execução](remediation-validation/antigravity-reconciliation-2026-11-07/workflow.json), [respostas públicas da produção](remediation-validation/antigravity-reconciliation-2026-11-07/production-readonly.json) e [validação anterior do painel](ADMIN-WEB.md).
