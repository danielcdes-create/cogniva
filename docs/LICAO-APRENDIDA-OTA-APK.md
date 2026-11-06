# Lição Aprendida: Resiliência no Pipeline OTA de APK, Streaming HTTP e Compressão

**Data do Incidente:** 05/11/2026 – 06/11/2026  
**Versões Impactadas:** 1.4.9 (`build 16`) e 1.4.10 (`build 17`)  
**Versão com Correção Definitiva:** 1.4.11 (`build 18`), commit `4d790fc`  
**Classificação:** Bloqueador de Distribuição Contínua / OTA Update Failure  

---

## 1. Contexto e Sintoma Observado

Durante a atualização do aplicativo Android via tela de **Ajustes → Atualização do App (OTA)**, os usuários que tocavam em **"Baixar e Atualizar"** recebiam a mensagem de erro:

> *"Não foi possível concluir. Seus registros foram preservados."*

O download do APK abortava prematuramente após alguns segundos, o arquivo não era concluído, e a caixa de diálogo de instalação do Android nunca era aberta.

---

## 2. Diagnóstico e Causa Raiz (RCA)

A falha decorreu de uma combinação de comportamentos entre três camadas: middleware HTTP do backend Rust, proxy reverso Nginx e cliente OkHttp no Android.

```
[Android App (OkHttp)]
   │  GET /api/v1/apk/download
   │  Header: "Accept-Encoding: gzip" (injetado automaticamente pelo OkHttp)
   ▼
[Nginx Reverse Proxy]
   │  Proxy pass para 127.0.0.1:8001
   ▼
[Backend Rust (Axum + tower-http)]
   │  Middleware: CompressionLayer::new()
   │  Gzip comprime arquivo .apk (~46 MB, que já é ZIP)
   │  Remove "Content-Length: 46101553"
   │  Adiciona "Transfer-Encoding: chunked" + "Content-Encoding: gzip"
   ▼
[Conexão Truncada / Buffer Exhaustion]
   │  Stream fecha subitamente em ~12,39 MB (CURLE_PARTIAL_FILE / EOF)
   ▼
[OkHttp GzipSource no Android]
   │  Lança java.io.EOFException() com e.message == null!
   ▼
[MainViewModel.kt operation { ... }]
   │  catch (e: Exception) { _userFeedbackMessage.emit(e.message ?: "Não foi possível concluir...") }
   │  Emite o texto padrão genérico, mascarando a falha de rede real.
```

### 2.1. Recompressão indevida de binário pré-comprimido no Axum
Em `backend-rust/src/main.rs`, as rotas `/api/v1/apk/download`, `/apk` e `/cogniva.apk` estavam registradas sob o middleware global `CompressionLayer::new()` do `tower-http`.
- O predicado padrão de tipos MIME do `tower-http` não excluía `application/vnd.android.package-archive`.
- Como o cliente OkHttp anuncia suporte a gzip via cabeçalho HTTP padrão `Accept-Encoding: gzip`, o Axum aplicava recompressão sobre o `.apk` (que internamente já é um contêiner ZIP DEFLATE).
- A recompressão forçou a troca de `Content-Length: 46101553` por `Transfer-Encoding: chunked` e sobrecarregou buffers de streaming assíncronos.

### 2.2. Esgotamento de buffer e corte do stream (`CURLE_PARTIAL_FILE`)
Ao tentar comprimir um fluxo de 46 MB em tempo real através do proxy Nginx com buffering padrão (`proxy_buffering on`), a conexão era encerrada com erro em aproximadamente 12,39 MB (`curl exit 18: transfer closed with outstanding read data remaining`).

### 2.3. Mascaramento de erro no cliente Android (`EOFException` com `message == null`)
- Quando a conexão caía, o `GzipSource` do OkHttp no Android detectava fim prematuro do stream gzip e lançava `java.io.EOFException`.
- No Java/Android runtime, `EOFException` herda de `IOException` sem definir mensagem textual (`e.message == null`).
- O bloco utilitário `operation { ... }` em `MainViewModel.kt` usava a expressão:
  ```kotlin
  _userFeedbackMessage.emit(e.message ?: "Não foi possível concluir. Seus registros foram preservados.")
  ```
- Por ser nula a mensagem, o app exibia a frase de fallback criada para conflitos de banco de dados e sincronização offline, levando o usuário e o suporte a acreditarem em um erro interno de banco de dados quando na verdade era uma queda de download HTTP.

### 2.4. Fragilidade na validação de certificados do APK (API 28+)
Em `MainViewModel.kt:257`, a validação pós-download utilizava apenas `incoming.signingInfo?.signingCertificateHistory.orEmpty()`. Para pacotes assinados com chave única sem histórico de rotação (APK Signature Scheme v3 sem linhagem), essa propriedade pode vir nula/vazia em alguns dispositivos, devendo consultar `incoming.signingInfo?.apkContentsSigners` como fallback.

---

## 3. Correções Definitivas Aplicadas

### 3.1. Backend Rust (`backend-rust/src/main.rs` e `src/routes/apk_ota.rs`)
1. **Isolamento do `CompressionLayer`:** As rotas de entrega de APK foram extraídas do roteador que aplica `CompressionLayer::new()`, ficando imunes a qualquer recompressão acidental:
   ```rust
   // main.rs
   .merge(Router::new()
       .route("/api/v1/health", get(...))
       .route("/api/v1/apk/info", get(routes::apk_ota::get_apk_info))
       .route("/api/app/latest-apk", get(routes::apk_ota::get_latest_apk)))
   .layer(CompressionLayer::new())
   .merge(
       Router::new()
           .route("/api/v1/apk/download", get(routes::apk_ota::download_apk))
           .route("/apk", get(routes::apk_ota::download_apk))
           .route("/cogniva.apk", get(routes::apk_ota::download_apk)),
   )
   ```
