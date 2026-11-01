# Tratamento dos 39 achados

Estado: implementação validada localmente; gates finais e promoção em acompanhamento. Este documento deve receber o resultado operacional antes de declarar encerramento da release. O diagnóstico original e seus bytes permanecem em `auditoria/2026-11-01`.

| Achado | Tratamento implementado | Evidência principal |
|---|---|---|
| C01 | Sessão verificada, finalidade separada, proprietário derivado da sessão, administração restrita e vínculo por prova temporária | Router rejeita ausência/identidade divergente, inclusive query codificada; vínculo e renovação no Mongo sintético |
| C02 | Evento, projeção e recibo atômicos; ACK após write majority/journal; remoção local só dos IDs confirmados | Falha de escrita injetada, ACK vazio e parcial |
| C03 | Evento imutável, replay do recibo, CAS e exclusão mútua dos remetentes locais | Retry, resposta perdida, concorrência e fila de 245 eventos |
| C04 | Questão/revisão/alternativa validadas; acerto calculado no servidor | Cliente afirma acerto de alternativa errada e servidor persiste erro |
| C05 | Gabarito textual legado normalizado; conflito, ausência e múltiplos não viram alternativa A | 618 documentos parseados, 612 respondíveis; seis preservados para revisão |
| C06 | Banco e fila por proprietário; migração aditiva e pendências antigas em revisão | Testes de isolamento e migração SQLite real no emulador |
| C07 | Atualizações de projeção por campo e append de eventos, sem substituição do documento legado | Campos desconhecidos de topo e aninhados sobrevivem a escritas concorrentes |
| C08 | Instante de ocorrência imutável, separado do recebimento; dia civil UTC−03:00 | Retry conserva instante; fixtures dos limites de dia nos dois runtimes |
| C09 | Concursos persistidos no Mongo com revisão e histórico de versões | Reinício do router, leitura persistida, CAS e histórico de revisões |
| C10 | Identidade do concurso inclui proprietário e ID lógico | Outro usuário não lê nem altera o concurso privado |
| C11 | Pull/push de concursos, perfil e aprendizagem; edição pendente preservada | Revisão remota, conflitos, projeção após ACK e pull paginado |
| C12 | Catálogo único compartilhado; rotas antigas projetam o mesmo agregado; revisão canônica explícita com dry-run | Catálogo/fixtures compartilhados e teste de dry-run, CAS e replay do mapeamento |
| C13 | Seleção persistida da escada, foco por conta e exclusão de concursos arquivados | Motores de prioridade/foco e projeções do mesmo perfil |
| C14 | Métricas locais usam histórico persistido e eventos pendentes reais | Histórico vazio produz zero, sem conquistas/tarefas simuladas |
| C15 | SRS, erros, desafio e tronco comum usam seus conjuntos elegíveis também offline | Conjuntos vazios permanecem vazios; tronco comum consulta todas as grades ativas |
| C16 | Sem inferência de tópico por substring; alias exato serve somente à seleção de treino livre | `inferCanonical` retorna ausência; cache antigo não alimenta vínculos inventados |
| C17 | Política comum de domínio, confiança, acurácia e evidência SRS | Mesmas fixtures JSON executadas em Rust e Kotlin |
| C18 | Prática decorre da presença do registro, incluindo domínio zero | Cálculo de cobertura por presença; arquivo de aprendizagem preservado |
| C19 | Acurácia por soma de acertos / soma de tentativas | Contraexemplo real no Mongo sintético: 99/101 = 98,02% |
| C20 | Nota ponderada por questão e regra explícita de desconto; corte desconhecido não gera aprovação | Fixtures 60% e 50%; UI usa a grade congelada na sessão |
| C21 | Simulado respeita grade, tópicos ativos, quantidades e bancas; insuficiência é explícita | Seleção estrita e rota auxiliar ignoram candidatos inventados pelo cliente |
| C22 | Novos eventos usam degraus 1/7/30, com erro/baixa confiança em um dia; legado não é reescrito | Fixtures de sequência, confiança, pontos e intervalo em ambos os runtimes |
| C23 | Agenda por intervalo efetivamente transcorrido; CAS, contadores após mutação e erros propagados | Antes/depois do vencimento, segunda execução sem contagem repetida e falha de escrita |
| C24 | Sessão/contexto/respostas/prazo absoluto duráveis, saves monotônicos e conclusão definitiva | Reabertura de Room preserva snapshot; save atrasado não ressuscita sessão |
| C25 | Publicação aplica diff confirmado/novo/arquivado, usa prática real e revisiona | Publicação idempotente, tópicos arquivados e histórico de revisões no Mongo |
| C26 | Agregado Room transacional; ACK não substitui edição concorrente; cópia antes de resolver conflito | Testes de edição durante envio e exportação exata da edição preservada |
| C27 | Reporte persistido antes da mensagem local; envio e revisão de rejeição separados | Outage mantém reporte; repetição local não duplica a fila |
| C28 | Contingência pré-resposta não usa comentário com gabarito | Testes de dica/simplificação contra marcador de resposta; pós-resposta identificado como comentário existente |
| C29 | Plausibilidade tem cache próprio; geração concorrente é serializada; orçamento por conta | Campos separados e roundtrip de metadados semânticos |
| C30 | Sessão congela proprietário/questões/grade; cancelamento e geração descartam contexto antigo; observação de filas/erros | Snapshot, isolamento, concorrência, ACK atrasado e restauração de sessão |
| C31 | HTTPS real, hash/tamanho, pacote/versão e assinatura verificados; release sem `debuggable` | Certificado válido e renovação; assinatura rotacionada API 28+; limite API 26–27 registrado abaixo |
| C32 | Gates antecedem build/promoção, na mesma revisão; falhas bloqueiam publicação | Runs com gate vermelho não executaram build nem promoção |
| C33 | Imagem por digest, bundle imutável, ponteiro único APK/metadados, prontidão e rollback | Ensaio controlado e promoção efetiva devem constar do resultado operacional |
| C34 | Build local produz somente artefato local versionado; versão deriva do Gradle | Script sem publicação automática nem nome/versão fixos |
| C35 | Readiness verifica Mongo e revisão; recursos têm limites e medições, sem promessas históricas | Docker não-root/estático, gate de tamanho, estado HTTP e inventário dos outros serviços |
| C36 | JVM declara cobertura de fonte/tema em PIXEL; geometria/cutout pertencem ao emulador | Duplo verify, negativo 1×1 restaurado por hash, API 35/Android 15 a 1440×3088/500dpi |
| C37 | Contratos atuais separados da visão e da baseline histórica | README, operação, recuperação e fontes congeladas; renomes das sete notas preservados integralmente |
| C38 | Revisões, quantidades, pesos, catálogo e datas validados antes de gravar | Revisão negativa/máxima, soma sem overflow e data UTF-8 sem panic |
| C39 | DTO completo no cache; ACK atualiza aprendizagem antes de remover fila; pull converge histórico | Roundtrip semântico, ACK parcial e base incompleta sem zerar pontos confirmados |

## Fronteiras de aceite

O software permite revisar os vínculos, mas o banco capturado contém **zero tópicos canônicos confirmados**. Simulados que dependem desses vínculos ficam indisponíveis quando a grade é insuficiente. Atribuir tópicos ou corrigir os seis gabaritos exige revisão pedagógica/fonte; esses fatos não foram inventados nem alterados em produção. Treino livre conserva seleção por rótulos exatos explicitamente conhecidos, sem transformar isso em domínio de um tópico.

A rotação adota chave privada em API 28+. API 26–27 conserva o assinante antigo para atualizar instalações sem desinstalar; essa compatibilidade não elimina o risco da chave antiga nesses sistemas. Não se afirma prova física de instalação/retomada no aparelho: nenhum dispositivo ADB estava conectado. O emulador comprova o perfil numérico API 35 (Android 15), não Android 17/One UI.

## Evidências

Logs e hashes em `remediation-validation/`. Testes de banco usam exclusivamente um Mongo sintético dedicado; as cópias e revisões pedagógicas do banco real ficam fora do Git, em diretório privado. A comparação antes/depois protege identidade e conteúdo dos eventos históricos. A emissão do código de vínculo ocorre somente após a atualização estar disponível e não deve entrar em log versionado.
