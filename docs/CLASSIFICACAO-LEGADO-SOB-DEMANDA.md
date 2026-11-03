# Classificação automática do legado sob demanda

Entrega 1.4.7/build 14 em validação. Este documento registra a exceção autorizada pelo proprietário para o acervo antigo; publicação e aceite serão registrados ao concluir os gates.

## Escopo fechado

`shared/legacy-classification-cohort.json` congela os 618 IDs do backup privado `20261103T005605Z`, com hash da revisão objetiva de cada questão válida. São 612 elegíveis e seis com gabarito ambíguo, explicitamente bloqueadas. O arquivo não contém enunciados, alternativas, usuários ou respostas históricas. Uma questão nova, mesmo sem campos modernos, não entra na exceção. Conteúdo alterado também não obtém nova chamada automática.

O cadastro normal permanece no schema v2. A migração Mongo `legacy-cohort-guard/1` restringe a antiga permissão de documentos legados a esses IDs. Um ID novo precisa atender ao schema v2 completo. A migração valida o acervo antes, conserva o recibo anterior e retoma uma interrupção entre `collMod` e atualização do recibo; não reescreve questões. Alteração desconhecida de validador bloqueia a preparação. O contrato central v2 e sua exigência de confirmação humana permanecem preservados.

## Acionamento

- Shuffle geral: sorteia um conjunto variado e prioriza as legadas elegíveis ainda não processadas. As questões enviadas entram na fila se houver capacidade. Não há espera por xAI no caminho da resposta. A classificação fica disponível para consultas futuras e sincronização do cache.
- Edital, componente e tronco: cada demanda tenta acrescentar até duas legadas válidas. Nomes de disciplinas apenas ordenam candidatos; não estabelecem classificação nem permitem preencher quotas com assuntos inferidos. A fila pode explorar candidatos quando não há correspondência textual.
- A fila é global e persistente, com até oito IDs distintos, inclusive entre usuários e processos. O worker processa uma questão por vez, com lease global de dois minutos e espera de cinco segundos entre ciclos. Reiniciar consome somente demanda já enfileirada; não inicia varredura paga do acervo.
- Consultas de catálogo, cache e aprendizagem não geram classificação. Shuffle inteiramente offline não pode acionar o servidor; o disparo ocorre numa seleção online posterior. A sessão que já foi enviada mantém seu snapshot.

## Limites de custo

Ativação explícita: `COGNIVA_LEGACY_AUTO_CLASSIFY=1`, chave privada configurada e modelo `grok-4.3`. Sem isso, não há enfileiramento nem chamada. O limite fixo inicial é de **20 reservas globais por dia civil UTC−03:00**, uma oportunidade automática por ID elegível em toda a vida desta política. Nenhum cliente escolhe orçamento, modelo ou reprocessamento.

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
