# Release 1.4.16 / build 23

Preparada em 10/11/2026. Backend **1.4.16**, Android **1.4.16/build 23**, contrato de sincronização 2. Promoção e aceite serão registrados após a conclusão do pipeline.

## Conteúdo

- Seção **Ajuda** no menu lateral da administração, com 14 tópicos sobre todas as telas do portal e os principais fluxos.
- Busca por termos sem diferenciar acentos/maiúsculas, índice, artigos expansíveis e atalhos para as telas correspondentes.
- Botão **Ajuda desta tela**, que abre e posiciona a explicação da área atual.
- Exemplo de preparação pré-edital do Banco Central, inserção/edição de questões, classificação/origem/validade, uso de IA, catálogo global, rascunhos, conflitos e sessão de 30 dias.
- [Guia em Markdown](GUIA-ADMINISTRACAO.md) gerado da mesma fonte da interface, com conferência automática no CI.
- Módulo `help.js` incorporado ao backend com as políticas existentes de CSP e cache. Nenhuma mudança funcional no Android; o build 23 acompanha o bundle de publicação.

## Validação e publicação

Validação local: 15 testes web aprovados; sintaxe dos módulos e alinhamento do guia conferidos. Na prévia sintética, menu, busca por Banco Central pré-edital, estado sem resultado, atalhos e ajuda contextual funcionaram, sem erros de console nem extrapolação da largura da página. CI, promoção, hashes e preservação serão registrados após o aceite.
