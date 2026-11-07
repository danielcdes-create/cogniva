# Catálogo compartilhado de conhecimentos

O arquivo compartilhado abaixo é a base imutável v1. A implementação do [painel web](ADMIN-WEB.md) permite criar, revisar e ativar versões posteriores no Mongo, sem recompilar a cada alteração. A API entrega a versão ativa e o Android atualizado projeta seus nomes, vínculos e situações após sincronização. IDs e snapshots históricos são preservados; desativação afeta a seleção atual sem apagar questões ou aprendizagem. Esta capacidade está implementada localmente, sem evidência de publicação em produção.

Etapa 1 concluída como definição versionada: `shared/knowledge-catalog.json`, versão 1, com 35 disciplinas, 49 assuntos, 174 subassuntos e sete troncos. A consulta completa está em [catalogo/CONHECIMENTOS.md](catalogo/CONHECIMENTOS.md). Há 41 nomes alternativos de disciplinas, além de abreviações pontuais de subassuntos.

Esta base editorial organiza conteúdo reutilizável; não certifica cobertura de editais nem classifica questões automaticamente. As etapas [3](ETAPA-3-BACKEND-MONGO.md) e [4](ETAPA-4-CADASTRO-CATALOGO.md), implementadas para 1.4.5/build 12, integram a leitura, a persistência versionada em Mongo e a associação explícita de componentes no Android. `shared/canonical-catalog.json` preserva os templates originais; a API de catálogo legado passa a projetar o catálogo de conhecimentos ampliado sem reescrever esse arquivo. Estado de publicação e aceite: `remediation-validation/1.4.5/`.

O marcador `compatibility.runtime_transition=NOT_APPLIED` pertence ao snapshot imutável da definição inicial, em que o arquivo de templates não foi substituído. Não é telemetria do deploy nem significa que a API atual desconheça o catálogo. O acervo de questões e os eventos de aprendizagem não são reclassificados por essa integração. Na etapa 5, uma proposta pode citar estes IDs, mas só o aceite humano grava o vínculo. Nome parecido, alias ou tronco não confirmam classificação. Nenhum lote de produção classificou as questões.

## Entidades e identidade

Uma disciplina tem `subject_id`, rótulo, nomes alternativos, escopo e relações com carreiras. Um assunto tem `topic_id`, `subject_id` e `parent_topic_id=null`. Um subassunto tem identidade própria e aponta para um assunto da mesma disciplina. Nesta versão há exatamente esses dois níveis abaixo da disciplina. Referências cruzadas ficam em `relations`, sem alterar o pai.

Os IDs não dependem de rótulos nem da posição na lista. Preservam-se todas as 24 disciplinas e os 26 tópicos existentes, inclusive nomes anteriores e vínculos de carreira. O registro `shared/knowledge-id-registry.json` reserva 265 identidades. Renomear requer manter o nome anterior como alias; reutilizar um ID para outra disciplina, pai ou significado é proibido. Desativação usa `DEPRECATED`, sem remoção física do registro.

Novos subassuntos usam IDs explícitos como `FIN_PUB_01_001`. O prefixo facilita a leitura inicial, mas não autoriza calcular equivalência nem renumerar IDs. Mudar o significado ou a filiação exige uma nova identidade e uma migração revisada; não basta editar o registro de reserva.

## Relações com carreiras

Os seis troncos atuais são preservados: fiscal, policial, controle, engenharia, tribunais e bancária. Acrescenta-se `fiscalizacao_trabalho`, distinguindo inspeção do trabalho de controle/auditoria governamental. O vínculo antigo de CPNU/AFT com `controle` permanece no contrato legado; não foi remapeado silenciosamente.

Cada disciplina declara seus `trunk_links`; a lista inversa de disciplinas não é mantida como segunda autoridade no novo catálogo. `FOUNDATIONAL` indica conteúdo central do perfil editorial; `RELATED`, conteúdo potencialmente relacionado. A origem distingue `LEGACY_TEMPLATE` de `EDITORIAL_SUGGESTION`. Nenhuma dessas relações torna a disciplina obrigatória em todos os editais da carreira.

