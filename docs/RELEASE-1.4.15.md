# Release 1.4.15 / build 22

Preparada em 07/11/2026. Android **1.4.15/build 22**, backend **1.4.15**, contrato de sincronização 2. Promoção e aceite serão registrados após a conclusão do pipeline.

## Conteúdo

- Guia de relações entre troncos, concursos, edital, grade, componentes, disciplinas, assuntos, subassuntos e questões no painel e no Android.
- Troncos mostram concursos da conta; disciplinas mostram relações com troncos e componentes das grades. Caminhos de subassuntos incluem o pai nos seletores e na consulta.
- Cadastro individual com prova/cargo/caderno, instituição, banca, ano, número, nível, fonte documental, conferência de origem e explicação. Mesmos campos no Android, painel e API, com recuperação de rascunhos anteriores.
- Origem oficial com requisitos documentais do schema v2. Origem, classificação e validade têm confirmações independentes; cadastro não altera questões e histórico anteriores.
- Novas sessões web duram **30 dias**: JWT, cookie e expiração persistida concordam. Logout continua revogando a sessão. Sessões emitidas anteriormente mantêm sua expiração até novo login.

## Validação e publicação

Testes locais e do CI, revisão promovida, hash/tamanho do APK, preservação do banco e saúde dos serviços serão registrados neste documento após o aceite. A release anterior permanece documentada em [1.4.14](RELEASE-1.4.14.md).

Referências: [estrutura dos estudos](ESTRUTURA-ESTUDOS.md), [administração](ADMIN-WEB.md) e [inserção de questões](INSERCAO-QUESTOES.md).
