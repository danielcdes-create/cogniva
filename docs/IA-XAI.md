# Integração com IA (xAI) — Tutores, Classificação e Governança de Cotas

O **Cogniva `2.0.0`** integra a Responses API da **xAI** de forma estritamente governada no backend Rust (`cogniva-api`), atendendo a três fluxos: os **8 tutores pedagógicos por questão**, a **assistência de importação de questões** e a **geração de propostas de classificação V2**.

---

## 1. Configuração Explícita e Cliente Compartilhado

- **Sem Modelo Hardcoded no Código:** Se `XAI_API_KEY` estiver configurada no ambiente, `XAI_MODEL` **deve** ser declarada explicitamente (ex.: `XAI_MODEL=grok-4.20-non-reasoning`). Caso `XAI_API_KEY` esteja presente sem `XAI_MODEL`, o backend recusa iniciar com erro claro de configuração (`config.rs`).
- **Isolamento de Segredos:** A chave `XAI_API_KEY` reside exclusivamente no servidor; nenhum segredo ou token da xAI é embarcado no APK Android ou nos assets web.
- **Conexão Reutilizada:** Todas as chamadas xAI compartilham um único `reqwest::Client` com TLS `rustls` e timeout de 40 segundos mantido em `AppState`.

---

## 2. Dupla Proteção de Orçamento e Estorno em Falhas

Para impedir exaustão acidental de créditos ou abuso por criação massiva de sessões convidadas (*Sybil*), [`services/rate_limit.rs`](../backend-rust/src/services/rate_limit.rs) impõe duas cotas atômicas simultâneas na coleção `ai_daily_quotas` (fuso `UTC`):

1. **Cota Diária por Conta (`XAI_DAILY_USER_LIMIT`):** Padrão de `120` gerações novas por dia por `user_id`.
2. **Teto Global Diário (`XAI_DAILY_GLOBAL_LIMIT`):** Padrão de `500` gerações novas por dia somando todo o servidor.

- **Cache Hit Custo Zero:** Consultar explicações, dicas, mnemônicos ou análises de plausibilidade já gravadas em `todasQuestoes` retorna imediatamente do MongoDB (`from_cache = true`) sem consumir cota.
- **Estorno Automático (`release_tutor`):** A reserva é efetuada usando a chave de dia capturada no início da operação; caso o provedor xAI retorne erro HTTP, timeout ou resposta vazia/incompleta, a reserva é estornada tanto na cota do usuário quanto no teto global, e nada inválido é persistido no acervo.

---

## 3. Os 8 Tutores Pedagógicos e `ai_resource_meta`

Os recursos pedagógicos cacheados em `cogniva.todasQuestoes` são:
1. Explicação detalhada pós-resposta (`explanation`);
2. Dicas contextuais progressivas pré-resposta (`contextualHint`, sem revelar o gabarito);
3. Dr. Sincero (`drSincero`);
4. Simplificação pré-resposta (`childFriendlyPre`, nunca faz fallback para `explanation` quando offline);
5. Explicação didática pós-resposta (`childFriendlyPost`);
6. Conceitos extras (`extraConcepts`);
7. Mnemônico (`mnemonico`);
8. Plausibilidade das alternativas (`plausibilityMarkdown`).

- **Exclusão Mútua por Questão/Recurso:** Solicitações concorrentes para o mesmo recurso da mesma questão são serializadas em processo e releem o documento antes de chamar a xAI.
- **Proveniência Estruturada (`ai_resource_meta`):** Toda nova geração grava metadados auditáveis no documento da questão registrando `provider: "xai"`, `model` efetivo (`XAI_MODEL`), versão do prompt, `content_revision` JCS SHA-256 avaliado e timestamp UTC de geração. Se a questão for editada durante a chamada externa, a escrita condicional CAS impede gravar texto gerado sobre enunciado antigo.

---

## 4. Curadoria Assistida no `/admin/`

- **Propostas de Classificação V2 (`/api/v1/admin/classification/proposals`):** Gera sugestões estruturadas apontando para disciplinas, assuntos e subassuntos ativos do catálogo `knowledge-catalog.json` vigente, sempre como `PENDING` até confirmação ou rejeição humana na aba **Classificação IA**.
- **Estruturação e Geração de Rascunhos (`/api/v1/admin/questions/import/assist`):** Converte texto bruto em rascunhos JSON ou sugere itens autorais com proveniência `AI`, exigindo revisão e confirmação explícitas antes de inserir no acervo.
