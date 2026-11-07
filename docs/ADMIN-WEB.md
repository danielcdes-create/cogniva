# Administração web do Cogniva

O painel está em `/admin/`, servido pelo próprio backend Rust, com a mesma API e base do Android. Disponível em produção desde **1.4.13/build 20**, com backend, APK e Nginx conferidos em 07/11/2026. [Aceite e evidências](RELEASE-1.4.13.md).

## O que pode ser administrado

- **Concursos:** criação, edição, situação, instituição, cargo, banca, previsão de prova, prioridade, corte, penalidade e participação na escada da conta.
- **Editais e grades:** disciplinas por componente/etapa, pesos, quantidades, grupos de conhecimentos, assuntos canônicos ou personalizados, publicação da grade e arquivamento. A mesma disciplina pode aparecer em várias etapas com identidades locais diferentes.
- **Questões:** busca paginada no enunciado, edição v2, conversão explícita de registros legados com prévia e revisão, inserção individual/JSON/JSONL e assistência opcional de Grok 4.3. Os fluxos de inserção usam as mesmas rotas do [módulo Android](INSERCAO-QUESTOES.md).
- **Troncos, disciplinas e assuntos globais:** criação, renomeação com aliases, escopo, vínculos com carreiras, papéis dos vínculos e desativação sem remoção de IDs. Revisões podem ser salvas, validadas, exportadas e **ativadas no próprio painel**.
- **Rascunhos e pendências:** continuidade no navegador, exportação/restauração por conta, comandos congelados após envio incerto, comparação com servidor e preservação de edições substituídas.

Grades de concursos e questões são gravadas após confirmação. Alterações globais exigem uma proposta salva como `REVIEW_READY`, motivo e confirmação explícita de ativação. A nova versão passa a valer nas próximas consultas, sem reinício ou nova compilação; o Android a recebe na sincronização. Referências históricas, classificações e tentativas continuam na versão em que foram registradas. Esta capacidade está instalada no backend e no Android 1.4.13; o deploy preservou o catálogo v1, sem ativar uma revisão editorial.

## Entrar pelo computador

