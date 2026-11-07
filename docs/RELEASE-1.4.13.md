# Release 1.4.13 / build 20

**Publicada e conferida em 07/11/2026.** Código `5f77a4adb900bed386f28df2268b40f4dbc123cc`, tag `v1.4.13`, Android **1.4.13/build 20** e backend **1.4.13**, contrato 2. A [run 42371065615](https://github.com/danielcdes-create/cogniva/actions/runs/42371065615) terminou com todos os jobs aprovados. Substitui a produção 1.4.11/build 18; a tentativa 1.4.12 permanece registrada como não publicada.

- [APK publicado / OTA](https://198.199.65.103/api/v1/apk/download): **46.363.697 bytes**.
- SHA-256: `87c7e36e0b2661c5027c8b37326dbfc7fad4e6d0fcc6f1d8287c9f95b32c114f`.
- [Painel administrativo](https://198.199.65.103/admin/), com código temporário para conta já autorizada.
- Runtime: **31.964.202 bytes**, abaixo do gate de 50 MB.

## Conteúdo

- Painel web em `/admin/` para concursos, editais/grades, questões e catálogo global. Login administrativo por código temporário, cookie HttpOnly, proteção de origem/CSRF e logout revogável.
- Inserção individual e JSON/JSONL em lote no Android e na web, com revisão, schema v2, rascunhos por conta, comandos idempotentes e assistência opcional de IA.
- Edição explícita de questões legadas sem trocar IDs ou descartar campos extras; revisão do gabarito, classificação, validade e auditoria.
- Revisões do catálogo global com aliases, vínculos, desativação, histórico imutável, ativação condicional e recuperação após reinício. O catálogo v1 permanece ativo durante o deploy; esta publicação não ativa uma proposta nem reclassifica o acervo.
- Correções anteriores de concursos/disciplinas e OTA preservadas. Mensagens HTTP da IA compartilhadas pelo tutor/importador; falhas de processamento deixam de simular indisponibilidade de internet.
- Correção da expectativa do teste que bloqueou a tentativa 1.4.12: excluir um concurso preserva a última grade no tombstone e no histórico.

## Verificação antes do envio

Checkout conciliado: 69 testes Rust integrais com Mongo loopback, 73 testes Android/JVM, lint e compilação dos testes instrumentados aprovados; 10 testes web e 47 testes Python com 14 subtestes aprovados. O CI executou novamente os gates na revisão publicada: Rust (69 aprovados), web, contratos, Android/JVM/lint/golden e emulador. Nenhuma geração paga de IA foi necessária para o aceite.

Referências: [conciliação do Antigravity](CONCILIACAO-ANTIGRAVITY-2026-11-07.md), [painel](ADMIN-WEB.md), [inserção de questões](INSERCAO-QUESTOES.md) e [operação](OPERACAO.md).

## Proteção e promoção

Antes do envio foram capturados [identidades do servidor compartilhado](remediation-validation/1.4.13/before/server-baseline.json) e [inventário do banco](remediation-validation/production-pre-1.4.13.json). O backup BSON/índices/views permanece privado fora do Git. A referência tem 618 questões e 47 documentos de aprendizagem com 83 eventos; o aceite deve confrontar fingerprints, sem apagar atividade legítima posterior.

A tag anterior `v1.4.12` permanece intacta. A publicação usa um novo commit/tag `v1.4.13`, versão Android 1.4.13/code 20 e backend 1.4.13. O pipeline oficial exige contratos, Rust, web, Android/golden e emulador, constrói/assina fora da VPS e promove somente `api-rust-core`, com ensaio de rollback.

O Nginx em produção ainda não possuía as rotas do painel nem todos os blocos OTA presentes no repositório. A instalação inicial atualizou somente `/etc/nginx/sites-available/cogniva`, após comparação da configuração efetiva, backup privado, `nginx -t` e recarga aprovados. Um fim de linha CRLF na configuração original foi identificado antes da troca; a comparação final usou os bytes reais. O Compose compartilhado, os outros vhosts e os containers de FIN, Asbuildo e Spamshield foram preservados. [Registro do Nginx](remediation-validation/1.4.13/nginx.json).

## Aceite concluído

- Health/readiness públicos confirmaram a revisão promovida. O manifesto privado da VPS, a imagem por digest e os metadados repetidos do APK concordam com a revisão/versão. Container saudável, não-root, somente leitura, sem capabilities e gate reaberto. [Aceite de produção](remediation-validation/1.4.13/production-release.json).
- O APK publicado foi baixado integralmente; tamanho e SHA-256 coincidem com o manifesto. Assinatura v3 verificada para API 28–35 e mesmo certificado de produção da 1.4.11 (`0a5b2b2e8feb9fabdbae4fa68a4b73a691f4e060622e60b494fb75e866f5f560`); versão medida 20/1.4.13 e `debuggable` ausente. [Assinatura](remediation-validation/1.4.13/apk-signature.json).
- `/api/v1/apk/download`, `/apk` e `/cogniva.apk` respondem sem `Content-Encoding`, mesmo pedindo gzip, com `Content-Length` exato. Página e três assets do painel são idênticos aos bytes do commit, com CSP, `no-store` e `nosniff`. Quatro rotas administrativas recusam anonimato com 401; login inválido retorna 401 e origem externa retorna 403. Não foi criada sessão administrativa nem executada edição de conteúdo em produção para testar a entrega. [HTTP/painel](remediation-validation/1.4.13/web-and-http.json).
- O CI mediu Android 15/API 35, 1440×3088, 500 dpi e punch-hole. O teste real registrou `INSET_CHECK: success`, top bar 136 px, obstáculo 136 px, CTA 2975 px e navegação 75 px. [Gates e medições](remediation-validation/1.4.13/ci-gates.json).
- O ensaio registrou `ROLLBACK_HEALTHY` e `CONTROLLED_ROLLBACK_VERIFIED`; a promoção definitiva preparou o armazenamento com `question_documents_rewritten=0`, oito índices e catálogo v1. [Workflow concluído](remediation-validation/1.4.13/workflow.json).
- A [captura final anterior](remediation-validation/production-before-final-1.4.13.json), de 12:29:13 UTC, e a [captura posterior](remediation-validation/production-post-1.4.13.json), de 12:31:51 UTC, têm **618 questões e cinco concursos da conta idênticos byte a byte**. Os **47 documentos e 83 eventos de aprendizagem** mantiveram o fingerprint protegido. Quatro validadores estritos, guard dos 618 IDs e catálogo v1 foram verificados. Não houve reclassificação ou ativação editorial pelo deploy. [Preservação](remediation-validation/1.4.13/storage-preservation.json).

A primeira referência do servidor incluiu o container temporário `certbot/certbot` da renovação TLS das 12:03 UTC. Seus eventos mostram saída 0 e remoção normal, sem pertencer ao Compose; a referência inicial foi preservada. A [referência estável](remediation-validation/1.4.13/before/server-stable-baseline.json) confirmou os mesmos quatro serviços persistentes e foi usada no aceite. [Evidência do container transitório](remediation-validation/1.4.13/before/transient-container.json).

A publicação está concluída. Instalação e interação em aparelho físico continuam distintas dos testes JVM/golden, do emulador e da disponibilidade do APK; não foi feita instalação física nesta entrega.
