# Integração com IA (xAI) — Tutores, Consulta Contextual em Camada, Blindagem e Governança de Cotas

O **Cogniva `2.4.2`** integra a API da **xAI** de forma estritamente governada no backend Rust (`cogniva-api`), atendendo aos **tutores pedagógicos por questão** (incluindo `term-lookup` e `term-lookup-post` para consulta contextual em camada), à **assistência de importação de questões** e à **geração de propostas de classificação V2**, além da rota gratuita de consulta normativa/enciclopédica `POST /api/v1/questions/:id/lookup`.

---

## 1. Configuração Explícita, Segurança de Prompt e Privacidade de Cache

- **Sem Modelo Hardcoded no Código:** Se `XAI_API_KEY` estiver configurada no ambiente, `XAI_MODEL` **deve** ser declarada explicitamente. Caso `XAI_API_KEY` esteja presente sem `XAI_MODEL`, o backend recusa iniciar com erro claro de configuração (`config.rs`).
- **Isolamento de Segredos:** A chave `XAI_API_KEY` reside exclusivamente no servidor; nenhum segredo ou token da xAI é embarcado no APK Android ou nos assets web.
- **Defesa contra Injeção Indireta de Prompt:** Textos livres fornecidos por estudantes (`user_note`, respostas discursivas) são encapsulados em `<untrusted_student_input>` e textos brutos de importação em `<untrusted_raw_source>`, com neutralização de tags de fechamento aninhadas (`</untrusted_`) e diretrizes explícitas em `pt-BR` no *system prompt* tratando blocos delimitados exclusivamente como dados passivos.
- **Privacidade Estrita de Cache (`should_use_shared_ai_cache`):** Sempre que uma chamada ao tutor inclui anotação individual (`user_note`) ou resposta discursiva do estudante, o backend **jamais lê nem grava** o resultado no cache compartilhado em `todasQuestoes` (`from_cache: false`), garantindo sigilo entre estudantes (consulte [`PRIVACY.md`](../PRIVACY.md)).

---

## 2. Dupla Proteção de Orçamento (`UTC-3`) e Estorno em Falhas

Para impedir exaustão de créditos ou abuso por criação automatizada de sessões convidadas (*Sybil*), [`services/rate_limit.rs`](../backend-rust/src/services/rate_limit.rs) impõe cotas atômicas simultâneas na coleção `tutor_budgets` usando chaves diárias no fuso oficial de estudo **`America/Sao_Paulo` (`UTC-3`)**:

1. **Cota Diária por Conta (`XAI_DAILY_USER_LIMIT`):** Padrão de `120` gerações novas por dia para contas registradas e teto reduzido de **`5` gerações/dia para contas convidadas (`is_guest`)**.
2. **Teto Global Diário (`XAI_DAILY_GLOBAL_LIMIT`):** Padrão de `500` gerações novas por dia somando todo o servidor.

- **Cache Hit Custo Zero:** Consultar explicações, dicas, mnemônicos ou análises impessoais já gravadas em `todasQuestoes` retorna imediatamente do MongoDB (`from_cache = true`) sem consumir cota.
- **Estorno Automático (`release_xai_quota`):** Caso o provedor xAI retorne erro HTTP, timeout ou resposta inválida tanto nos tutores (`ai_tutor.rs`) quanto na estruturação assistida de importação (`question_import.rs`), a reserva é imediatamente estornada na cota do usuário e no teto global.

---

## 3. Os 8 Tutores Pedagógicos, Bússola Pré-Resposta e `ai_resource_meta`

Os recursos pedagógicos cacheados em `cogniva.todasQuestoes` (apenas quando impessoais, versão de prompt `ai-tutor/3`) dividem-se estritamente entre **ajuda pré-resposta** e **contexto pós-resposta**:

### 3.1 Tutores Pré-Resposta (Alinhados à Banca, Zero Vazamento de Gabarito)
Em questões de concursos públicos (FGV, Cebraspe, FCC, divergências STF/STJ), omitir o gabarito do tutor aumentaria o risco de o modelo inferir a alternativa errada e induzir o estudante ao distrator. Por isso, [`services/ai_tutor.rs`](../backend-rust/src/services/ai_tutor.rs) aplica **defesa em 3 camadas**:

1. **Bússola Pedagógica Interna Sigilosa (`bussola_gabarito_sigiloso_nao_revelar`):** O gabarito oficial é enviado dentro de `<untrusted_raw_source>` sob chave semântica distinta (`bussola_gabarito_sigiloso_nao_revelar`, nunca `correct_answer`), instruindo o modelo a consultá-lo **apenas em silêncio** para saber com 100% de certeza qual instituto, dispositivo, jurisprudência ou eixo interpretativo a banca adotou — eliminando dicas contraditórias ou alucinações.
2. **Técnica de "Um Nível de Abstração Acima" (`PRE_ANSWER_NO_SPOILER_GUARDRAIL_PT_BR`):** O *system prompt* e o *user prompt* instruem o modelo a converter o ponto cobrado pela banca em contextualização geral do instituto, simplificação estritamente simétrica/neutra das opções (sem letras `A`, `B`, `C`) e perguntas-guia abertas — proibindo revelar, insinuar, confirmar ou eliminar alternativas/gabarito (inclusive em itens `Certo/Errado` ou diante de pedidos explícitos em `user_note`).
3. **Filtro Determinístico Anti-Spoiler em Rust (`detect_pre_answer_spoiler` + Retry Corretivo):**
   - **Na geração (`generate_verified_tutor_text`):** Antes de devolver ao cliente ou persistir em `todasQuestoes`, o backend inspeciona a resposta em busca de marcadores explícitos de entrega de gabarito (`✅`/`❌`, cabeçalhos `Gabarito:`/`Resposta correta:`, frases declarativas como *"a alternativa correta é"*, indicação de letra `A–E`, julgamentos conclusivos *"o item está certo/errado"* ou menção ao nome do campo interno). Se detectado, o backend executa **1 regeneração corretiva automática** na mesma reserva de cota; se persistir, bloqueia a resposta sem gravar em cache e estorna a cota (`release_xai_quota`).
   - **Na leitura de cache legado:** Textos pré-resposta antigos em `todasQuestoes` também passam por `detect_pre_answer_spoiler`; se algum registro legado contiver spoiler explícito, o cache é ignorado e substituído por uma geração limpa `ai-tutor/3`.
   - **No cliente Android ([`ConcursoRepository.kt`](../android-app/app/src/main/java/br/com/concursoprep/app/data/repository/ConcursoRepository.kt)):** Nenhum dos três tutores pré-resposta (`contextual-hint`, `child-friendly-pre`, `dr-sincero`) faz fallback para `explanation` quando offline ou sob falha HTTP.

