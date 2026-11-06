# Arquivo Histórico de Documentação — Cogniva

Este diretório reúne relatórios de marcos concluídos, notas técnicas de etapas intermediárias e históricos de auditorias e correções anteriores.

Para os manuais de arquitetura, operação e schemas ativos, consulte a documentação principal em [`docs/`](../README.md).

---

## 1. Auditoria e Remediação Inicial (01/11/2026)

Documentos relacionados ao fechamento dos 39 achados originais (C01–C39) que estabeleceram a base autenticada, transacional e offline-first do ecossistema:

- **[FECHAMENTO-AUDITORIA.md](auditoria-2026-11-01/FECHAMENTO-AUDITORIA.md):** Tratamento detalhado de cada um dos 39 achados da auditoria inicial e limites de escopo.
- **[REMEDIACAO.md](auditoria-2026-11-01/REMEDIACAO.md):** Registro da execução das correções e validação na release 1.4.0.

---

## 2. Etapas de Evolução e Grok Build (1.4.5 a 1.4.6)

Registros técnicos da transição para o catálogo compartilhado, validação de questões v2 e classificação assistida:

- **[ETAPA-3-BACKEND-MONGO.md](etapas/ETAPA-3-BACKEND-MONGO.md):** Suporte persistente ao schema v2 no MongoDB, validações, índices e outbox.
- **[ETAPA-4-CADASTRO-CATALOGO.md](etapas/ETAPA-4-CADASTRO-CATALOGO.md):** Integração do catálogo de conhecimentos ao cadastro de concursos e etapas no Android.
- **[ETAPAS-5-7-CLASSIFICACAO-SELECAO.md](etapas/ETAPAS-5-7-CLASSIFICACAO-SELECAO.md):** Classificação assistida por IA, seleção de questões por revisão e evidência de domínio.
- **[NOTA-TECNICA-GROK-BUILD-PASSO-5.md](etapas/NOTA-TECNICA-GROK-BUILD-PASSO-5.md):** Especificação técnica profunda e diretrizes para execução das etapas 5 a 7.

---

## 3. Notas de Versão e Correções Intermediárias

Histórico de patches pontuais aplicados em releases anteriores:

- **[CORRECAO-VINCULO-1.4.1.md](releases/CORRECAO-VINCULO-1.4.1.md):** Correção do formulário e contrato de vínculo de contas históricas via Telegram.
- **[CORRECAO-QUESTOES-1.4.2.md](releases/CORRECAO-QUESTOES-1.4.2.md):** Reconciliação do acervo de questões legadas e bloqueio de questões com gabarito ambíguo.
- **[GESTAO-CONCURSOS-1.4.4.md](releases/GESTAO-CONCURSOS-1.4.4.md):** Implementação da tela de gerenciamento de concursos, arquivamento e migração Room 3→4.

---

## 4. Notas Técnicas de Conversão Original (Kotlin/Python → Rust)

Documentos conceituais e diretrizes de arquitetura geradas durante a conversão inicial da stack:

- **[notas-conversao/](notas-conversao/):** Diretório com as 7 notas técnicas sobre pipeline CI/CD, screenshots JVM/Paparazzi, arquitetura de concursos e modelo de dados fundamental.
