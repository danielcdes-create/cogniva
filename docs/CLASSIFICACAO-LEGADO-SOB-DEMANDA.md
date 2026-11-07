# Classificação automática do legado sob demanda

Publicada e ativada: **1.4.7/build 14**, revisão `41d976ee7bf0bab77ea216dc53980c08d01c1ac6`, promovida em 02/11/2026 às 22:59:25 (UTC−03:00). O aceite e a prova real abaixo foram executados com orçamento limitado. A exceção continua restrita ao inventário congelado, sem revisão humana fictícia.

## Escopo fechado

`shared/legacy-classification-cohort.json` congela os 618 IDs do backup privado `20261103T005605Z`, com hash da revisão objetiva de cada questão válida. São 612 elegíveis e seis com gabarito ambíguo, explicitamente bloqueadas. O arquivo não contém enunciados, alternativas, usuários ou respostas históricas. Uma questão nova, mesmo sem campos modernos, não entra na exceção. Conteúdo alterado também não obtém nova chamada automática.

O cadastro normal permanece no schema v2. A migração Mongo `legacy-cohort-guard/1` restringe a antiga permissão de documentos legados a esses IDs. Um ID novo precisa atender ao schema v2 completo. A migração valida o acervo antes, conserva o recibo anterior e retoma uma interrupção entre `collMod` e atualização do recibo; não reescreve questões. Alteração desconhecida de validador bloqueia a preparação. O contrato central v2 e sua exigência de confirmação humana permanecem preservados.

## Acionamento

- Shuffle geral: sorteia um conjunto variado e prioriza as legadas elegíveis ainda não processadas. As questões enviadas entram na fila se houver capacidade. Não há espera por xAI no caminho da resposta. A classificação fica disponível para consultas futuras e sincronização do cache.
- Edital, componente e tronco: cada demanda tenta acrescentar até duas legadas válidas. Nomes de disciplinas apenas ordenam candidatos; não estabelecem classificação nem permitem preencher quotas com assuntos inferidos. A fila pode explorar candidatos quando não há correspondência textual.
- A fila é global e persistente, com até oito IDs distintos, inclusive entre usuários e processos. O worker processa uma questão por vez, com lease global de dois minutos e espera de cinco segundos entre ciclos. Reiniciar consome somente demanda já enfileirada; não inicia varredura paga do acervo.
- Consultas de catálogo, cache e aprendizagem não geram classificação. Shuffle inteiramente offline não pode acionar o servidor; o disparo ocorre numa seleção online posterior. A sessão que já foi enviada mantém seu snapshot.

## Limites de custo

Ativação explícita: `COGNIVA_LEGACY_AUTO_CLASSIFY=1`, chave privada configurada e modelo `grok-4.7`. Sem isso, não há enfileiramento nem chamada. O limite fixo inicial é de **20 reservas globais por dia civil UTC−03:00**, uma oportunidade automática por ID elegível em toda a vida desta política. Nenhum cliente escolhe orçamento, modelo ou reprocessamento.

O orçamento é reservado duravelmente antes de chamar o provedor. Reserva, erro de banco, timeout ou resposta desconhecida não são devolvidos ao orçamento. Uma proposta já existente é reutilizada ou deixada para revisão, conservadoramente consumindo a reserva diária mesmo sem chamada nova. A geração compartilha a reserva do classificador administrativo, impedindo uma segunda chamada concorrente pelo caminho automático. Chamadas administrativas explicitamente autorizadas têm o orçamento administrativo próprio; o teto de 20 aplica-se a esta automação.

Entrada da questão/alternativas limitada a 16.000 bytes, catálogo de candidatos fixo e saída limitada a 2.048 tokens; timeout de 40 segundos, sem retry HTTP automático. Esse teto limita oportunidades e tokens; não representa um valor monetário garantido, pois o preço pertence ao provedor. Com 612 candidatas, o processamento integral não ocorre em um único dia e depende de demanda e revisão de pendências.

Uma falha fica `FAILED_NO_RETRY`; uma reserva antiga sem conclusão comprovada fica `UNKNOWN_NO_RETRY`. Crash após aplicar pode recuperar o recibo a partir do documento, sem nova chamada. Nunca apagar comandos, esvaziar `attempted`, expirar recibos de custo ou regenerar para testar. Reprocessamento excepcional exige ação administrativa explícita e auditável.

## Aplicação automática e proveniência

O prompt existente `classify-question/2` gera uma proposta pendente, preservada integralmente. Uma política adicional e separada pode aplicar apenas propostas atuais, do catálogo correto, com exatamente um principal direto, IDs válidos, até três vínculos e confiança declarada mínima de 0,90 em todos. Confiança é um filtro operacional e não prova de correção. Respostas fora dessas regras permanecem `PENDING_REVIEW` e não alteram a questão.

A aplicação grava `legacy_auto_classification`, regida por `shared/schemas/legacy-auto-classification.schema.json`, com estado `AUTO_APPLIED`, política `legacy-618-auto/1`, origem `AI`, método `AI_AUTOMATIC_LEGACY`, modelo, prompt/hash, revisão do conteúdo, justificativas, instante e ID da proposta. Não grava `classification.state=CONFIRMED` nem inventa revisor humano. A proposta permanece disponível para revisão humana pelo fluxo administrativo existente. Uma classificação humana posterior prevalece sobre a extensão automática.

