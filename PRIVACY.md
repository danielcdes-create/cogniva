# Política de Privacidade e Proteção de Dados (LGPD) — Cogniva

Esta política descreve de forma transparente como o ecossistema **Cogniva** (aplicativo Android, Portal Administrativo Web e API `cogniva-api`) trata dados pessoais em conformidade com a **Lei Geral de Proteção de Dados Pessoais — LGPD (Lei nº 13.709/2018)**.

---

## 1. Dados Coletados e Finalidades (Arts. 6º e 7º da LGPD)

O Cogniva adota o princípio da **minimização de dados**, coletando exclusivamente o necessário para a execução da plataforma de estudos, simulados e repetição espaçada:

| Categoria de Dado | Exemplos | Finalidade Específica |
| :--- | :--- | :--- |
| **Identificação e Autenticação** | Identificador interno de conta (`user_id`), nome de exibição, e-mail (para contas registradas) e hash de senha com **Argon2id**; ou conta convidada local vinculável | Autenticação segura de sessão, sincronização *offline-first* entre dispositivos e recuperação/vínculo de progresso. |
| **Histórico Pedagógico e Metas** | Tentativas de resposta (`userActivityLog`), recibos idempotentes (`attempt_receipts`), nível de confiança, tempo de resolução, anotações pessoais (`user_note`) e grades personalizadas (`user_exams`) | Cálculo local e em nuvem de repetição espaçada (SRS), caderno de erros, índice de prontidão e estatísticas de desempenho exclusivamente para o próprio titular. |
| **Registros Operacionais de Segurança** | Sessões autenticadas (`sessions`) com expiração automática, contadores diários de orçamento de IA (`tutor_budgets`) no fuso `America/Sao_Paulo` e proteção anti-abuso por IP | Segurança da informação, prevenção à fraude e proteção orçamentária da infraestrutura (Art. 7º, IX da LGPD). |

O Cogniva **não coleta** localização GPS, agenda de contatos, microfone, câmera, identificadores publicitários nem comercializa dados pessoais com terceiros.

---

## 2. Uso de Inteligência Artificial e Transferência Internacional (`xAI`)

- Quando o estudante aciona voluntariamente recursos do **Tutor IA** (dicas contextuais, explicações, análise de alternativas, consulta contextual de termos/trechos `term-lookup` / `term-lookup-post`, correção discursiva assistida ou modo `dr-sincero`), o enunciado da questão e, quando preenchida pelo titular, a anotação de estudo ou trecho consultado (`user_note`) ou resposta discursiva são transmitidos sob TLS 1.2/1.3 para a API da **xAI** (`api.x.ai`, localizada no exterior) exclusivamente para gerar o retorno pedagógico solicitado.
- A consulta rápida de trechos (`POST /api/v1/questions/:id/lookup`) prioriza o catálogo normativo local/servidor e síntese aberta da Wikimedia PT sem enviar dados pessoais à xAI; quando o estudante opta pela pesquisa externa em camada (Partial Custom Tab Google / Planalto / STF-STJ), a navegação ocorre diretamente no navegador do aparelho sobreposto à questão.
- As chamadas utilizam delimitação estrutural contra injeção de prompt (`<untrusted_student_input>`) e respostas personalizadas contendo notas individuais do estudante **jamais são gravadas** no cache compartilhado de explicações (`ai_explanations`).
- Recomenda-se não inserir dados pessoais sensíveis, senhas ou informações confidenciais em anotações livres de estudo.

---

## 3. Direitos do Titular e Exclusão Imediata da Conta (Art. 18 da LGPD)

Em cumprimento ao **Art. 18, incisos IV e VI da LGPD**, o titular pode exercer a eliminação imediata de sua conta e de todos os seus dados pessoais a qualquer momento, **diretamente pelo aplicativo Android** (em **Ajustes → Privacidade e LGPD → Excluir minha conta e dados pessoais**, confirmando `EXCLUIR`) ou via endpoint autenticado `DELETE /api/v1/auth/account` com corpo `{"confirm": "EXCLUIR"}`:

1. **Eliminação Física Imediata dos Dados Pessoais:** A confirmação remove permanentemente do banco de dados `cogniva` em nuvem e do armazenamento criptografado local do dispositivo:
   - Conta, credenciais Argon2id e preferências do titular (`usuarios`) e todas as sessões ativas (`sessions`);
   - Todo o histórico de resoluções, recibos de sincronização, avaliações discursivas e métricas SRS (`userActivityLog`, `attempt_receipts` e `answer_evaluations`);
   - Concursos, editais e compatibilidades personalizadas pelo titular (`user_exams` e `question_exam_compatibility`);
   - Orçamentos individuais de IA, rascunhos privados e operações vinculadas ao titular (`tutor_budgets`, `catalog_editor_drafts` e `question_import_ai_requests`).
2. **Preservação Integral do Acervo Editorial Compartilhado:** O banco compartilhado de questões (`todasQuestoes`), o histórico técnico de revisões editoriais (`question_revision_events`), os modelos públicos de provas (`exam_templates`) e o catálogo canônico de disciplinas e tópicos (`knowledge_catalog_*`) constituem acervo pedagógico impessoal e permanecem 100% preservados. Eventuais relatos de revisão de gabarito anteriormente enviados (`reportedQuestoes`) têm os identificadores do autor desvinculados/anonimizados, mantendo-se apenas o apontamento técnico sobre a questão.

Consulte também a [Política de Segurança](SECURITY.md) e a documentação técnica em [docs/README.md](docs/README.md).
