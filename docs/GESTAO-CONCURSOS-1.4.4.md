# Gestão de concursos e componentes — 1.4.4/build 11

Implementação em validação. O cadastro anterior limitava a adição às disciplinas do tronco escolhido, sem inclusão livre, edição de nomes/tópicos nem escolha de etapa e grupo de conhecimentos. A API recusava identidades fora do catálogo e reinseria componentes removidos como arquivados.

O formulário permite buscar em todo o catálogo, adicionar disciplinas personalizadas, editar nomes, etapa livre, grupo (gerais, específicos, discursiva/estudo de caso ou outros), peso, quantidade e tópicos. Bancas adicionais também podem ser cadastradas. Disciplinas e tópicos podem ser excluídos da grade; tópicos e disciplinas com conteúdo podem ser arquivados/restaurados. A exclusão de componentes é aplicada ao salvar o formulário. Cancelar descarta as alterações do formulário.

A etapa pertence à disciplina e aparece junto ao grupo na visualização do edital. Identidades personalizadas usam `custom-UUID`; editar o nome não altera a identidade canônica. Isso não cria questões nem atribui vínculo canônico a documentos do acervo por inferência. Uma grade pode ser salva vazia para ser preenchida depois; prática e simulado continuam exigindo questões compatíveis.

Em Ajustes, a entrada **Gerenciar concursos e registros** abre uma tela própria com concursos, arquivados, criação, edição, restauração, exclusão com confirmação e registros preservados. Arquivar retira o concurso da escada; restaurar o inclui novamente como pré-edital. Excluir mantém um marcador sincronizado `EXCLUIDO`, oculto da lista e impedido de entrar na prática. Isso evita que templates ou outro aparelho recriem a entrada no próximo pull. Os eventos de aprendizagem não são apagados. Revisões anteriores das configurações já sincronizadas ficam em `revision_history`; componentes excluídos deixam de ser reinseridos na grade atual.

Persistência: Room 3→4 acrescenta `exam_subjects.stage` com padrão vazio, sem apagar tabelas ou filas. O campo percorre Room, payload HTTP, Mongo e retorno. Campos legados sem etapa continuam válidos. Escritas mantêm proprietário autenticado, revisão esperada e replay idempotente.

Validações previstas: migração/reabertura do SQLite, gravação e sincronização sem ressuscitar componentes, cadastro real na UI com fonte 2.0, arquivamento/restauração/confirmação de exclusão na UI e integração HTTP com Mongo sintético. Nenhuma configuração pessoal em produção deve ser removida como teste.
