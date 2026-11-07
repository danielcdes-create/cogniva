# Release 1.4.15 / build 22

**Publicada e conferida em 07/11/2026.** Código `bc9451ff5b1584249440df3d9b7e4d28455ea3b8`, tag `v1.4.15`, Android **1.4.15/build 22**, backend **1.4.15**, contrato de sincronização 2. A [run 42397102944](https://github.com/danielcdes-create/cogniva/actions/runs/42397102944) terminou com todos os jobs aprovados. Substitui a produção 1.4.14/build 21.

- [APK publicado / OTA](https://198.199.65.103/api/v1/apk/download): **46.429.233 bytes**.
- SHA-256: `1a32c1c333372ad19e63162ab18518b163b360f5f41c1351476cf6b40accd42c`.
- Runtime: **31.985.720 bytes**, abaixo do gate de 50 MB.
- Imagem: `ghcr.io/danielcdes-create/api-rust-core@sha256:21477999b7b7b817d70d94d71bd54423b2dcabe85376fde16392e279f63e2208`.

## Conteúdo

- Guia de relações entre troncos, concursos, edital, grade, componentes, disciplinas, assuntos, subassuntos e questões no painel e no Android.
- Troncos mostram concursos da conta; disciplinas mostram relações com troncos e componentes das grades. Caminhos de subassuntos incluem o pai nos seletores e na consulta.
- Cadastro individual com prova/cargo/caderno, instituição, banca, ano, número, nível, fonte documental, conferência de origem e explicação. Mesmos campos no Android, painel e API, com recuperação de rascunhos anteriores.
- Origem oficial com requisitos documentais do schema v2. Origem, classificação e validade têm confirmações independentes; cadastro não altera questões e histórico anteriores.
- Novas sessões web duram **30 dias**: JWT, cookie e expiração persistida concordam. Logout continua revogando a sessão. Sessões emitidas anteriormente mantêm sua expiração até novo login.

## Validação e publicação

O CI aprovou 12 testes web, os contratos de conhecimento, **70 testes Rust incluindo Mongo sintético**, Android/JVM/lint/golden, testes integrados em emulador API 35, build assinado e promoção com ensaio de rollback (`ROLLBACK_HEALTHY`). A prévia local conferiu renderização do guia, caminhos de assuntos, novos campos, recuperação do rascunho após recarga e desmarcação da conferência quando a origem muda.

Health e readiness anunciam `bc9451ff5b1584249440df3d9b7e4d28455ea3b8` / 1.4.15. O APK servido foi baixado integralmente e confere versão 22/1.4.15, tamanho e SHA-256. Os quatro arquivos públicos do painel são idênticos aos bytes do commit, com CSP e cache desabilitado. A rota protegida anônima responde 401. O login real de diagnóstico confirmou cookie seguro de **2.592.000 segundos**, expiração correspondente do token, identidade e logout seguido de 401. Credenciais não integram as evidências.

Os backups anterior/posterior confirmam **618 questões e cinco concursos idênticos byte a byte**, catálogo v1, quatro validadores, oito índices e guard do legado preservados. O histórico permanece com **47 documentos / 84 eventos**, fingerprint `28b23646bccbc046a2dcae5e83e5e5834ff082f74daab67a2928ccba88ba47ad`. Não houve reclassificação, inserção de conteúdo de teste ou chamada paga de IA no aceite. O Compose compartilhado e os IDs de FIN, Asbuildo e Spamshield permanecem iguais à referência imediatamente anterior.

Evidências: [CI](remediation-validation/1.4.15/ci.json), [aceite da release](remediation-validation/1.4.15/production-release.json), [preservação do banco](remediation-validation/1.4.15/storage-preservation.json), [assets web](remediation-validation/1.4.15/web-assets.json), [sessão de 30 dias](remediation-validation/1.4.15/web-session.json) e [prévia da interface](remediation-validation/1.4.15/ui-preview.json).

Não houve instalação em aparelho físico nesta entrega. A release anterior permanece documentada em [1.4.14](RELEASE-1.4.14.md).

Referências: [estrutura dos estudos](ESTRUTURA-ESTUDOS.md), [administração](ADMIN-WEB.md) e [inserção de questões](INSERCAO-QUESTOES.md).
