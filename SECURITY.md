# Política de Segurança (Security Policy) — Cogniva

## Versões Suportadas

Apenas a versão mais recente promovida em produção recebe correções de segurança e suporte operacional ativo:

| Versão | Build Android (`versionCode`) | Contrato API (`contract_version`) | Status |
| :--- | :---: | :---: | :--- |
| `2.4.4` | `34` | `5` | Suportada (Produção ativa) |
| `2.0.0` – `2.4.3` | `25` – `33` | `3` – `5` | Suportada para atualização OTA imediata |
| `< 2.0.0` | `< 25` | `< 3` | Descontinuada (Atualizar para `2.4.4`) |

## Invariantes de Segurança da Arquitetura

1. **Autenticação Nativa e Isolamento por Proprietário:**
   - Senhas de contas registradas são derivadas exclusivamente com **Argon2id** (sob semáforo de concorrência limitada para proteção de memória) e sal criptográfico individual; tokens de sessão são gerados com entropia criptográfica e armazenados no cliente Android sob criptografia AES-GCM apoiada pelo Android Keystore (`allowBackup="false"`).
   - Sessões do Portal Administrativo (`/admin/`) e do Portal de Missões (`/missoes/`) utilizam cookies separados (`cogniva_web_session` e `cogniva_missions_session`) com `HttpOnly`, `SameSite=Strict` e `Secure` sob HTTPS, validação combinada de esquema/origem (`X-Forwarded-Proto` + `Origin`/`Referer` + `Sec-Fetch-Site`), proteção contra spoofing de `Host` e cabeçalho anti-CSRF (`X-Cogniva-CSRF: 1`).
   - Endpoints de autenticação e criação de contas contam com limitação de taxa em dupla camada: Nginx com normalização de prefixo IPv6 `/64` e limitador persistente/em memória com chaves de sujeito derivadas em SHA-256 no runtime Rust.
2. **Integridade de Build, Promoção Atômica e OTA:**
   - O runtime `cogniva-api` e o pacote `.apk` são compilados exclusivamente fora da VPS no pipeline imutável [deploy.yml](.github/workflows/deploy.yml), com verificação `SHA256SUMS`, assinatura privada APK Signature Scheme v2/v3 (sem chaves de depuração), ensaio automático de *rollback* isolado (`rollback-drill`) e validação estrita de URI de origem (esquema, host e porta exatos), tamanho e hash SHA-256 antes da abertura de sessão no `PackageInstaller`.
3. **Endurecimento de Runtime, Rede e Contratos:**
   - O serviço `api-rust-core` executa como usuário não-root, filesystem somente leitura (`read_only: true`), `cap_drop: ["ALL"]`, `no-new-privileges: true` e limites estritos de memória, CPU e PIDs.
   - A borda TLS aplica HSTS, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy` restritiva e renovação automática horária monitorada.
   - Todos os contratos Schema v2 passam por validação JSON Schema Draft 2020-12, restrição estrita a esquemas `http(s)://` em evidências/referências e delimitação estrutural contra injeção indireta de prompt nas integrações com `xAI`.

## Relato Responsável de Vulnerabilidades

- **Não abra issues públicas** contendo detalhes exploráveis, *proofs-of-concept*, credenciais ou dados reais.
- Utilize o recurso privado **Report a vulnerability** (GitHub Security Advisories) do repositório para relatar falhas de segurança diretamente ao mantenedor.
- Inclua descrição técnica do vetor, passos mínimos de reprodução em ambiente sintético e avaliação de impacto.

Consulte também a [Política de Privacidade e LGPD](PRIVACY.md).
