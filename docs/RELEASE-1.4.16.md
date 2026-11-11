# Release 1.4.16 / build 23

**Publicada e conferida em 10/11/2026 (Brasília).** Código `610e669d7273f030112f86801321593c09884379`, tag `v1.4.16`, backend **1.4.16**, Android **1.4.16/build 23**, contrato de sincronização 2. A [run 42926911430](https://github.com/danielcdes-create/cogniva/actions/runs/42926911430) terminou com todos os jobs aprovados. Substitui a produção 1.4.15/build 22.

- [APK publicado / OTA](https://198.199.65.103/api/v1/apk/download): **46.429.233 bytes**.
- SHA-256: `6eacb5459164ad97e1cbfcf74dd5dd77c46db8ed6861646d26c9fb12e394dfe3`.
- Runtime: **32.019.181 bytes**, abaixo do gate de 50 MB.
- Imagem: `ghcr.io/danielcdes-create/api-rust-core@sha256:5125be8bdf2044fc3d9f1de593dc8843c750d2919b3c36d95ce4a287207245f9`.

## Conteúdo

- Seção **Ajuda** no menu lateral da administração, com 14 tópicos sobre todas as telas do portal e os principais fluxos.
- Busca por termos sem diferenciar acentos/maiúsculas, índice, artigos expansíveis e atalhos para as telas correspondentes.
- Botão **Ajuda desta tela**, que abre e posiciona a explicação da área atual.
- Exemplo de preparação pré-edital do Banco Central, inserção/edição de questões, classificação/origem/validade, uso de IA, catálogo global, rascunhos, conflitos e sessão de 30 dias.
- [Guia em Markdown](GUIA-ADMINISTRACAO.md) gerado da mesma fonte da interface, com conferência automática no CI.
- Módulo `help.js` incorporado ao backend com as políticas existentes de CSP e cache. Nenhuma mudança funcional no Android; o build 23 acompanha o bundle de publicação.

## Validação e publicação

Validação local: 15 testes web aprovados; sintaxe dos módulos e alinhamento do guia conferidos. Na prévia sintética, menu, busca por Banco Central pré-edital, estado sem resultado, atalhos e ajuda contextual funcionaram, sem erros de console nem extrapolação da largura da página. O CI aprovou 15 testes web, conferência do guia gerado, contratos de conhecimento, **70 testes Rust incluindo Mongo sintético**, Android/JVM/lint/golden, emulador API 35, build assinado e promoção com ensaio de rollback (`ROLLBACK_HEALTHY`).

Health e readiness anunciam a revisão `610e669d7273f030112f86801321593c09884379` / 1.4.16. O APK servido foi baixado integralmente e confere versão 23/1.4.16, tamanho e SHA-256. Os **cinco arquivos públicos do painel**, incluindo `help.js`, correspondem ao commit, com CSP e cache desabilitado. A rota protegida anônima responde 401. Login real de diagnóstico confirmou os 30 dias e logout seguido de 401. Credenciais não integram os relatórios.

## Preservação e atividade durante o intervalo

As **618 questões são idênticas byte a byte**, sem reclassificação nesta publicação. O histórico de aprendizagem permanece com **52 documentos / 89 eventos**, fingerprint `4a5ee18b9407aa14084db495fa4148dbb6dfe665d4dfdd356a01782686a73920`. Quatro concursos também são idênticos byte a byte.

O comparador estrito de snapshots **não passou para o quinto concurso**: o cadastro do Banco Central avançou da revisão 3 para a 5 entre as capturas, com alteração de metadados, grade e situação. A análise conferiu que o histórico anterior continua idêntico como prefixo, que a revisão 3 do backup foi preservada integralmente no primeiro snapshot novo e que os snapshots 3 e 4 antecedem a revisão atual 5. São alterações compatíveis com o fluxo normal de edição versionada; os snapshots não registram o instante/autor de cada operação, portanto não se atribuiu autoria. Essas novas revisões não foram revertidas nem ajustadas para produzir igualdade artificial. As cinco identidades de concurso permanecem presentes.

Os quatro validadores, oito índices, catálogo v1 e guard do legado foram conferidos. Compose e IDs de FIN, Asbuildo e Spamshield correspondem à referência capturada antes desta promoção. Não foram inseridas questões de teste, ativados catálogos ou realizadas chamadas pagas de IA no aceite.

Evidências: [CI](remediation-validation/1.4.16/ci.json), [release](remediation-validation/1.4.16/production-release.json), [comparação dos dados e revisões](remediation-validation/1.4.16/storage-preservation.json), [arquivos web](remediation-validation/1.4.16/web-assets.json), [sessão](remediation-validation/1.4.16/web-session.json) e [prévia local](remediation-validation/1.4.16/ui-preview.json).

Não houve instalação em aparelho físico nesta entrega.
