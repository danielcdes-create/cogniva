package br.com.concursoprep.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import br.com.concursoprep.app.data.remote.ConcursoApiService
import br.com.concursoprep.app.data.remote.NativeSessionPayload
import br.com.concursoprep.app.domain.model.AuthSessionDto
import br.com.concursoprep.app.domain.model.UserProfileDto
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credenciais e seleção de conta isoladas do banco Room de cada `user_id`.
 * Segredos (`auth_token_*` e `installation_secret`) são cifrados em repouso via Android Keystore (AES-GCM).
 */
class AuthStore(context: Context) {
    private val prefs = context.getSharedPreferences("cogniva_prefs", Context.MODE_PRIVATE)

    private fun keystoreKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return@runCatching existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        generator.generateKey()
    }.getOrNull()

    private fun encryptSecret(plain: String): String {
        val key = keystoreKey() ?: return plain
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val ct = Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            "$ENC_PREFIX$iv:$ct"
        }.getOrDefault(plain)
    }

    private fun decryptSecret(stored: String?): String? {
        if (stored == null) return null
        if (!stored.startsWith(ENC_PREFIX)) {
            val upgraded = encryptSecret(stored)
            return if (upgraded != stored) stored else stored
        }
        val payload = stored.removePrefix(ENC_PREFIX)
        val sep = payload.indexOf(':')
        if (sep <= 0) return null
        val key = keystoreKey() ?: return null
        return runCatching {
            val iv = Base64.decode(payload.substring(0, sep), Base64.NO_WRAP)
            val ct = Base64.decode(payload.substring(sep + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun readSecret(prefKey: String): String? {
        val raw = prefs.getString(prefKey, null) ?: return null
        if (!raw.startsWith(ENC_PREFIX)) {
            val encrypted = encryptSecret(raw)
            if (encrypted != raw) {
                prefs.edit().putString(prefKey, encrypted).apply()
            }
            return raw
        }
        return decryptSecret(raw)
    }

    private fun writeSecret(prefKey: String, plain: String) {
        check(prefs.edit().putString(prefKey, encryptSecret(plain)).commit())
    }

    val installationId: String = prefs.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("installation_id", it).commit())
    }

    val installationSecret: String = readSecret("installation_secret") ?: ByteArray(32).also {
        SecureRandom().nextBytes(it)
    }.joinToString("") { "%02x".format(it) }.also {
        writeSecret("installation_secret", it)
    }

    val localUserId: Long = -(
        ByteBuffer.wrap(
            MessageDigest.getInstance("SHA-256")
                .digest("cogniva-native:$installationId:$installationSecret".toByteArray(Charsets.UTF_8))
        ).long.ushr(1).coerceAtLeast(1)
    )

    val selectedUserId: Long get() = prefs.getLong("user_id", localUserId)

    fun activeContestIds(owner: Long): Set<String> =
        if (selectedUserId == owner) {
            (prefs.getString("active_contests", "CPNU") ?: "CPNU")
                .split(',')
                .filter { it.isNotBlank() }
                .toSet()
        } else {
            emptySet()
        }

    fun token(userId: Long): String? = readSecret("auth_token_$userId")

    fun isRegisteredAccount(userId: Long): Boolean =
        prefs.getBoolean("account_registered_$userId", false)

    fun accept(session: AuthSessionDto) {
        val uid = session.profile.userId
        writeSecret("auth_token_$uid", session.token)
        check(
            prefs.edit()
                .putBoolean("account_registered_$uid", !session.profile.isGuest || !session.profile.email.isNullOrBlank())
                .commit()
        )
        saveProfile(session.profile)
    }

    fun pendingProfile(userId: Long): String? = prefs.getString("pending_profile_$userId", null)

    fun setPendingProfile(userId: Long, payload: String) {
        check(prefs.edit().putString("pending_profile_$userId", payload).commit())
    }

    fun clearPendingProfile(userId: Long, expected: String) {
        if (pendingProfile(userId) == expected) {
            check(prefs.edit().remove("pending_profile_$userId").commit())
        }
    }

    fun invalidate(userId: Long) {
        prefs.edit().remove("auth_token_$userId").apply()
    }

    fun clearSessionForLogout(userId: Long): Long {
        check(
            prefs.edit()
                .remove("auth_token_$userId")
                .putLong("user_id", localUserId)
                .remove("email")
                .putString("role", "student")
                .putBoolean("is_guest", true)
                .putBoolean("is_admin", false)
                .commit()
        )
        return localUserId
    }

    fun saveProfile(profile: UserProfileDto) {
        check(
            prefs.edit()
                .putBoolean("account_registered_${profile.userId}", !profile.isGuest || !profile.email.isNullOrBlank())
                .commit()
        )
        if (selectedUserId != profile.userId || !pendingProfile(profile.userId).isNullOrBlank()) return
        check(
            prefs.edit()
                .putString("display_name", profile.displayName)
                .putString("email", profile.email)
                .putString("role", profile.role)
                .putBoolean("is_guest", profile.isGuest)
                .putBoolean("is_admin", profile.isAdmin)
                .putInt("daily_questions_target", profile.dailyQuestionsTarget)
                .putFloat("daily_study_hours_target", profile.dailyStudyHoursTarget.toFloat())
                .putString("active_contests", profile.activeContestIds.joinToString(","))
                .commit()
        )
    }

    fun select(userId: Long) {
        check(prefs.edit().putLong("user_id", userId).commit())
    }

    suspend fun ensureSession(api: ConcursoApiService, owner: Long) {
        val existing = token(owner)
        if (!existing.isNullOrBlank()) {
            val expires = runCatching {
                val payload = String(
                    Base64.decode(existing.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP),
                    Charsets.UTF_8
                )
                org.json.JSONObject(payload).getLong("exp")
            }.getOrDefault(0L)
            if (expires > System.currentTimeMillis() / 1000 + 60) return
        }
        if (isRegisteredAccount(owner)) {
            error("Sua sessão expirou. Entre novamente com seu e-mail e senha em Ajustes; suas pendências locais estão preservadas.")
        }
        val response = api.guestSession(NativeSessionPayload(installationId, installationSecret))
        if (response.profile.userId != owner && owner > 0L) {
            error("Esta conta exige autenticação por e-mail e senha. As pendências do proprietário original foram preservadas.")
        }
        accept(response)
    }

    companion object {
        private const val KEY_ALIAS = "cogniva_auth_aes_key"
        private const val ENC_PREFIX = "enc:v1:"
    }
}
