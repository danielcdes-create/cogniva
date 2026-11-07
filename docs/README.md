# Documentação do Ecossistema Cogniva

Este diretório centraliza os documentos de referência técnica, manuais operacionais, especificações de dados e registros de auditoria do sistema **Cogniva** (`concursoprep`).

---

## 🗺️ Mapa de Documentação Ativa

### 1. Operação, Infraestrutura e Distribuição
- **[OPERACAO.md](OPERACAO.md):** Manual completo de operação em produção na VPS DigitalOcean. Cobre limites de recursos, credenciais seguras, gestão de gates de deploy, verificação de releases, procedimentos de rollback atômico, renovação de certificados TLS (Let's Encrypt) e diagnóstico do banco.
- **[LICAO-APRENDIDA-OTA-APK.md](LICAO-APRENDIDA-OTA-APK.md):** Pós-morte e invariantes de engenharia do pipeline de atualização OTA. Registra o diagnóstico da recompressão gzip indevida de binários, regras de streaming direto (`proxy_buffering off`, `no-transform`), validação de certificados no Android (API 28+) e checklist de teste pós-promoção.

### 2. Catálogo de Conhecimentos e Modelagem de Dados
- **[ESTRUTURA-ESTUDOS.md](ESTRUTURA-ESTUDOS.md):** Significados e relações entre troncos, concursos, editais, grades, componentes, disciplinas, assuntos, subassuntos e questões.
- **[RELEASE-1.4.15.md](RELEASE-1.4.15.md):** Hierarquia visível, cadastro de questões com origem completa e sessão web de 30 dias.
- **[ADMIN-WEB.md](ADMIN-WEB.md):** Painel web, login, administração de concursos/editais e questões, edição/ativação global do catálogo, histórico, recuperação e publicação.
- **[CONCILIACAO-ANTIGRAVITY-2026-11-07.md](CONCILIACAO-ANTIGRAVITY-2026-11-07.md):** Conferência das correções anteriores, causa da publicação interrompida da 1.4.12 e conciliação com painel/importador.
- **[RELEASE-1.4.14.md](RELEASE-1.4.14.md):** Promoção e aceite da 1.4.14/build 21, com todas as chamadas novas de IA em `grok-4.7`.
- **[RELEASE-1.4.13.md](RELEASE-1.4.13.md):** Conteúdo, preparação, promoção e aceite da release conciliada 1.4.13/build 20.
- **[INSERCAO-QUESTOES.md](INSERCAO-QUESTOES.md):** Cadastro individual e em lote no Android, formatos de entrada, assistência opcional de IA, revisão, permissões e recuperação de envios.
- **[CATALOGO-CONHECIMENTOS.md](CATALOGO-CONHECIMENTOS.md):** Especificação normativa do catálogo de conhecimentos compartilhados (`shared/knowledge-catalog.json`). Define a hierarquia de disciplinas, assuntos, subassuntos, troncos de carreira, aliases editoriais e regras de mapeamento canônico.
- **[SCHEMA-QUESTOES-V2.md](SCHEMA-QUESTOES-V2.md):** Especificação técnica dos contratos de questões v2, schemas JSON 2020-12 e validações BSON no MongoDB. Detalha validação normativa, hashing JCS/SHA-256 de conteúdo, idempotência com `operation_id`, outbox transacional e suporte a questões objetivas e discursivas.
- **[catalogo/CONHECIMENTOS.md](catalogo/CONHECIMENTOS.md):** Tabela legível e formatada de todas as disciplinas, assuntos e subassuntos do catálogo compartilhado, gerada pelo script de validação de catálogo.

### 3. Inteligência Artificial e Automação Controlada
- **[IA-XAI.md](IA-XAI.md):** Arquitetura de integração com a API xAI (Grok). Descreve os prompts de tutoria e classificação, armazenamento persistente no MongoDB, contratos de rate-limit (`reserve_tutor` / `release_tutor`), concorrência com exclusão mútua (`LOCKS`) e isolamento de chaves privadas.
- **[CLASSIFICACAO-LEGADO-SOB-DEMANDA.md](CLASSIFICACAO-LEGADO-SOB-DEMANDA.md):** Regras, cotas diárias e limites de custo da classificação sob demanda do acervo congelado (618 questões). Define salvaguardas contra loops de repetição e preservação da proveniência.

---

## 🏛️ Arquivo Histórico e Auditorias

Para consultar decisões de arquitetura de fases anteriores, marcos de entrega e notas de correção:

- **[Arquivo Histórico](historico/README.md):** Reúne relatórios de fechamento de etapas ([Etapas 3](historico/etapas/ETAPA-3-BACKEND-MONGO.md), [4](historico/etapas/ETAPA-4-CADASTRO-CATALOGO.md) e [5–7](historico/etapas/ETAPAS-5-7-CLASSIFICACAO-SELECAO.md)), notas de patches de versões anteriores ([1.4.1](historico/releases/CORRECAO-VINCULO-1.4.1.md), [1.4.2](historico/releases/CORRECAO-QUESTOES-1.4.2.md), [1.4.4](historico/releases/GESTAO-CONCURSOS-1.4.4.md)) e a auditoria inicial de novembro de 2026 ([FECHAMENTO-AUDITORIA.md](historico/auditoria-2026-11-01/FECHAMENTO-AUDITORIA.md) e [REMEDIACAO.md](historico/auditoria-2026-11-01/REMEDIACAO.md)).
- **[Auditoria de 01/11/2026](auditoria/2026-11-01/AUDITORIA-COGNIVA.md):** Diagnóstico original congelado contendo a análise profunda dos achados C01 a C39 com evidências e snapshots de fontes preservados.
- **Evidências de Validação de Releases:** Diretório [`remediation-validation/`](remediation-validation/) contendo logs e resumos de validação das versões 1.4.1 a 1.4.7.
