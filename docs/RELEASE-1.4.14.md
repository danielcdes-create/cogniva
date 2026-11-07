# Release 1.4.14 / build 21

**Publicada e conferida em 07/11/2026.** Código `184f44f6dd9a7995d1a5a094b52637f31a8ee6c4`, tag `v1.4.14`, Android **1.4.14/build 21** e backend **1.4.14**, contrato 2. A [run 42381079294](https://github.com/danielcdes-create/cogniva/actions/runs/42381079294) terminou com todos os jobs aprovados. Substitui a produção 1.4.13/build 20.

- [APK publicado / OTA](https://198.199.65.103/api/v1/apk/download): **46.363.697 bytes**.
- SHA-256: `775ddbe216a0129ded4c77f476cba602aba76b085c50dda7d1c90411efacd4e8`.
- Runtime: **31.959.451 bytes**, abaixo do gate de 50 MB.
- Imagem: `ghcr.io/danielcdes-create/api-rust-core@sha256:4853531068a4bf3f4204f2abafd3d9b8929fadfa65d23e1c3a2400f36e7412d9`.

## Conteúdo

Tutores, classificação, assistência de importação e a fila automática do legado passam a chamar `grok-4.7`. O ambiente de produção já estava nesse modelo. Um registro antigo gravado com `grok-4.3` continua válido; chamada nova não usa esse modelo.

## Aceite

Gates de web, contratos, Rust, Android/JVM/golden, emulador API 35, build assinado e promoção com ensaio de rollback aprovados. Health e readiness anunciam revisão `184f44f` e versão 1.4.14. O APK baixado confere tamanho, SHA-256 e versão 21/1.4.14. Rota protegida anônima responde 401. Container saudável, não-root, somente leitura, `XAI_MODEL=grok-4.7` e gate aberto. FIN, Asbuildo e Spamshield mantiveram os mesmos IDs.

Inventário imediatamente anterior e posterior: **618 questões**, **47 documentos / 83 eventos** de aprendizagem, fingerprint `b9b5fd6a3d2ef3920b43bf1035fc5823c17585e4425d70ae705dbf327cb98d1f` nos dois momentos. [Aceite](remediation-validation/1.4.14/production-release.json).

Não houve instalação em aparelho físico nesta entrega.
