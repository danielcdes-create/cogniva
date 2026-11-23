package br.com.concursoprep.app.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.IntentCompat
import br.com.concursoprep.app.BuildConfig
import br.com.concursoprep.app.domain.model.ApkInfoDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Gerenciador endurecido de atualização OTA do APK Cogniva:
 * - Exige `sha256_checksum` hexadecimal não vazio (64 hex chars), `size_bytes > 0` limitado e `versionCode > instalado`.
 * - Faz download limitado em arquivo temporário `.apk.part` mantendo o mesmo descritor aberto (`RandomAccessFile`)
 *   para validar contagem de bytes + SHA-256 e transmitir diretamente para `PackageInstaller.Session`
 *   (`session.openWrite("cogniva-base.apk", 0, expectedBytes)`), eliminando condições de corrida TOCTOU.
 * - Abandona sessões órfãs em `packageInstaller.mySessions` com mais de 24 horas antes de criar nova sessão.
 */
object AppUpdateManager {

    const val MAX_APK_BYTES: Long = 200L * 1024L * 1024L // 200 MiB
    const val STALE_SESSION_MAX_AGE_MS: Long = 24L * 60L * 60L * 1000L // 24h
    internal const val ACTION_INSTALL_STATUS = "br.com.concursoprep.app.action.APK_INSTALL_STATUS"

    private val SHA256_HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")
    private val ENCODED_TRAVERSAL_REGEX = Regex("(?i)%(?:2e|2f|5c|00)")

    private val _statusEvents = MutableSharedFlow<InstallSessionEvent>(extraBufferCapacity = 4)
    val statusEvents: SharedFlow<InstallSessionEvent> = _statusEvents.asSharedFlow()
    private val activeUiCollectorCount = java.util.concurrent.atomic.AtomicInteger(0)

    sealed interface InstallSessionEvent {
        data class PendingUserConfirmation(val confirmationIntent: Intent) : InstallSessionEvent
        data object Succeeded : InstallSessionEvent
        data class Failed(val message: String) : InstallSessionEvent
    }

    fun onUiCollectorStarted() {
        activeUiCollectorCount.incrementAndGet()
    }

    fun onUiCollectorStopped() {
        activeUiCollectorCount.updateAndGet { cur -> if (cur > 0) cur - 1 else 0 }
    }

    internal fun resetUiCollectorsForTest() {
        activeUiCollectorCount.set(0)
    }

    internal fun emitStatusEvent(event: InstallSessionEvent): Boolean = _statusEvents.tryEmit(event)
    internal fun hasActiveUiCollectors(): Boolean =
        activeUiCollectorCount.get() > 0 && _statusEvents.subscriptionCount.value > 0

    fun validateMetadata(
        info: ApkInfoDto,
        installedVersionCode: Int = BuildConfig.VERSION_CODE
    ): ValidatedApkMetadata {
        require(info.versionCode > installedVersionCode) {
            "Nenhuma versão superior disponível para instalação."
        }
        require(info.sizeBytes in 1L..MAX_APK_BYTES) {
            "Tamanho do pacote inválido nos metadados da atualização (${info.sizeBytes} bytes)."
        }
        val normalizedSha = info.sha256Checksum.trim().lowercase(Locale.ROOT)
        require(normalizedSha.isNotBlank() && SHA256_HEX_REGEX.matches(normalizedSha)) {
            "Checksum SHA-256 ausente ou inválido nos metadados da atualização."
        }
        return ValidatedApkMetadata(
            versionCode = info.versionCode,
            versionName = info.versionName,
            expectedBytes = info.sizeBytes,
            expectedSha256Hex = normalizedSha,
            downloadPath = info.downloadPath
        )
    }

    data class ValidatedApkMetadata(
        val versionCode: Int,
        val versionName: String,
        val expectedBytes: Long,
        val expectedSha256Hex: String,
        val downloadPath: String
    )