Abra [o painel administrativo](https://198.199.65.103/admin/). A publicação 1.4.13 confirmou a página e os três assets iguais ao commit promovido, CSP, cache desabilitado e rejeição de acesso administrativo anônimo.

O responsável emite um código para uma conta **já comprovada e autorizada em `ADMIN_USER_IDS`**:

Na configuração inicial, `ADMIN_USER_IDS` precisa estar no ambiente efetivo do container, por exemplo no arquivo privado `/opt/apps/.env.rust`. Emitir um código não concede o papel administrativo. Confirme a identidade por um vínculo nativo existente e seu recibo; depois atualize a configuração privada e recrie somente o Cogniva, com gate, backup e readiness, sem substituir o Compose compartilhado ou ampliar a lista para contas não verificadas.

```sh
docker exec api-rust-core /usr/local/bin/app --issue-web-code ID_DA_CONTA
```

O comando deve ser executado no ambiente que possui o segredo JWT e a lista administrativa corretos. Não publicar códigos, colocá-los na URL, gravá-los em documentação ou ampliar a lista de administradores apenas para contornar uma falha de acesso.

Cole o código na tela de entrada. Ele vale por 10 minutos e tem uso único, com propósito `web_login`, separado das credenciais nativas e dos códigos de vínculo do Android. Após o login, a sessão web dura até 8 horas e pode ser encerrada pelo botão **Sair**. Use a mesma conta do Android para administrar seus concursos; questões e catálogo são compartilhados, enquanto grades e rascunhos mantêm escopo de proprietário.

O navegador não guarda JWT em `localStorage` ou `sessionStorage`. A sessão usa cookie `HttpOnly`, `SameSite=Strict` e `Secure` fora de loopback, com revogação persistida no logout. Ações com cookie exigem origem correspondente ao endereço do painel e o cabeçalho `X-Cogniva-CSRF: 1`. Logout também funciona para limpar uma sessão expirada. Respostas autenticadas por cookie e os arquivos do painel usam `Cache-Control: no-store`.

## Edição e recuperação

Alterações são salvas localmente sob a identidade textual da conta, incluindo IDs de 64 bits sem conversão para números JavaScript. Credenciais não fazem parte desse arquivo.

Antes de gravar um concurso, publicar uma grade, editar uma questão ou inserir um lote, o painel salva o comando exato no navegador. Falhas de rede não geram novas identidades nem reenvio automático. A ação de retentativa reutiliza o comando original.

Concursos usam as revisões e recibos já existentes. Questões usam CAS, validação v2 e outbox de auditoria. O painel confere identidade e revisão dos recibos; a importação confere cada item e não declara o lote completo se parte dele falhar. A edição de conteúdo v2 invalida uma decisão definitiva de validade e registra `UNDER_REVIEW`. Em uma questão legada, **Preparar conversão e editar** cria apenas um rascunho v2 com o mesmo ID: gabarito ausente, ambíguo ou contraditório fica sem seleção, classificação exige conferência e validade começa `UNKNOWN`. A gravação só ocorre após prévia válida e confirmação. Campos extras são preservados e o documento anterior integra a auditoria.

Em conflito, use **Comparar com servidor**. Escolher a versão salva conserva a edição anterior no arquivo local, disponível em **Rascunhos e pendências**. O módulo não faz mesclagem automática nem sobrescreve a revisão de outro aparelho. Rascunho local ilegível bloqueia alterações até exportação/recuperação, em vez de ser substituído silenciosamente.

O painel pode continuar com os dados carregados durante uma perda de conexão, mas login, nova leitura e gravação exigem acesso à API. Não há service worker nem promessa de reabertura offline. A limpeza do armazenamento do navegador apaga os rascunhos locais; exporte os arquivos que deseja conservar.

## Propostas do catálogo

O editor inicia uma cópia da versão atual, mantendo o hash da base e propondo `catalog_version + 1`. As entidades mantêm IDs, filiação e nomes anteriores. Renomear conserva aliases; desativação usa `DEPRECATED`. Novas propostas registram a fonte editorial `WEB_EDITOR`.

O servidor permite salvar um rascunho incompleto como `DRAFT`. Para `REVIEW_READY`, exige o schema compartilhado e valida IDs únicos, referências, hierarquia, nomes/aliases normalizados, fontes, relações simétricas e preservação de identidades. Vínculos com troncos podem mudar; IDs existentes e a filiação de assuntos não são reutilizados com outro significado. Para reorganizar uma filiação, crie a nova entidade e desative a anterior. O registro conserva identidades anteriores e acrescenta as novas. Assuntos novos precisam de subassuntos, conforme a política vigente.

`catalog_editor_drafts` contém a revisão atual por conta/proposta. `catalog_editor_versions` preserva snapshots individuais de cada salvamento. O hash e a revisão impedem que outra janela sobrescreva a proposta sem conferência. As duas coleções usam majority/journal; repetir o mesmo comando recupera o mesmo resultado. Se o ponteiro não for confirmado após armazenar uma versão, a mesma retentativa pode concluir a operação sem substituir outra proposta.

Depois de salvar, informe o motivo e confirme **Ativar catálogo global**. O comando fixa a revisão da proposta e o hash do catálogo ativo. `knowledge_catalog_activations` registra o snapshot e a autoria imutáveis; uma troca condicional de `knowledge_catalog_head` escolhe a versão ativa. Duas propostas concorrentes não sobrescrevem uma à outra. Em resposta incerta, **Conferir / reenviar ativação** reutiliza o mesmo comando, inclusive após reinício. Uma proposta antiga exige nova revisão a partir da base atual. O histórico permite consultar/exportar versões confirmadas e seus registros de identidade.

`knowledge_catalog_versions` conserva o catálogo original e cada versão ativada. O servidor mantém um snapshot consistente por requisição e interpreta classificações/compatibilidades antigas em sua versão conhecida. Renomear ou mudar um vínculo não reescreve questões, evidências de aprendizagem ou tentativas. Desativar uma disciplina/assunto retira suas questões da seleção atual, mantendo documentos e histórico; grades existentes podem conservar referências desativadas, mas novas associações a elas são bloqueadas. A semântica de exclusões históricas é preservada no servidor e no Android atualizado.

A automação finita de classificação legada está vinculada ao contrato v1. Ativar v2 ou superior **pausa novas gerações dessa automação**; o inventário congelado, a fila, os limites e as propostas já existentes são preservados. Revisão/classificação manual continua disponível. Não há migração automática dessa automação para uma política diferente.

## Rotas

| Rota | Finalidade |
| --- | --- |
| `GET /admin/` e `/admin/assets/*` | Interface estática incorporada ao binário |
| `POST /api/v1/auth/web-session` | Troca de código de uso único por cookie |
| `POST /api/v1/auth/web-logout` | Revogação/limpeza do cookie, inclusive expirado |
| `GET /api/v1/admin/web/me` | Identidade e permissões da sessão |
| `GET /api/v1/admin/summary` | Contagens de questões, concursos e catálogo |
| `GET /api/v1/admin/questions` | Busca literal e paginação por ObjectId, até 50 itens |
| `POST /api/v1/admin/questions/:id/prepare-edit` | Prévia validada, hash e revisões de uma edição v2 |
| `GET /api/v1/admin/catalog/base` | Catálogo ativo e hash de referência |
| `POST /api/v1/admin/catalog/validate` | Validação de proposta e registro de IDs exportável |
| `GET/POST /api/v1/admin/catalog/drafts` | Listagem/salvamento de propostas da conta |
| `GET /api/v1/admin/catalog/drafts/:id` | Leitura da revisão atual da proposta |
| `POST /api/v1/admin/catalog/activate` | Ativação revisada, condicional e idempotente |
| `GET /api/v1/admin/catalog/activations/:operation` | Situação de um comando de ativação da conta |
| `GET /api/v1/admin/catalog/history?before=VERSAO` | Histórico confirmado, até 50 versões por página |
| `GET /api/v1/admin/catalog/versions/:version` | Catálogo e registro de identidades históricos |

As rotas de concursos, publicação de editais, questões v2 e importação são reutilizadas. Autenticação por Bearer do Android continua válida; o cookie web tem propósito próprio e não transforma códigos de login em tokens nativos.

## Arquitetura e publicação

Os arquivos em `web-admin/` são HTML/CSS/JavaScript sem dependências de produção. `package.json` configura apenas módulos ES e testes nativos do Node. O Rust incorpora os arquivos durante a compilação; não há novo container Node, banco separado, hospedagem externa ou build na VPS.

`backend-rust/Dockerfile` copia os fontes web antes da compilação. `deploy/nginx-concursoprep.conf` encaminha `/admin/` e inclui a troca de código no limite de autenticação. A promoção deve seguir [OPERACAO.md](OPERACAO.md), afetando apenas o Cogniva e preservando o compose compartilhado. O gate de manutenção também bloqueia o painel durante promoção. Conteúdo web tem CSP com scripts/estilos da própria origem, sem scripts inline, `eval` ou renderização de HTML fornecido em questões.

## Validação e prévia isolada

```powershell
node --check web-admin/app.js
node --check web-admin/domain.js
node --test web-admin/tests/*.test.js
python -m pytest scripts/tests -q
cd backend-rust
cargo build --locked
$env:COGNIVA_TEST_MONGODB_URI='mongodb://127.0.0.1:27048/'
cargo test --locked -- --include-ignored --test-threads=1
```

A integração usa Mongo dedicado na porta 27048, com `enableTestCommands=1` para os testes de falha do projeto. Nunca apontar essa variável ao Atlas de produção. O CI inclui os testes web e a suíte Rust integral serializada.

Para conferir a interface localmente com o binário já compilado, Mongo isolado em execução e o módulo Python `pymongo` instalado (ferramenta de QA, sem dependência em produção):

```powershell
python scripts/preview-web-admin.py --port 8876 --minutes 20
```

O script cria apenas uma base `cogniva_test_web_preview_*`, restringe o HTTP a `127.0.0.1`, desabilita todos os provedores de IA e usa perfil/questões sintéticos. A saída informa o caminho de um `ready.json` temporário com URL, código de demonstração e arquivo de parada. Crie o arquivo de parada indicado para encerrar antecipadamente; ao fim, o script encerra seu backend, remove a base sintética e apaga o código temporário. Ele não inicia Mongo, instala serviços, usa credenciais reais nem executa build em servidor remoto.

## Resultado da validação local — 07/11/2026

- 68 testes Rust aprovados, incluindo todas as integrações opt-in com Mongo isolado, em execução serializada.
- 10 testes web aprovados e sintaxe dos módulos JavaScript verificada.
- 47 testes Python e 14 subtestes aprovados.
- 71 testes Android/JVM e lint aprovados. Nenhuma homologação em aparelho físico nesta validação.
- No navegador: login real por cookie, cadastro individual com classificação, edição v2 com validade em revisão, lote JSONL de duas questões, concurso com Matemática em duas etapas/pesos distintos, proposta de renomeação com alias e salvamento `REVIEW_READY`, persistência após recarregamento e indicadores consultados da base sintética.
- Na ampliação global: ativação v2 pelo navegador com motivo e confirmação, histórico exibido, recarga com versão ativa preservada, nova revisão v3 e conversão explícita de questão legada com ID/campos adicionais preservados e classificação conferida.
- Integração: ativação concorrente com um único vencedor, recibos idempotentes, recuperação após reinício/reserva e preservação de questões, exclusões e aprendizagem durante renomeação/desativação.
- Dimensões conferidas: desktop com viewport de 1440 px e painel compacto de 658 px, sem extrapolar a largura da página. Nenhum erro de console foi observado na conferência final.

A execução completa encontrou uma expectativa antiga de teste que exigia grade vazia no tombstone de concurso. O backend já preservava a última grade salva, conforme seu contrato; o teste foi atualizado para exigir essa preservação, sem alterar a rotina de exclusão. A suíte integral passou depois desse ajuste.

Evidências: [registro de validação](remediation-validation/admin-web/validation.json) e [captura do catálogo ativado com dados sintéticos](remediation-validation/admin-web/catalogo-ativado.jpg). Nenhuma publicação em produção ou chamada paga à IA foi realizada nesta validação.

A validação de publicação da 1.4.13 confirmou todos os gates de CI, download integral e assinatura compatível do APK, quatro assets públicos idênticos ao commit, 401 em quatro rotas administrativas, rejeição de login inválido/origem externa e três rotas de APK sem compressão. Não foram inseridas questões ou ativados catálogos em produção para testar a entrega. [Resultados medidos](RELEASE-1.4.13.md).

Após a publicação, a conta administrativa do proprietário foi habilitada usando o vínculo nativo e recibo já existentes. O teste autenticado confirmou cookie seguro, identidade, resumo, catálogo v1, capacidades de inserção e logout com retorno a 401. Somente metadados de sessão foram criados; nenhum conteúdo foi editado. [Configuração](remediation-validation/1.4.13/admin-access.json) e [login validado](remediation-validation/1.4.13/admin-login.json). Códigos e cookies não integram as evidências.