Os 3 tutores pré-resposta são:
1. **Ajuda pré-resposta (`child-friendly-pre` / `ajudaInfantil`):** Estrutura-se em *Contexto do Tema*, *Enunciado Simplificado* (destacando termos de comando como `EXCETO`, `INCORRETA`, `APENAS`, `NÃO`), *Leitura Neutra das Opções* e *Orientação de Raciocínio* com perguntas-guia abertas;
2. **Dicas contextuais progressivas (`contextual-hint` / `contextualHint`):** Até 3 níveis progressivos alinhados à bússola interna (`#1` contextualização do instituto → `#2` recorte e comando → `#3` critério de análise em forma de pergunta aberta), enviando apenas as dicas anteriores ao índice solicitado;
3. **Dr. Sincero pré-resposta (`dr-sincero` / `drSincero`):** Alerta ácido e direto sobre pegadinhas clássicas da banca nesse ponto da matéria e armadilhas de leitura apressada, sem resolver a questão pelo candidato;
4. **Consulta Contextual de Trecho Pré-Resposta (`term-lookup`):** Explica o significado conceitual/normativo de um termo ou trecho selecionado pelo aluno em `<untrusted_student_input>` sob guarda estrita anti-spoiler (`detect_pre_answer_spoiler`), jamais gravando em cache compartilhado.

### 3.2 Tutores Pós-Resposta (Recebem e Fundamentam `correct_answer`)
Como o estudante já submeteu sua resposta, o payload inclui explicitamente `correct_answer` e autoriza fundamentar o gabarito oficial (sem depender de letras fixas `A`, `B`, `C`, pois as alternativas são embaralhadas):
5. **Contexto adicional pós-resposta (`extra-concepts` / `explanationExtra`):** Situa o tema dentro da disciplina real da questão, explica o fundamento da resposta correta (`correct_answer`), aprofunda conceitos-chave e distinções essenciais, apresenta exemplos práticos coerentes com a área e resume como o tópico costuma cair em prova;
6. **Explicação simples pós-resposta (`child-friendly-post` / `explicacaoInfantil`):** Traduz a ideia central com analogias do cotidiano, demonstra por que `correct_answer` faz sentido e fecha com um resumo de fixação;
7. **Explicação detalhada por alternativa (`explanation`):** Panorama da questão seguido de análise individual (`✅:` na correta / `❌:` nas incorretas);
8. **Mnemônico (`mnemonic` / `mnemonico`):** Recurso de memorização ancorado nos requisitos ou conceitos que sustentam `correct_answer`;
9. **Plausibilidade dos distratores (`plausibility` / `plausibilityMarkdown`):** Classificação das alternativas incorretas (`Improvável`, `Parcialmente plausível`, `Plausível`) em face de `correct_answer` e de comandos como `NÃO`/`EXCETO`;
10. **Consulta Contextual de Trecho Pós-Resposta (`term-lookup-post`):** Explica diretamente o trecho selecionado à luz do gabarito oficial e da Ficha Editorial, sem leitura/escrita em cache compartilhado.

- **Exclusão Mútua por Questão/Recurso:** Solicitações concorrentes impessoais para o mesmo recurso da mesma questão são serializadas em processo e releem o documento antes de chamar a xAI.
- **Proveniência Estruturada (`ai_resource_meta`):** Toda gravação compartilhada registra metadados conformes a `#/$defs/ai_resource_metadata` (`provider: "xai"`, `model`, versão do prompt, `content_revision` JCS SHA-256 avaliado e timestamp UTC). Se a questão for editada durante a chamada externa, a escrita condicional CAS impede gravar texto gerado sobre enunciado antigo.

---

## 4. Curadoria Assistida no `/admin/`

- **Propostas de Classificação V2 (`/api/v1/admin/classification/proposals`):** Gera sugestões estruturadas sobre o catálogo ativo `knowledge-catalog.json`, sempre como `PENDING` até revisão humana na aba **Classificação IA** (com rotação automática de `operation_id` no cliente em caso de falha transitória).
- **Estruturação e Geração de Rascunhos (`/api/v1/admin/questions/import/assist`):** Converte texto bruto delimitado em `<untrusted_raw_source>` para rascunhos JSON com proveniência `AI`, exigindo revisão e confirmação humana antes de inserir no acervo.
