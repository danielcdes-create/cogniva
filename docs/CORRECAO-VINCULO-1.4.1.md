# Ajustes: confirmação do vínculo de conta

Versão 1.4.1/build 8: build/lint e regressão visual locais concluídos; gates de emulador/publicação em acompanhamento. Registrar o aceite operacional ao concluir a promoção.

O campo de código usava `fillMaxWidth()` dentro de uma `Row` que também continha ID e username com peso. Os outros campos ficavam comprimidos e cresciam na vertical. A única confirmação estava depois das metas de estudo, sob o rótulo genérico de salvar cadastro e preferências.

Os três campos agora ficam em uma coluna, cada um com largura completa. O título e o ID atual também ocupam linhas próprias. O botão **Confirmar vínculo** fica imediatamente abaixo do código, com estado de envio, bloqueio de repetição, retorno de sucesso/erro e opção de mostrar/ocultar o código. Falha mantém os campos para nova tentativa; sucesso limpa o código da memória.

Confirmar autentica a conta e carrega suas preferências salvas. Não envia nome, metas ou concursos ainda em edição para a conta de destino. **Salvar nome e metas** continua sendo uma ação separada e não consome o código. Código expirado e ausência de conexão recebem mensagens específicas. A troca de conta mantém o mecanismo existente de reinício e isolamento dos bancos/filas.

Regressão: duas novas imagens Paparazzi, com fonte normal e 200%, usam o componente real e o `DeviceConfig.PIXEL` de estoque. O teste instrumentado monta a tela completa de Ajustes nos dois tamanhos de fonte, mede a largura útil dos três campos, confirma a ação junto ao código e verifica bloqueio durante envio/retry sem gravar preferências. Todos os dados/interações desse teste são fixtures, sem acesso à API de produção.

Duas execuções reais de verify passaram com nove screenshots cada. O controle negativo trocou somente a nova imagem de fonte 200% por PNG 1×1 válido e falhou na comparação; os bytes originais foram restaurados e conferidos por SHA-256. Evidências específicas em `remediation-validation/1.4.1/`; inventário de histórico anterior em `remediation-validation/production-before-1.4.1.json`. As evidências originais da release 1.4.0 permanecem preservadas.