    fun resolveDownloadUrl(apiBaseUrl: String, downloadPath: String): String {
        val baseUri = runCatching { java.net.URI(apiBaseUrl.trim()) }.getOrNull()
        require(
            baseUri != null &&
                baseUri.scheme.equals("https", ignoreCase = true) &&
                !baseUri.host.isNullOrBlank() &&
                baseUri.userInfo == null &&
                baseUri.rawUserInfo == null &&
                baseUri.rawFragment == null
        ) {
            "Atualização exige conexão HTTPS."
        }
        val basePort = if (baseUri.port == 443) -1 else baseUri.port
        val cleanBase = apiBaseUrl.trim().trimEnd('/')
        val cleanPath = downloadPath.trim()
        require(
            !cleanPath.startsWith("//") &&
                !cleanPath.contains('\\') &&
                !cleanPath.contains("..") &&
                !cleanPath.contains('#') &&
                !ENCODED_TRAVERSAL_REGEX.containsMatchIn(cleanPath)
        ) {
            "Origem de download da atualização não autorizada."
        }
        val resolved = when {
            cleanPath.contains("://") -> cleanPath
            cleanPath.startsWith("/api/v1/") -> {
                val origin = cleanBase.removeSuffix("/api/v1")
                origin + cleanPath
            }
            cleanPath.startsWith("/api/") -> {
                val origin = cleanBase.removeSuffix("/api/v1").removeSuffix("/api")
                origin + cleanPath
            }
            else -> {
                val relative = cleanPath.trimStart('/')
                "$cleanBase/${relative.ifBlank { "apk/download" }}"
            }
        }
        val parsedUri = runCatching { java.net.URI(resolved) }.getOrNull()
        val rawPath = parsedUri?.rawPath.orEmpty()
        val decodedPath = parsedUri?.path.orEmpty()
        require(
            parsedUri != null &&
                parsedUri.rawFragment == null &&
                parsedUri.fragment == null &&
                parsedUri.userInfo == null &&
                parsedUri.rawUserInfo == null &&
                !ENCODED_TRAVERSAL_REGEX.containsMatchIn(rawPath) &&
                !rawPath.contains("..") &&
                !decodedPath.contains("..") &&
                !decodedPath.contains('\\') &&
                rawPath.split('/').none { it == "." || it == ".." } &&
                decodedPath.split('/').none { it == "." || it == ".." }
        ) {
            "Origem de download da atualização não autorizada."
        }
        val targetUri = parsedUri.normalize()
        val targetPort = if (targetUri.port == 443) -1 else targetUri.port
        val normRawPath = targetUri.rawPath.orEmpty()
        val normDecodedPath = targetUri.path.orEmpty()
        require(
            targetUri.scheme.equals("https", ignoreCase = true) &&
                targetUri.userInfo == null &&
                targetUri.rawUserInfo == null &&
                targetUri.rawFragment == null &&
                targetUri.host.equals(baseUri.host, ignoreCase = true) &&
                targetPort == basePort &&
                normRawPath.startsWith("/api/") &&
                normDecodedPath.startsWith("/api/") &&
                !normRawPath.contains("..") &&
                !normDecodedPath.contains("..")
        ) {
            "Origem de download da atualização não autorizada."
        }
        return resolved
    }

