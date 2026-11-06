# Ajustes: confirmação do vínculo de conta

Versão **1.4.1/build 8 publicada em 01/11/2026 às 17:55 (UTC−03:00)**. Revisão `22bd6027acfe277ea2d1e425578d4d4709620f1d`; [run 41347592566](https://github.com/danielcdes-create/cogniva/actions/runs/41347592566) com todos os cinco jobs aprovados e aceite externo concluído.

O campo de código usava `fillMaxWidth()` dentro de uma `Row` que também continha ID e username com peso. Os outros campos ficavam comprimidos e cresciam na vertical. A única confirmação estava depois das metas de estudo, sob o rótulo genérico de salvar cadastro e preferências.

Os três campos agora ficam em uma coluna, cada um com largura completa. O título e o ID atual também ocupam linhas próprias. O botão **Confirmar vínculo** fica imediatamente abaixo do código, com estado de envio, bloqueio de repetição, retorno de sucesso/erro e opção de mostrar/ocultar o código. Falha mantém os campos para nova tentativa; sucesso limpa o campo ou reabre o app na conta vinculada. O código não é persistido no estado salvo da tela.

Confirmar autentica a conta e carrega suas preferências salvas. Não envia nome, metas ou concursos ainda em edição para a conta de destino. **Salvar nome e metas** continua sendo uma ação separada e não consome o código. Código expirado e ausência de conexão recebem mensagens específicas. A troca de conta mantém o mecanismo existente de reinício e isolamento dos bancos/filas.

Regressão: duas novas imagens Paparazzi, com fonte normal e 200%, usam o componente real e o `DeviceConfig.PIXEL` de estoque. O teste instrumentado monta a tela completa de Ajustes nos dois tamanhos de fonte, mede a largura útil dos três campos, confirma a ação junto ao código e verifica bloqueio durante envio/retry sem gravar preferências. Todos os dados/interações desse teste são fixtures, sem acesso à API de produção.

Duas execuções reais de verify passaram com nove screenshots cada. O controle negativo trocou somente a nova imagem de fonte 200% por PNG 1×1 válido e falhou na comparação; os bytes originais foram restaurados e conferidos por SHA-256. Evidências específicas em `remediation-validation/1.4.1/`; inventário de histórico anterior em `remediation-validation/production-before-1.4.1.json`. As evidências originais da release 1.4.0 permanecem preservadas.

## Aceite da publicação

- Backend: 24 testes aprovados com Mongo sintético. Android/JVM/lint e nove screenshots aprovados no CI. Emulador API 35/Android 15: cinco testes aprovados, incluindo os dois cenários reais de Ajustes; resultados em `native-summary.json`.
- APK release sem `debuggable`, pacote/versão e assinatura conferidos localmente, inclusive API 28–35; mesmo certificado privado da release anterior. Arquivo: 12.725.237 bytes; SHA-256 `7b45bc089623ea29e7c33bf2c72319796c34f48050e73c1e2332cf6af455d4f7`.
- Runtime pronto de 25.808.896 bytes; `CONTROLLED_ROLLBACK_VERIFIED` seguido da promoção efetiva. HTTPS health/readiness confirmaram 1.4.1, revisão esperada e contrato 2. Duas leituras de metadados iguais; download real corresponde a tamanho/hash do bundle; rota protegida anônima retorna 401.
- Capturas imediatamente antes/depois preservaram 15 documentos/27 eventos, SHA-256 protegido `a34574d29433a312c494026a3c9fc84291bde7fbe05783675caf79a10caf50c7`. Compose e demais containers preservados. Inventários `production-pre-promotion-1.4.1.json` e `production-after-1.4.1.json`, no diretório de evidências acima desta versão.
- Atualizar sobre a instalação existente conserva o vínculo; não é necessário emitir outro código para uma conta já autenticada. Instalação física da 1.4.1 continua sem prova ADB; a validação nativa desta release ocorreu no emulador. Permanecem os limites de gabaritos/vínculos canônicos e assinatura API 26–27 registrados no fechamento original.
