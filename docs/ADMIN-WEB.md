# Administração Web do Cogniva (`/admin/`)

O painel administrativo web em `/admin/` é incorporado diretamente ao binário Rust (`cogniva-api`) a partir de [`web-admin/`](../web-admin/), compartilhando a mesma API `/api/v1/*`, os mesmos validadores V2 e o mesmo catálogo canônico do aplicativo Android.

---

## 1. Autenticação Nativa, Segurança Web e Acessibilidade

- **Login por E-mail e Senha (`Argon2id`):** Administradores autenticam diretamente em `/admin/` informando e-mail e senha da conta Cogniva com `role = "admin"` (definida via `ADMIN_EMAILS` ou `cogniva-api --grant-admin <email>`), protegido por limitação de taxa por IP e por conta.
- **Sessão Revogável por Cookie Seguro:**
  - `POST /api/v1/auth/web-session` cria uma sessão na coleção `sessions` e define cookie `HttpOnly`, `SameSite=Strict` e `Secure` (fora de loopback).
  - Nenhum token é exposto em `localStorage` ou `sessionStorage`.
  - Requisições autenticadas por cookie validam `Sec-Fetch-Site`, esquema + host/porta (`X-Forwarded-Proto` + `Origin`/`Referer`) e cabeçalho `X-Cogniva-CSRF: 1`.
  - `POST /api/v1/auth/web-logout` revoga a sessão no servidor, limpa o cookie e garante *teardown* local resiliente (`finally`) mesmo sob falhas de rede ou HTTP `502`/`503`.
- **Acessibilidade WCAG 2.1 AA e Responsividade:**
  - Paleta de cores, *badges* e avisos auditados matematicamente para contraste `>= 4.5:1`, tipografia mínima `>= 11px`, navegação visível por teclado (`:focus-visible`), modais `<dialog>` com `aria-labelledby`, preservação de foco e painéis `<details>` entre renderizações, e *drawer* lateral responsivo para telas menores que `960px`.

---

## 2. Escopo de Governança e Curadoria (`2.0.1`)

1. **Templates Globais de Concursos (`exam_templates`):**
   - O `/admin/` gerencia exclusivamente os **templates globais oficiais** (`GET/POST/PUT /api/v1/admin/exam-templates`, `POST /api/v1/admin/exam-templates/:id/publish-edital`), sem misturar grades pessoais criadas por alunos.
   - Rascunhos locais limpos sincronizam silenciosamente com revisões mais novas do servidor, enquanto rascunhos com edições locais exibem banner explícito de conflito com botão de recarregamento seguro; o histórico local em `localStorage` é limitado a 20 snapshots sem aninhamento recursivo.
2. **Banco de Questões 100% V2:**
   - Busca paginada com cursor bidirecional (`← Página anterior` / `Próxima página →`) e busca local insensível a acentos (normalização NFD), inspeção e edição completa de questões V2 (`explanation`, `level`, `exam_year`, `validity` com evidência `http(s)://`).
   - Validação preventiva em `pt-BR` do JSON de rubrica em questões `OPEN_ENDED` (modo preparatório) antes do envio.
   - Inserção individual ou em lote (JSON/JSONL/IA) com rotação automática de `aiOperationId` em falhas para permitir nova tentativa imediata sem `409 Conflict`.
3. **Aba Reportes (`reportedQuestoes`):**
   - Lista todos os reportes abertos enviados pelos estudantes (`GET /api/v1/admin/reports`), com atalho direto para abrir e corrigir a questão V2 e ações de resolução (`RESOLVED` ou `DISMISSED` via `POST /api/v1/admin/reports/:id/resolve`).
   - Em caso de exclusão de conta pelo titular (LGPD Art. 18), reportes prévios são anonimizados (`user_id: null`, `anonymized_lgpd: true`), preservando o insumo técnico de curadoria.
4. **Aba Classificação IA (`classification.rs`):**
   - Geração de propostas em lote sobre o catálogo ativo (`POST /api/v1/admin/classification/proposals`) e revisão humana individual (`POST /api/v1/admin/classification/decisions`).
5. **Catálogo Global Versionado (`knowledge-catalog.json`):**
   - Criação, renomeação com preservação automática de aliases normalizados, vínculo com troncos (`default_weight`) e ativação versionada (`POST /api/v1/admin/catalog/activate`) com invalidação imediata de cache.
6. **Central de Ajuda Integrada e LGPD:**
   - Inclui seção dedicada a **Privacidade (LGPD Art. 18) e Governança de IA**, sincronizada automaticamente com [`GUIA-ADMINISTRACAO.md`](GUIA-ADMINISTRACAO.md) via `node scripts/export-admin-help.mjs --check`.

---

## 3. Principais Rotas Administrativas

| Rota | Finalidade |
| :--- | :--- |
| `GET /admin/` e `/admin/assets/*` | Interface estática incorporada ao binário com CSP restrita (`script-src 'self'; style-src 'self'`) |
| `POST /api/v1/auth/web-session` / `web-logout` | Login nativo (e-mail + senha) por cookie `HttpOnly` e revogação |
| `GET /api/v1/admin/web/me` / `summary` | Sessão administrativa ativa e indicadores globais do acervo |
| `GET/POST/PUT /api/v1/admin/exam-templates*` | Gestão de templates globais de concursos e publicação de edital |
| `GET /api/v1/admin/questions` | Busca literal e paginação bidirecional do acervo V2 |
| `POST /api/v1/admin/questions/:id/prepare-edit` | Prévia validada, hash JCS e revisões para atualização CAS V2 |
| `POST /api/v1/admin/questions/import/*` | Capacidades, estruturação por IA, prévia e commit idempotente |
| `GET/POST /api/v1/admin/reports*` | Fila de curadoria e resolução de reportes de estudantes |
| `GET/POST /api/v1/admin/classification/*` | Propostas assistidas por IA e decisões humanas sobre o catálogo ativo |
| `GET/POST /api/v1/admin/catalog/*` | Validação, rascunhos `DRAFT`/`REVIEW_READY`, ativação e histórico |

---

## 4. Verificação Local

```bash
# Verificar sincronização entre web-admin/help.js e docs/GUIA-ADMINISTRACAO.md
node scripts/export-admin-help.mjs --check

# Checar sintaxe ES Modules e rodar suíte automatizada de domínio, ajuda, CSP, XSS e WCAG AA
npm --prefix web-admin run check
npm --prefix web-admin test
```