    /**
     * Abandona sessões antigas (> 24h em API 30+, ou inativas em API 28-29) pertencentes ao instalador deste app.
     */
    fun abandonStaleSessions(
        installer: PackageInstaller,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        val cutoffEpochMs = nowEpochMs - STALE_SESSION_MAX_AGE_MS
        for (sessionInfo in installer.mySessions) {
            val isStale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val lastTouched = sessionInfo.updatedMillis.takeIf { it > 0L } ?: sessionInfo.createdMillis
                lastTouched in 1L until cutoffEpochMs
            } else {
                !sessionInfo.isActive
            }
            if (isStale) {
                runCatching { installer.abandonSession(sessionInfo.sessionId) }
            }
        }
    }

    /**
     * Grava o fluxo de entrada em um arquivo `.apk.part` temporário, valida tamanho exato e SHA-256
     * sobre o mesmo descritor de arquivo aberto e transmite diretamente para [sinkOpener] sem reabrir por caminho.
     */
    internal fun downloadVerifyAndStreamToSink(
        source: InputStream,
        validated: ValidatedApkMetadata,
        updatesDir: File,
        onInspectVerifiedTempFile: (File) -> Unit = {},
        sinkOpener: (expectedBytes: Long) -> OutputStream
    ) {
        updatesDir.mkdirs()
        // Limpa eventuais artefatos antigos antes de iniciar
        updatesDir.listFiles()?.forEach { old ->
            if (old.name.endsWith(".part") || old.name.endsWith(".apk")) {
                runCatching { old.delete() }
            }
        }

        val partFile = File(updatesDir, "cogniva-${validated.versionCode}-${UUID.randomUUID()}.apk.part")
        try {
            RandomAccessFile(partFile, "rw").use { raf ->
                val writeBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalDownloaded = 0L
                while (true) {
                    val read = source.read(writeBuffer)
                    if (read < 0) break
                    if (read == 0) continue
                    totalDownloaded += read
                    if (totalDownloaded > validated.expectedBytes) {
                        throw SecurityException(
                            "O download excedeu o tamanho declarado nos metadados (${validated.expectedBytes} bytes)."
                        )
                    }
                    raf.write(writeBuffer, 0, read)
                }
                raf.fd.sync()

                val fileLen = raf.length()
                if (totalDownloaded != validated.expectedBytes || fileLen != validated.expectedBytes) {
                    throw SecurityException(
                        "Tamanho do APK baixado ($fileLen bytes) diverge do esperado (${validated.expectedBytes} bytes)."
                    )
                }

                // Verifica SHA-256 diretamente no descritor aberto (.apk.part)
                raf.seek(0L)
                val digest = MessageDigest.getInstance("SHA-256")
                var remainingForHash = validated.expectedBytes
                while (remainingForHash > 0L) {
                    val toRead = minOf(writeBuffer.size.toLong(), remainingForHash).toInt()
                    val read = raf.read(writeBuffer, 0, toRead)
                    if (read <= 0) {
                        throw IOException("Fim inesperado do arquivo temporário durante verificação SHA-256.")
                    }
                    digest.update(writeBuffer, 0, read)
                    remainingForHash -= read
                }
                val computedHex = digest.digest().joinToString("") { "%02x".format(it) }
                val hashMatches = MessageDigest.isEqual(
                    computedHex.toByteArray(Charsets.US_ASCII),
                    validated.expectedSha256Hex.toByteArray(Charsets.US_ASCII)
                )
                if (!hashMatches) {
                    throw SecurityException("Checksum SHA-256 do pacote baixado não confere com o servidor.")
                }

                onInspectVerifiedTempFile(partFile)

                // Reposiciona o mesmo descritor aberto para 0 e transmite direto para a sessão de instalação (sem TOCTOU)
                raf.seek(0L)
                val openDescriptorStream = FileInputStream(raf.fd)
                sinkOpener(validated.expectedBytes).use { sink ->
                    var remainingToStream = validated.expectedBytes
                    while (remainingToStream > 0L) {
                        val toRead = minOf(writeBuffer.size.toLong(), remainingToStream).toInt()
                        val read = openDescriptorStream.read(writeBuffer, 0, toRead)
                        if (read <= 0) {
                            throw IOException("Falha ao transmitir pacote verificado para o instalador do sistema.")
                        }
                        sink.write(writeBuffer, 0, read)
                        remainingToStream -= read
                    }
                    sink.flush()
                }
            }
        } finally {
            runCatching { partFile.delete() }
        }
    }

    private fun verifyArchiveIdentity(context: Context, apkFile: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installed = pm.getPackageInfo(context.packageName, flags)
        val incoming = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: throw SecurityException("O arquivo baixado não é um pacote Android (APK) válido.")
        val installedCerts = installed.signingInfo?.apkContentsSigners.orEmpty()
        val incomingHistory = (incoming.signingInfo?.signingCertificateHistory
            ?: incoming.signingInfo?.apkContentsSigners).orEmpty()
        val incomingVersion = incoming.longVersionCode
        require(
            incoming.packageName == context.packageName &&
                incomingVersion > BuildConfig.VERSION_CODE &&
                installedCerts.isNotEmpty() &&
                installedCerts.all { old -> incomingHistory.any { it == old } }
        ) {
            "O pacote não corresponde à identidade e à assinatura oficial do aplicativo."
        }
    }

    suspend fun downloadVerifyAndStageInstall(
        context: Context,
        info: ApkInfoDto,
        apiBaseUrl: String = BuildConfig.API_BASE_URL,
        httpClient: OkHttpClient? = null
    ) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val validated = validateMetadata(info, BuildConfig.VERSION_CODE)
        val downloadUrl = resolveDownloadUrl(apiBaseUrl, validated.downloadPath)

        val client = httpClient ?: OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        val installer = appContext.packageManager.packageInstaller
        abandonStaleSessions(installer)

        val updatesDir = File(appContext.cacheDir, "updates")
        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("Accept-Encoding", "identity")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Servidor retornou HTTP ${response.code} ao baixar a atualização.")
                }
                val body = response.body ?: throw IOException("Resposta vazia ao baixar a atualização.")

                var createdSessionId: Int? = null
                var openedSession: PackageInstaller.Session? = null
                try {
                    body.byteStream().use { networkStream ->
                        downloadVerifyAndStreamToSink(
                            source = networkStream,
                            validated = validated,
                            updatesDir = updatesDir,
                            onInspectVerifiedTempFile = { verifiedPart ->
                                verifyArchiveIdentity(appContext, verifiedPart)
                            },
                            sinkOpener = { expectedBytes ->
                                val params = PackageInstaller.SessionParams(
                                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                                ).apply {
                                    setSize(expectedBytes)
                                    setAppPackageName(appContext.packageName)
                                }
                                val sid = installer.createSession(params)
                                createdSessionId = sid
                                val session = installer.openSession(sid)
                                openedSession = session
                                val sessionOut = session.openWrite("cogniva-base.apk", 0L, expectedBytes)
                                object : OutputStream() {
                                    override fun write(b: Int) = sessionOut.write(b)
                                    override fun write(b: ByteArray, off: Int, len: Int) = sessionOut.write(b, off, len)
                                    override fun flush() {
                                        sessionOut.flush()
                                        session.fsync(sessionOut)
                                    }
                                    override fun close() {
                                        sessionOut.close()
                                    }
                                }
                            }
                        )
                    }

                    val session = checkNotNull(openedSession) { "Sessão de instalação não inicializada." }
                    val sessionId = checkNotNull(createdSessionId)
                    val callbackIntent = Intent(appContext, ApkInstallStatusReceiver::class.java).apply {
                        action = ACTION_INSTALL_STATUS
                        setPackage(appContext.packageName)
                    }
                    val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val pendingIntent = PendingIntent.getBroadcast(
                        appContext,
                        sessionId,
                        callbackIntent,
                        pendingFlags
                    )
                    session.commit(pendingIntent.intentSender)
                    session.close()
                    openedSession = null
                } catch (t: Throwable) {
                    runCatching { openedSession?.abandon() }
                    runCatching { openedSession?.close() }
                    createdSessionId?.let { sid -> runCatching { installer.abandonSession(sid) } }
                    throw t
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw IOException("Falha de conexão durante o download da atualização. Tente novamente.", e)
        } finally {
            if (httpClient == null) {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }
}

class ApkInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppUpdateManager.ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirmIntent = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            IntentCompat.getParcelableExtra(
                intent,
                Intent.EXTRA_INTENT,
                Intent::class.java
            )
        } else {
            null
        }
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        handleInstallStatus(
            status = status,
            confirmIntent = confirmIntent,
            statusMessage = detail,
            startConfirmActivity = { target ->
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(target) }
            }
        )
    }

    companion object {
        internal fun handleInstallStatus(
            status: Int,
            confirmIntent: Intent?,
            statusMessage: String?,
            startConfirmActivity: (Intent) -> Unit
        ) {
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val target = confirmIntent ?: return
                    target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val deliveredToUi = AppUpdateManager.hasActiveUiCollectors() &&
                        AppUpdateManager.emitStatusEvent(
                            AppUpdateManager.InstallSessionEvent.PendingUserConfirmation(target)
                        )
                    if (!deliveredToUi) {
                        startConfirmActivity(target)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    AppUpdateManager.emitStatusEvent(AppUpdateManager.InstallSessionEvent.Succeeded)
                }
                else -> {
                    val msg = if (!statusMessage.isNullOrBlank()) {
                        "Instalação não concluída ($status): $statusMessage"
                    } else {
                        "Instalação cancelada ou recusada pelo sistema."
                    }
                    AppUpdateManager.emitStatusEvent(AppUpdateManager.InstallSessionEvent.Failed(msg))
                }
            }
        }
    }
}
