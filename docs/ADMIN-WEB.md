# Administração Web do Cogniva (`/admin/`)

O painel administrativo web em `/admin/` é incorporado diretamente ao binário Rust (`cogniva-api`) a partir de [`web-admin/`](../web-admin/), compartilhando a mesma API `/api/v1/*`, os mesmos validadores V2 e o mesmo catálogo canônico do aplicativo Android.

---

## 1. Autenticação Nativa e Segurança Web

- **Login por E-mail e Senha (`Argon2id`):** Administradores autenticam diretamente em `/admin/` informando e-mail e senha da conta Cogniva com `role = "admin"` (definida via `ADMIN_EMAILS` ou `cogniva-api --grant-admin <email>`).
- **Sessão Revogável por Cookie Seguro:**
  - `POST /api/v1/auth/web-session` valida as credenciais, cria uma sessão na coleção `sessions` (validade de até 30 dias) e define cookie `HttpOnly`, `SameSite=Strict` e `Secure` (fora de loopback).
  - Nenhum token é exposto em `localStorage` ou `sessionStorage`.
  - Mutações autenticadas por cookie exigem correspondência estrita de `Origin` (`COGNIVA_PUBLIC_ORIGIN`) e cabeçalho `X-Cogniva-CSRF: 1`.
  - `POST /api/v1/auth/web-logout` revoga imediatamente a sessão no servidor e limpa o cookie no navegador.

---

## 2. Escopo de Governança e Curadoria (`2.0.0`)

1. **Templates Globais de Concursos (`exam_templates`):**
   - O `/admin/` gerencia exclusivamente os **templates globais oficiais** (`GET/POST/PUT /api/v1/admin/exam-templates`, `POST /api/v1/admin/exam-templates/:id/publish-edital`), sem misturar grades pessoais criadas por alunos.
   - Atualizações em um template global propagam automaticamente para alunos que não customizaram a grade (`is_customized == false`) e sinalizam `upstream_update_available = true` para alunos com grades personalizadas.
2. **Banco de Questões 100% V2:**
   - Busca paginada, inspeção e edição completa de questões V2 preservando `explanation`, `level` e `exam_year`, além de seletor explícito de **Validade** (`CURRENT`, `UNDER_REVIEW`, `OBSOLETE`, `UNKNOWN`).
   - Ao selecionar `OPEN_ENDED` (discursiva), o painel exibe aviso explícito de **modo preparatório** (validação de rubrica com soma igual a `100`, preservada no acervo V2 e excluída das filas objetivas).
   - Inserção individual ou em lote (JSON/JSONL/IA) com destravamento automático do formulário em caso de rejeição HTTP `4xx`, evitando bloqueio permanente de rascunhos.
3. **Aba Reportes (`question_reports`):**
   - Lista todos os reportes abertos enviados pelos estudantes (`GET /api/v1/admin/reports`), com atalho direto para abrir e corrigir a questão V2 e ações de resolução (`RESOLVED` ou `DISMISSED` via `POST /api/v1/admin/reports/:id/resolve`).
   - Enquanto aberto, um reporte oculta a questão apenas para o estudante que o registrou.
4. **Aba Classificação IA (`classification.rs`):**
   - Permite gerar propostas de classificação em lote sobre o catálogo ativo (`POST /api/v1/admin/classification/proposals`) com limite explícito de itens e chamadas pagas, seguidas de revisão humana individual (`POST /api/v1/admin/classification/decisions` — aceitar ou rejeitar sem vazar locks).
5. **Catálogo Global Versionado (`knowledge-catalog.json`):**
   - Criação, renomeação com preservação automática de aliases normalizados (NFKD), vínculo com troncos (`default_weight`) e deprecação sem perda de IDs.
   - Qualquer administrador autenticado pode revisar e ativar propostas em estado `REVIEW_READY` (`POST /api/v1/admin/catalog/activate`), invalidando instantaneamente o cache em memória do backend.
6. **Central de Ajuda Integrada:**
   - Pesquisa instantânea sem diferenciação de acentos e atalhos contextuais por tela, sincronizada com [`GUIA-ADMINISTRACAO.md`](GUIA-ADMINISTRACAO.md) via `node scripts/export-admin-help.mjs --check`.

---

## 3. Principais Rotas Administrativas

| Rota | Finalidade |
| :--- | :--- |
| `GET /admin/` e `/admin/assets/*` | Interface estática incorporada ao binário com CSP restrita |
| `POST /api/v1/auth/web-session` / `web-logout` | Login nativo (e-mail + senha) por cookie `HttpOnly` e revogação |
| `GET /api/v1/admin/web/me` / `summary` | Sessão administrativa ativa e indicadores globais do acervo |
| `GET/POST/PUT /api/v1/admin/exam-templates*` | Gestão de templates globais de concursos e publicação de edital |
| `GET /api/v1/admin/questions` | Busca literal e paginação do acervo V2 |
| `POST /api/v1/admin/questions/:id/prepare-edit` | Prévia validada, hash JCS e revisões para atualização CAS V2 |
| `POST /api/v1/admin/questions/import/*` | Capacidades, estruturação por IA, prévia e commit idempotente |
| `GET/POST /api/v1/admin/reports*` | Fila de curadoria e resolução de reportes de estudantes |
| `GET/POST /api/v1/admin/classification/*` | Propostas assistidas por IA e decisões humanas sobre o catálogo ativo |
| `GET/POST /api/v1/admin/catalog/*` | Validação, rascunhos `DRAFT`/`REVIEW_READY`, ativação e histórico |

> **Nota sobre Compatibilidade por Concurso:** A rota `POST /api/v1/exams/:id/compatibility` pertence ao escopo do proprietário da grade (`owner_user_id`), permitindo gerenciar vínculos de compatibilidade da própria grade sem exigir privilégio global de administrador.

---

## 4. Verificação Local

```bash
# Verificar sincronização entre web-admin/help.js e docs/GUIA-ADMINISTRACAO.md
node scripts/export-admin-help.mjs --check

# Checar sintaxe ES Modules e rodar suíte automatizada de domínio, ajuda e estado
npm --prefix web-admin run check
npm --prefix web-admin test
```
