# Catálogo Compartilhado de Conhecimentos e Templates Globais

Para entender como troncos, templates de concursos, grades pessoais, componentes, disciplinas, assuntos, subassuntos e questões se conectam na prática, consulte [`ESTRUTURA-ESTUDOS.md`](ESTRUTURA-ESTUDOS.md).

---

## 1. Fonte Única de Verdade (`catalog_version: 2`)

No **Cogniva `2.0.0`**, toda a taxonomia pedagógica e editorial está consolidada em um único artefato canônico:

- **[`shared/knowledge-catalog.json`](../shared/knowledge-catalog.json)** (`schema_version: 2`, `catalog_version: 2`, validado por [`shared/knowledge-catalog.schema.json`](../shared/knowledge-catalog.schema.json)):
  - **7 troncos de carreira** (`fiscal`, `controle`, `policial`, `tribunais`, `bancaria`, `engenharia`, `fiscalizacao_trabalho`), incluindo `icon_key` para renderização direta nos clientes.
  - **35 disciplinas canônicas**, cada uma declarando `trunk_links` explícitos com `role` (`FOUNDATIONAL` ou `RELATED`), `default_weight` numérico e `aliases` para busca textual.
  - **49 assuntos raiz** (`parent_topic_id: null`) e **174 subassuntos** vinculados à respectiva disciplina.
  - Tabela completa legível gerada automaticamente em [`catalogo/CONHECIMENTOS.md`](catalogo/CONHECIMENTOS.md).
- **[`shared/exam-templates.json`](../shared/exam-templates.json)** (validado por [`shared/schemas/exam-templates.schema.json`](../shared/schemas/exam-templates.schema.json)):
  - Define os templates globais oficiais iniciais (`CPNU`, `RFB`, `BNDES`, `AFT`) carregados em `cogniva.exam_templates` e empacotados no Android para uso imediato *offline*.
- **[`shared/knowledge-id-registry.json`](../shared/knowledge-id-registry.json):**
  - Registro monotônico de reserva de identidades canônicas, garantindo que nenhum `trunk_id`, `subject_id` ou `topic_id` seja reutilizado com significado distinto.

---

## 2. Regras de Identidade, Aliases e Evolução Versionada

1. **Imutabilidade Semântica de IDs:**
   - Identidades como `FIN_PUB`, `DIR_TRIB` ou `FIN_PUB_01_001` nunca mudam quando um rótulo é refinado.
   - Renomear uma entidade exige preservar o nome anterior em `aliases` (comparação normalizada NFKD sem colisão entre entidades ativas).
   - Desativar uma disciplina ou tópico utiliza `status: "DEPRECATED"`, removendo-o de novas associações sem apagar questões, classificações históricas ou evidências de estudo já registradas.
2. **Separação Clara de Conceitos Próximos:**
   - Disciplinas como *Administração Financeira e Orçamentária (AFO)* e *Finanças Públicas*, ou *Contabilidade Geral* e *Contabilidade Pública*, mantêm identidades distintas, declarando interseções explícitas por meio de `relations` (`OVERLAPS_WITH`, `RELATED_TO`) sem transferir domínio automaticamente.
3. **Hierarquia Estrita de 2 Níveis (`TOPIC → SUBTOPIC`):**
   - Todo assunto raiz (`kind: "TOPIC"`, `parent_topic_id: null`) **deve possuir ao menos um subassunto filho** (`kind: "SUBTOPIC"`, `parent_topic_id: "<TOPIC_ID>"`). Tópicos de edital sem subdivisões explícitas devem ser modelados com um `TOPIC` pai e um `SUBTOPIC` folha correspondente, classificando-se as questões sempre no `SUBTOPIC` folha.
   - A unicidade normalizada (NFKD) de `label` e `aliases` é verificada globalmente entre disciplinas, por `(subject_id, null)` entre `TOPIC`s raiz e por `(subject_id, parent_topic_id)` entre `SUBTOPIC`s irmãos.
4. **Ativação em Execução sem Recompilação (Preservando o Baseline `v2`):**
   - O arquivo [`shared/knowledge-catalog.json`](../shared/knowledge-catalog.json) (`catalog_version: 2`) permanece como raiz imutável da cadeia de ativação embutida no binário.
   - Novas revisões em produção (`catalog_version + 1`: `v3`, `v4`, `v5`...) são propostas, validadas e ativadas pelo painel [`/admin/`](ADMIN-WEB.md) ou via [`scripts/cogniva_import.py`](../scripts/cogniva_import.py) (veja [`PACOTES-QUESTOES.md`](PACOTES-QUESTOES.md)), gravando snapshots imutáveis em `knowledge_catalog_versions` e atualizando `knowledge_catalog_head` com invalidação imediata do cache em memória no `cogniva-api`.

---

## 3. Validação e Exportação

```bash
# Validar schema JSON, integridade referencial, aliases NFKD, registro de IDs e paridade do Markdown
python3 scripts/knowledge_catalog.py --check

# Regenerar docs/catalogo/CONHECIMENTOS.md após atualizar shared/knowledge-catalog.json
python3 scripts/knowledge_catalog.py --export

# Executar suíte completa de testes dos contratos
pytest scripts/tests -v
```