O legado tem relações cujo contêiner diz “complementar”, mas cujo `relevance_tier` diz `CORE`. Essa inconsistência não é apagada por reescrever o catálogo em execução. A nova definição registra a proveniência e usa o tier declarado para o papel herdado; uma futura revisão de templates deve decidir a composição de cada edital separadamente.

## Consolidação sem equivalências falsas

AFO e Finanças Públicas continuam distintas, com sobreposições explícitas em orçamento. Contabilidade Geral e Contabilidade Pública têm identidades diferentes. Gestão Governamental, Administração Pública Federal e Administração Geral também permanecem separadas. O antigo ID `ENG_CIVIL` continua significando **Estruturas**, não toda a Engenharia Civil.

`OVERLAPS_WITH` e `RELATED_TO` são relações de conteúdo, não sinônimos. Não transferem domínio, gabarito, questões nem cobertura integral entre tópicos. Aliases servem à localização de um conceito: não são regras para migrar questões ou mesclar registros por semelhança textual.

A expansão acrescenta Matemática, Raciocínio Lógico, Matemática Financeira, Estatística, Informática Básica, Administração Geral, Contabilidade Pública, Gestão de Pessoas, Direito Civil, Planejamento e Gestão de Obras e Direito Eleitoral. A versão é uma base inicial revisável, não uma taxonomia exaustiva de todas as carreiras e especialidades.

## Proveniência e referências

Cada entidade aponta para fontes identificadas no próprio catálogo. `LEGACY_V2` é a base interna anterior. `EDITORIAL_V1` identifica a decomposição assistida proposta nesta etapa. `REFERENCE_PORTAL` identifica páginas primárias consultadas, sem afirmar extração ou conferência integral dos conteúdos programáticos:

- [MGI: CPNU 2024, bloco Trabalho e Saúde do Servidor](https://www.gov.br/gestao/pt-br/concursonacional/editais/edital-cpnu-bloco-4-10jan2024.pdf/view).
- [FGV: Receita Federal 2022](https://conhecimento.fgv.br/concursos/rfb22).
- [FGV: DNIT 2023](https://conhecimento.fgv.br/concursos/dnit23).
- [TRE-MA: concurso unificado da Justiça Eleitoral 2024](https://www.tre-ma.jus.br/institucional/concurso-publico/concurso-publico-unificado-2024).

O catálogo não contém interpretações de normas nem certifica vigência jurídica. Títulos de NRs e demais conteúdos normativos exigem avaliação de validade na questão e na versão de referência aplicável.

## Validação e manutenção

```powershell
python -m pip install -r scripts/requirements-contracts.txt
python scripts/knowledge_catalog.py --check
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
```

Após editar a base, `python scripts/knowledge_catalog.py --export` atualiza a consulta Markdown. O CI rejeita divergência entre a consulta e o JSON.

O validador verifica JSON Schema, IDs únicos, referências, nomes alternativos ambíguos após normalização, hierarquia sem ciclos ou pais de outra disciplina, fontes conhecidas, relações simétricas duplicadas, preservação dos IDs legados e integridade do catálogo atual. O hash de compatibilidade é de JSON normalizado, independente de CRLF/LF.

Para acrescentar conhecimento, revisar o escopo e os aliases, reservar IDs, fornecer a proveniência, atualizar a versão do catálogo e regenerar a consulta. Futuras versões em Mongo precisam ser recuperáveis por `catalog_id` e `catalog_version`, pois classificações e avaliações históricas devem continuar referenciando a versão usada.


Validação desta definição: **34 testes aprovados localmente e no [GitHub Actions](https://github.com/danielcdes-create/cogniva/actions/runs/41503303538)**, revisão `ccd40f8ca74f753e2f6b90c30856caa0840893d9`. Evidências sanitizadas em `schema-validation/2026-11-02/`.
