package br.com.concursoprep.app.data

import android.content.Context
import br.com.concursoprep.app.data.remote.ConcursoApiService
import br.com.concursoprep.app.data.remote.NativeSessionPayload
import br.com.concursoprep.app.domain.model.AuthSessionDto
import java.security.SecureRandom
import java.util.UUID

/** Credentials and account selection are separate from the databases owned by each account. */
class AuthStore(context: Context) {
    private val prefs = context.getSharedPreferences("cogniva_prefs", Context.MODE_PRIVATE)
    val installationId: String = prefs.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("installation_id", it).commit())
    }
    val installationSecret: String = prefs.getString("installation_secret", null) ?: ByteArray(32).also {
        SecureRandom().nextBytes(it)
    }.joinToString("") { "%02x".format(it) }.also {
        check(prefs.edit().putString("installation_secret", it).commit())
    }
    val localUserId: Long = -(java.nio.ByteBuffer.wrap(java.security.MessageDigest.getInstance("SHA-256").digest("cogniva-native:$installationId:$installationSecret".toByteArray(Charsets.UTF_8))).long.ushr(1).coerceAtLeast(1))
    val selectedUserId: Long get() = prefs.getLong("user_id", localUserId)
    fun activeContestIds(owner: Long):Set<String> = if(selectedUserId==owner)(prefs.getString("active_contests","CPNU")?:"CPNU").split(',').filter { it.isNotBlank() }.toSet()else emptySet()
    fun token(userId: Long): String? = prefs.getString("auth_token_$userId", null)
    fun accept(session: AuthSessionDto) {
        check(prefs.edit().putString("auth_token_${session.profile.userId}", session.token).commit())
    }
    fun pendingProfile(userId: Long): String? = prefs.getString("pending_profile_$userId",null)
    fun setPendingProfile(userId: Long, payload: String) { check(prefs.edit().putString("pending_profile_$userId",payload).commit()) }
    fun clearPendingProfile(userId: Long, expected: String) { if(pendingProfile(userId)==expected) check(prefs.edit().remove("pending_profile_$userId").commit()) }
    fun invalidate(userId:Long){prefs.edit().remove("auth_token_$userId").apply()}
    fun saveProfile(profile:br.com.concursoprep.app.domain.model.UserProfileDto){
        if(selectedUserId!=profile.userId||!pendingProfile(profile.userId).isNullOrBlank())return
        check(prefs.edit().putString("display_name",profile.displayName).putString("username",profile.username)
            .putInt("daily_questions_target",profile.dailyQuestionsTarget).putFloat("daily_study_hours_target",profile.dailyStudyHoursTarget.toFloat())
            .putString("active_contests",profile.activeContestIds.joinToString(",")).commit())
    }
    fun select(userId: Long) { check(prefs.edit().putLong("user_id", userId).commit()) }
    suspend fun ensureSession(api: ConcursoApiService, owner: Long) {
        val existing=token(owner)
        if(existing!=null) {
            val expires=runCatching {
                val payload=String(android.util.Base64.decode(existing.split('.')[1],android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP),Charsets.UTF_8)
                org.json.JSONObject(payload).getLong("exp")
            }.getOrDefault(0)
            if(expires>System.currentTimeMillis()/1000+60) return
        }
        // Re-enrollment is idempotent and refreshes only the installation's proven account.
        val response = api.nativeSession(NativeSessionPayload(installationId, installationSecret))
        if (response.profile.userId != owner) error("Esta conta exige vínculo verificado. As pendências do proprietário original foram preservadas.")
        accept(response)
        if(pendingProfile(owner).isNullOrBlank()&&selectedUserId==owner) {
            val p=response.profile
            check(prefs.edit().putString("display_name",p.displayName).putString("username",p.username)
                .putInt("daily_questions_target",p.dailyQuestionsTarget).putFloat("daily_study_hours_target",p.dailyStudyHoursTarget.toFloat())
                .putString("active_contests",p.activeContestIds.joinToString(",")).commit())
        }
    }
}
