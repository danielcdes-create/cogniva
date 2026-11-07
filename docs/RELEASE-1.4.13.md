# Release 1.4.13 / build 20

Estado inicial em 07/11/2026: **preparada para publicação**, ainda sem aceite de produção. A versão confirmada antes desta promoção é 1.4.11/build 18. O resultado final, a revisão, a execução CI e os metadados medidos serão registrados após a publicação, sem confundir preparação com entrega concluída.

## Conteúdo

- Painel web em `/admin/` para concursos, editais/grades, questões e catálogo global. Login administrativo por código temporário, cookie HttpOnly, proteção de origem/CSRF e logout revogável.
- Inserção individual e JSON/JSONL em lote no Android e na web, com revisão, schema v2, rascunhos por conta, comandos idempotentes e assistência opcional de IA.
- Edição explícita de questões legadas sem trocar IDs ou descartar campos extras; revisão do gabarito, classificação, validade e auditoria.
- Revisões do catálogo global com aliases, vínculos, desativação, histórico imutável, ativação condicional e recuperação após reinício. O catálogo v1 permanece ativo durante o deploy; esta publicação não ativa uma proposta nem reclassifica o acervo.
- Correções anteriores de concursos/disciplinas e OTA preservadas. Mensagens HTTP da IA compartilhadas pelo tutor/importador; falhas de processamento deixam de simular indisponibilidade de internet.
- Correção da expectativa do teste que bloqueou a tentativa 1.4.12: excluir um concurso preserva a última grade no tombstone e no histórico.

## Verificação antes do envio

Checkout conciliado: 69 testes Rust integrais com Mongo loopback, 73 testes Android/JVM, lint e compilação dos testes instrumentados aprovados; 10 testes web e 47 testes Python com 14 subtestes aprovados nas validações anteriores deste mesmo conjunto. Os gates do CI devem executar novamente na revisão da release. Nenhuma geração paga de IA é necessária para o aceite.

Referências: [conciliação do Antigravity](CONCILIACAO-ANTIGRAVITY-2026-11-07.md), [painel](ADMIN-WEB.md), [inserção de questões](INSERCAO-QUESTOES.md) e [operação](OPERACAO.md).

## Proteção e promoção

Antes do envio foram capturados [identidades do servidor compartilhado](remediation-validation/1.4.13/before/server-baseline.json) e [inventário do banco](remediation-validation/production-pre-1.4.13.json). O backup BSON/índices/views permanece privado fora do Git. A referência tem 618 questões e 47 documentos de aprendizagem com 83 eventos; o aceite deve confrontar fingerprints, sem apagar atividade legítima posterior.

A tag anterior `v1.4.12` permanece intacta. A publicação usa um novo commit/tag `v1.4.13`, versão Android 1.4.13/code 20 e backend 1.4.13. O pipeline oficial exige contratos, Rust, web, Android/golden e emulador, constrói/assina fora da VPS e promove somente `api-rust-core`, com ensaio de rollback.

O Nginx em produção ainda não possuía as rotas do painel nem todos os blocos OTA presentes no repositório. A instalação inicial deve atualizar somente `/etc/nginx/sites-available/cogniva`, após comparar a configuração efetiva, guardar backup, executar `nginx -t` e recarregar. O Compose compartilhado e os outros vhosts/containers são preservados.

Depois da promoção, o aceite deve conferir readiness/revisão, APK completo/tamanho/SHA-256/assinatura, download sem compressão, painel e assets com CSP, autenticação administrativa, inventário/histórico e identidade dos outros serviços. Testes de aparelho físico continuam distintos da publicação e do emulador de CI.