Os campos singulares canônicos espelham o principal. O adaptador só os aceita pela exceção quando inventário, revisão, recibo, catálogo, política e espelhos correspondem. Metadados automáticos desatualizados retiram a questão da prática até revisão. O servidor pode marcar o vínculo como verificado pela política autorizada; isso não equivale a revisão humana. Backend e cache Android aproveitam essa mesma marcação, mantendo crédito por tentativa/revisão e sem domínio integral por secundário.

Enunciado, alternativas, gabarito, validade, origem, caches de tutores e eventos históricos permanecem intactos. A classificação não certifica vigência de lei nem resolve gabarito. Antes da aplicação, o comando guarda o documento original completo; o CAS compara o snapshot inteiro para não sobrescrever edição, revisão humana ou cache produzido concorrentemente. Os seis gabaritos ambíguos continuam fora da prática.

## Operação e aceite

Inspeção sem chamada ao provedor, somente via operador autenticado na VPS:

```sh
docker exec api-rust-core /usr/local/bin/app --legacy-classification-status
```

O resultado contém contagens, política, teto diário, reservas, fila e estados dos trabalhos; não contém chave, conteúdo ou dados de usuário. Desativar a variável e recriar somente o Cogniva interrompe novas reservas, conservando fila, auditoria e classificações já aplicadas. Uma chamada já enviada pode concluir.

O aceite de release é feito inicialmente com a automação desligada, comparando questões/concursos em bytes e o histórico protegido. Depois da ativação, uma seleção controlada pode gerar custo real autorizado. A captura posterior deve usar `verify-question-storage-backup.py --authorized-legacy-auto-classification`: esse modo admite apenas a nova extensão e os dois espelhos, valida associação ao inventário e exige todos os demais campos, concursos e histórico preservados. Não usar igualdade total após uma aplicação autorizada nem relaxar o comparador de classificação humana.

Testes usam exclusivamente Mongo sintético e xAI simulada: lista fechada, seis bloqueadas, qualidade da proposta, fila concorrente, limite persistente, reinício, erro sem repetição, recibo recuperável, prioridade, seed, preservação e instalação/recovery do guard Mongo.

## Aceite executado em produção

- [Pipeline 41561247262](https://github.com/danielcdes-create/cogniva/actions/runs/41561247262): seis jobs aprovados na mesma revisão; 40 testes Rust, 47 contratos Python, JVM/lint, nove imagens golden e 11 testes instrumentados. Imagem/APK construídos e assinados no CI, ensaio controlado de rollback aprovado e promoção conferida externamente. O run anterior `41560821631` foi cancelado antes do build/promoção para corrigir gravações ociosas da fila; não é a evidência desta publicação.
- APK: 12.842.035 bytes, SHA-256 `1549916af29398b02094af0356e0c5b8c835a3988574507bcc129c6a05383d61`, versão 1.4.7/build 14; bytes do download público idênticos ao bundle, assinante privado API 28–35 e lineage preservados, sem debuggable. Runtime: 30.722.984 bytes, abaixo de 50 MB. Sem dispositivo ADB conectado; não se afirma instalação física.
- Aceite inicial com automação desligada: 618 questões e quatro concursos idênticos em bytes, histórico protegido preservado e guard Mongo instalado. Catálogo, cache/projeção, modos pessoais, health/readiness e 401 anônimo conferidos, sem chamada paga nessa fase.
- Ativação explícita no ambiente privado, com cópia privada da configuração anterior; somente Cogniva recriado, mantendo imagem, Compose e demais containers. A evidência `automation-activation.json` comprova flag ativa e guard instalado.
- Prova real: a única chamada real gerou e persistiu uma proposta com confiança 0,80, abaixo do limiar 0,90: ficou PENDING_REVIEW. A observação posterior manteve uma única reserva, sem repetição automática; não foi forçado um novo sorteio para obter aplicação favorável. Reservas no dia da captura: **1 de 20**; não foram enviadas tentativas de aprendizagem. O limite diário é conservador e não equivale a uma cobrança monetária apurada.
- Inventário após o aceite: **0 aplicações automáticas**, **612 legadas sem classificação** e **6 gabaritos ambíguos**. Aplicação automática não é confirmação humana nem significa que todo o acervo foi classificado. A evolução posterior depende de demanda, orçamento e revisão das ambiguidades.
- Comparação após ativação: mudanças admitidas apenas na extensão automática e nos dois espelhos canônicos, com inventário, hashes e schema verificados. Todos os demais campos originais, quatro concursos e o histórico protegido permaneceram preservados. O recibo guarda o original completo no Mongo; backups BSON ficam privados fora do Git.

As opções de coleção passaram a ser exportadas como Extended JSON, preservando os ObjectIds introduzidos pelo guard. A primeira captura `post-promotion-1.4.7-disabled` usava a antiga serialização em texto e não serviu à comparação tipada do validador. A nova captura `post-promotion-1.4.7-disabled-typed` resolveu a divergência de representação, sem alterar banco ou recibos; ambas foram preservadas para rastreabilidade.

Evidências: `remediation-validation/1.4.7/`; baseline imediatamente anterior em `before-final/`; inventários privados de backup referenciados pelos relatórios de preservação. O commit posterior de documentação não altera o runtime `41d976ee7bf0bab77ea216dc53980c08d01c1ac6`.
