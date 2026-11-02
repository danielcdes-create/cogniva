# Tratamento dos 39 achados

Fechamento inicial em 01/11/2026: tratamento de C01–C39 implementado e release 1.4.0/build 7 entregue. Gates, assinatura, rollback controlado, promoção e aceite externo concluídos na revisão `d8716db8c12653a726c9284b9e28e7ba1c30df64`. A versão atual é **1.4.3/build 10**, com as correções adicionais de [Ajustes](CORRECAO-VINCULO-1.4.1.md) e [acesso às questões](CORRECAO-QUESTOES-1.4.2.md), seis testes de emulador e aceite na API real concluído. A [migração xAI](IA-XAI.md) acrescenta cache confirmado por recurso e prova real com Grok 4.3. As fronteiras de dados e de aparelho físico abaixo permanecem explícitas. O diagnóstico original e seus bytes permanecem em `auditoria/2026-11-01`.

## Resultado operacional da primeira release (1.4.0)

- [Run 41343484265](https://github.com/danielcdes-create/cogniva/actions/runs/41343484265): todos os cinco jobs aprovados. Backend com 24 testes; Android/JVM, lint e golden aprovados; três testes reais no emulador API 35/Android 15, 1440×3088/500dpi com cutout.
- Log de promoção registra `ROLLBACK_HEALTHY`, `CONTROLLED_ROLLBACK_VERIFIED` e promoção efetiva às 20:04:07 UTC. O gate de manutenção está aberto e acesso anônimo a dados retorna 401.
- HTTPS health/readiness anunciam a revisão esperada e contrato 2; readiness confirma Mongo. Duas leituras públicas de metadados produziram o mesmo resultado e o APK baixado externamente corresponde ao hash e tamanho do bundle assinado.
- APK: 12.708.853 bytes, versão 1.4.0/build 7, SHA-256 `1abfb764909dd4274e351a2c30e3471ba41ab3b11842adf2dd1dc3389bf35c35`. Assinatura e pacote `br.com.concursoprep.app` verificados localmente; sem `debuggable`.
- Runtime: 25.808.311 bytes; usuário 65532, filesystem somente leitura, capacidades removidas, limite 160 MiB/0,75 CPU. Amostra às 20:05 UTC: 1,793 MiB e 0,05% CPU, sem carga representativa; não constitui garantia de capacidade.
- Antes/depois: 15 documentos e 27 eventos; hash protegido idêntico `a34574d29433a312c494026a3c9fc84291bde7fbe05783675caf79a10caf50c7`. Compose compartilhado e IDs de FIN, Asbuildo e Spamshield preservados. Backups BSON/índices/views ficam fora do Git.
- Renovação TLS ativa e última execução bem-sucedida. Gemini explicitamente configurado para modelo disponível, sem chamada de geração paga. O Mongo sintético local foi encerrado depois de verificar seu executável e porta; nenhum serviço de banco foi instalado.

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
| C15 | SRS, erros e desafio usam histórico global elegível, sem herdar a aba arquivada; disponibilidade e revisão pendente convergem no pull/cache | Mongo real sintético, escopos online/offline e API real: 3 SRS, 3 erros, 4 desafio; conjuntos vazios não viram treino comum |
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
| C33 | Imagem por digest, bundle imutável, ponteiro único APK/metadados, prontidão e rollback | Ensaio controlado e promoção efetiva aprovados; log `ci-promote-d8716db.log` |
| C34 | Build local produz somente artefato local versionado; versão deriva do Gradle | Script sem publicação automática nem nome/versão fixos |
| C35 | Readiness verifica Mongo e revisão; recursos têm limites e medições, sem promessas históricas | Docker não-root/estático, gate de tamanho, estado HTTP e inventário dos outros serviços |
| C36 | JVM declara cobertura de fonte/tema em PIXEL; geometria/cutout pertencem ao emulador | Duplo verify, negativo 1×1 restaurado por hash, API 35/Android 15 a 1440×3088/500dpi |
| C37 | Contratos atuais separados da visão e da baseline histórica | README, operação, recuperação e fontes congeladas; renomes das sete notas preservados integralmente |
| C38 | Revisões, quantidades, pesos, catálogo e datas validados antes de gravar | Revisão negativa/máxima, soma sem overflow e data UTF-8 sem panic |
| C39 | DTO completo no cache; ACK atualiza aprendizagem antes de remover fila; pull converge histórico e disponibilidade sem apagar referências antigas | Roundtrip, ACK parcial, base incompleta e abertura/resposta de questão legada no emulador; oito registros mantidos, quatro questões disponíveis |

## Fronteiras de aceite

O software permite revisar os vínculos, mas o banco capturado contém **zero tópicos canônicos confirmados**. Simulados que dependem desses vínculos ficam indisponíveis quando a grade é insuficiente. Atribuir tópicos ou corrigir os seis gabaritos exige revisão pedagógica/fonte; esses fatos não foram inventados nem alterados em produção. Treino livre conserva seleção por rótulos exatos explicitamente conhecidos, sem transformar isso em domínio de um tópico.

A rotação adota chave privada em API 28+. API 26–27 conserva o assinante antigo para atualizar instalações sem desinstalar; essa compatibilidade não elimina o risco da chave antiga nesses sistemas. Não se afirma prova física de instalação/retomada no aparelho: nenhum dispositivo ADB estava conectado. O emulador comprova o perfil numérico API 35 (Android 15), não Android 17/One UI.

## Evidências

Logs e hashes em `remediation-validation/`: `ci-*-d8716db.log`, `production-release.json`, `production-pre-promotion.json`, `production-after.json`, `release-signature.log`, `release-badging.log` e `visual-gate.json`. `scripts/verify-production-release.py` repete o aceite somente leitura e baixa o artefato fora do repositório. Testes de banco usam exclusivamente um Mongo sintético dedicado; as cópias e revisões pedagógicas do banco real ficam fora do Git, em diretório privado. A comparação antes/depois protege identidade e conteúdo dos eventos históricos. A emissão do código de vínculo ocorre somente após a atualização estar disponível e não deve entrar em log versionado.