2. **Cabeçalhos de proteção anti-transformação:** A resposta de download passou a incluir:
   - `Content-Length: <size_bytes>` (calculado e verificado contra o arquivo imutável);
   - `Cache-Control: no-store, no-transform` (proíbe proxies intermediários de alterar a codificação do payload);
   - `X-Accel-Buffering: no` (instrui o Nginx a realizar streaming direto sem reter em buffers).
3. **Teste automatizado de regressão no Rust:**
   - Adicionado teste em `main.rs` (`latest_apk_tests`) que envia requisição explicitamente com `Accept-Encoding: gzip` e valida que `Content-Encoding` **não** é gzip e que `Content-Length` e o SHA-256 coincidem integralmente.

### 3.2. Configuração Nginx (`deploy/nginx-concursoprep.conf`)
Adicionado bloco dedicado às rotas de download de APK para desativar explicitamente a compressão e o buffering de proxy:
```nginx
location = /api/v1/apk/download {
    if ($cogniva_maintenance = 1) { return 503; }
    limit_req zone=cogniva_api burst=10 nodelay;
    gzip off;
    proxy_buffering off;
    proxy_pass http://127.0.0.1:8001;
}
```

### 3.3. Cliente Android (`ConcursoRepository.kt` e `MainViewModel.kt`)
1. **Pedido explícito de identidade (`Accept-Encoding: identity`):**
   ```kotlin
   client.newCall(
       Request.Builder()
           .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/apk/download")
           .header("Accept-Encoding", "identity")
           .build()
   )
   ```
2. **Resiliência na verificação de certificados (API 28+):**
   ```kotlin
   val incomingHistory = if (Build.VERSION.SDK_INT >= 28) {
       (incoming.signingInfo?.signingCertificateHistory ?: incoming.signingInfo?.apkContentsSigners).orEmpty()
   } else {
       incoming.signatures.orEmpty()
   }
   ```
3. **Tratamento de erro e mensagens descritivas:**
   - Erros de rede durante o download lançam `IOException` com mensagem clara: *"Falha de conexão durante o download da atualização. Tente novamente."*.
   - `MainViewModel.downloadAndInstallApk` captura exceções especificamente, atualiza `statusMessage` com a falha real e nunca emite mensagens genéricas de banco de dados para falhas de rede.

---

## 4. Invariantes de Engenharia para Publicações Futuras

Para impedir a reincidência de problemas na publicação e atualização de APKs:

1. **Invariante de Binários Pré-Comprimidos:**
   - Arquivos com extensões `.apk`, `.zip`, `.gz`, `.tar.gz` ou MIME `application/vnd.android.package-archive` **nunca** devem passar por camadas de compressão dinâmica (Gzip, Deflate, Brotli, Zstandard) no Axum ou Nginx.
2. **Invariante de Tamanho e Integridade (Content-Length / SHA-256):**
   - O endpoint de download do APK **deve sempre** retornar o cabeçalho `Content-Length` com o valor numérico exato igual ao campo `size_bytes` retornado por `/api/v1/apk/info`.
   - Se uma requisição com `Accept-Encoding: gzip` alterar o `Content-Length` ou omiti-lo em favor de `Transfer-Encoding: chunked`, o gate de release deve falhar.
3. **Invariante de Proxy Buffering:**
   - Downloads de binários acima de 10 MB servidos pelo backend devem utilizar `proxy_buffering off` e `X-Accel-Buffering: no` para evitar buffer exhaustion e timeouts de I/O em conexões móveis com jitter.
4. **Invariante de Mensagens de Erro no Cliente:**
   - Métodos utilitários que executam corrotinas (`operation { ... }`) nunca devem usar uma mensagem de domínio genérica (como "Seus registros foram preservados") como fallback quando `e.message` for nulo. Operações de rede devem ter fallbacks explícitos (e.g. `e.message ?: "Falha na comunicação com o servidor."`).

---

## 5. Checklist de Verificação Pós-Promoção de APK

Após qualquer push de tag que promova release no DigitalOcean, execute a verificação ponta a ponta simulando um cliente móvel:

```powershell
# 1. Obter metadados esperados
curl.exe -sk https://198.199.65.103/api/v1/apk/info

# 2. Testar download simulando cliente Android com suporte a gzip
curl.exe -sk -D - -H "Accept-Encoding: gzip" -o "$env:TEMP/verify-cogniva.apk" -w "`nHTTP=%{http_code} SIZE=%{size_download} EXIT=%{exitcode}`n" https://198.199.65.103/api/v1/apk/download

# 3. Conferir se:
# - HTTP = 200
# - EXIT = 0
# - Cabeçalho "Content-Encoding: gzip" NÃO está presente na resposta
# - Cabeçalho "Content-Length" bate exatamente com o size_bytes retornado pelo /apk/info
# - O hash SHA-256 do arquivo baixado bate 100% com o sha256_checksum do /apk/info:
Get-FileHash -Algorithm SHA256 "$env:TEMP/verify-cogniva.apk"

# 4. Limpeza
Remove-Item -Force "$env:TEMP/verify-cogniva.apk"
```
